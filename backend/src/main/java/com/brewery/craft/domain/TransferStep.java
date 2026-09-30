package com.brewery.craft.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 迁罐执行步骤（处理进度 + 失败恢复依据）。
 *
 * <p>一次确认按固定步骤推进，每步先 PENDING → DONE；任一步抛错则 FAILED，
 * 已经 DONE 的步骤按逆序补偿并记 ROLLED_BACK + 补偿说明。这样任何时刻
 * 原罐、目标罐都不会同时显示占用，且「已经完成到哪一步」完整可查，
 * 失败后可在 FAILED 方案上继续处理。
 *
 * <pre>
 * 1 PRECHECK      确认前条件复核（窗口/批次/目标罐最新条件）
 * 2 RESERVE_TARGET 目标罐置 FERMENTING/熟成 并锁定当前批次（占罐预定）
 * 3 DETACH_SOURCE  原罐解除占用并转 CLEANING
 * 4 ATTACH_TARGET  批次 fermenter_no 改写为目标罐，目标罐 current_batch_no 落批
 * 5 HISTORY        追加 batch_tank_history（MOVED，曲线/阶段时间不动）
 * 6 COMPLETE       方案置 EXECUTED
 * </pre>
 */
@Data
@Entity
@Table(name = "fermenter_transfer_step")
public class TransferStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TankTransferPlan plan;

    /** 步骤序号 1..6 */
    @Column(name = "step_seq", nullable = false)
    private Integer stepSeq;

    /** PRECHECK/RESERVE_TARGET/DETACH_SOURCE/ATTACH_TARGET/HISTORY/COMPLETE */
    @Column(name = "step_code", nullable = false, length = 24)
    private String stepCode;

    /** PENDING/DONE/FAILED/ROLLED_BACK/SKIPPED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 失败原因 */
    @Column(name = "fail_reason", length = 512)
    private String failReason;

    /** 回退补偿说明 */
    @Column(name = "rollback_note", length = 512)
    private String rollbackNote;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;
}
