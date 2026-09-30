package com.brewery.craft.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块七：在制批次迁罐方案。
 *
 * 生命周期：
 *   PENDING 待确认（含「迁移失败后回到待处理继续」）
 *   → EXECUTING 迁移进行中（记录已完成到第几步，防止原罐/目标罐同时显示占用）
 *   → EXECUTED 已执行（历史不可改写）；
 *   EXPIRED 方案过期（窗口改期/取消、目标罐被抢占、批次已不在原罐…，必须重新计算）；
 *   CANCELLED 已取消（仅 PENDING 可取消）。
 *
 * 唯一生效方案：同一批次同时只允许一条 PENDING/EXECUTING/EXECUTED 方案；
 * 同一目标罐在重叠时段只能接收一条生效方案（DB 预留表 + 业务校验双保险）。
 */
@Data
@Entity
@Table(name = "tank_transfer_plan")
public class TankTransferPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 触发本方案的维护窗口 */
    @Column(name = "maintenance_id", nullable = false)
    private Long maintenanceId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批号冗余，便于留痕/列表 */
    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 原罐 */
    @Column(name = "source_tank_no", nullable = false, length = 16)
    private String sourceTankNo;

    /** 目标罐 */
    @Column(name = "target_tank_no", nullable = false, length = 16)
    private String targetTankNo;

    /** 生成方案时批次所处阶段（FERMENT/CONDITION…），迁移不重置阶段 */
    @Column(name = "batch_stage", nullable = false, length = 16)
    private String batchStage;

    /** 批次产量快照（L），用于容量适配复核 */
    @Column(name = "batch_volume_l", precision = 10, scale = 2)
    private java.math.BigDecimal batchVolumeL;

    /** 批次发酵所需温控区间快照（℃），用于温区兼容复核 */
    @Column(name = "need_temp_min", precision = 5, scale = 2)
    private java.math.BigDecimal needTempMin;

    @Column(name = "need_temp_max", precision = 5, scale = 2)
    private java.math.BigDecimal needTempMax;

    /** 预计迁移时间（默认维护开始前，可人工指定） */
    @Column(name = "planned_move_at", nullable = false)
    private LocalDateTime plannedMoveAt;

    /** 方案预留目标罐的时段起（= plannedMoveAt 前少量缓冲） */
    @Column(name = "reserve_from", nullable = false)
    private LocalDateTime reserveFrom;

    /** 方案预留目标罐的时段止（= 维护预计恢复，迁回/继续占用到该时点） */
    @Column(name = "reserve_to", nullable = false)
    private LocalDateTime reserveTo;

    /** PENDING/EXECUTING/EXECUTED/EXPIRED/CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 已完成到第几步：0 未开始，1~5 对应 DISCONNECT/DRAIN/MOVE/REFILL/RECONNECT */
    @Column(name = "completed_step", nullable = false)
    private Integer completedStep;

    /** 最近一次迁移失败停在哪一步（EXECUTING→回 PENDING 时留痕） */
    @Column(name = "failed_step")
    private Integer failedStep;

    /** 最近一次失败原因 */
    @Column(name = "fail_reason", length = 512)
    private String failReason;

    /** 累计尝试次数（失败后继续处理会 +1） */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** 过期原因（EXPIRED 时钉死：维护改期/目标罐被抢占/批次状态变化…） */
    @Column(name = "expired_reason", length = 512)
    private String expiredReason;

    /**
     * 方案条件指纹：生成时把维护窗口起止、目标罐状态/容量/温区、批次阶段/罐号/版本等条件摘要钉死，
     * 确认时重算比对，不一致即判旧方案过期（HTTP 409），提示重新计算。
     */
    @Column(name = "conditions_hash", nullable = false, length = 64)
    private String conditionsHash;

    @Column(name = "proposed_by", nullable = false, length = 32)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁：两人同时确认/继续处理同一方案时只允许一条成功 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
