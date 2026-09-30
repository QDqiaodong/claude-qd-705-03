package com.brewery.craft.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 迁罐方案（可确认、可过期、可失败回退）。
 *
 * <p>维护窗口与在制批次冲突时生成：列出原罐、目标罐、批次当前阶段、预计迁移时间。
 * 状态链：
 * <ul>
 *   <li>PROPOSED 已计算待确认 → EXECUTING 确认后执行中 → EXECUTED 迁移完成；</li>
 *   <li>PROPOSED → EXPIRED 方案过期（维护时间/目标罐状态在确认前被别人改动、
 *       目标罐被另一条迁移抢占、同一批次有了更新的方案），必须重新计算；</li>
 *   <li>EXECUTING → FAILED 迁移中途失败：已完成步骤保留在 transfer_step，
 *       占用已补偿回退，可从 FAILED 继续处理（重新执行或重新选目标）。</li>
 * </ul>
 *
 * <p>并发口径：{@link #activeSlot} 在 PROPOSED/EXECUTING 时为 "B"+batchId，
 * 数据库唯一约束保证同一批次同时只有一个生效方案；目标罐在重叠时段只能接收一个批次，
 * 在确认时加行锁二次校验。{@link #fingerprint} 是生成方案时的条件快照，
 * 确认时重算不一致即判过期。
 */
@Data
@Entity
@Table(name = "fermenter_transfer_plan")
public class TankTransferPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案单号，如 TP-2026-001 */
    @Column(name = "plan_no", nullable = false, length = 24)
    private String planNo;

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 原罐号（冲突罐） */
    @Column(name = "source_tank_no", nullable = false, length = 16)
    private String sourceTankNo;

    /** 推荐目标罐 id / 罐号（无法处理时为空，原因落在 candidates 与 conflictSummary） */
    @Column(name = "target_tank_id")
    private Long targetTankId;

    @Column(name = "target_tank_no", length = 16)
    private String targetTankNo;

    /** PROPOSED/EXECUTING/EXECUTED/EXPIRED/FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 生成方案时批次所处阶段（FERMENT/CONDITION） */
    @Column(name = "batch_stage", nullable = false, length = 16)
    private String batchStage;

    /** 预计迁移时间（默认取维护开始前，前端可调整） */
    @Column(name = "planned_move_at", nullable = false)
    private LocalDateTime plannedMoveAt;

    /** 迁移实际完成时间 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 迁罐后批次预计占用目标罐到何时（重叠时段校验用） */
    @Column(name = "estimated_occupy_until")
    private LocalDateTime estimatedOccupyUntil;

    /**
     * 条件指纹（JSON）：窗口起止、目标罐状态/容量/温区/维护占用、批次阶段/罐号/体积 等
     * 生成瞬间的值。确认时按当前数据重算，不一致 → 409 过期并要求重新计算。
     */
    @Column(name = "fingerprint", nullable = false, length = 2000)
    private String fingerprint;

    /** 过期/失败原因（人读） */
    @Column(name = "status_reason", length = 512)
    private String statusReason;

    /** 无法处理摘要（没有合格目标罐时） */
    @Column(name = "conflict_summary", length = 512)
    private String conflictSummary;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁：两个人同时确认同一方案，只有一条成功 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * 生效槽位：PROPOSED/EXECUTING 时为 "B" + batchId，其余状态为 null。
     * 配合部分唯一索引实现「同一批次只能有一个生效方案」。
     */
    @Column(name = "active_slot", length = 40)
    private String activeSlot;

    /** 全部候选目标罐评估结果（合格的 + 不合格的不适配原因） */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<TransferCandidate> candidates = new ArrayList<>();

    /** 执行步骤进度（含失败后已完成到哪一步、回退到哪一步） */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepSeq ASC")
    private List<TransferStep> steps = new ArrayList<>();
}
