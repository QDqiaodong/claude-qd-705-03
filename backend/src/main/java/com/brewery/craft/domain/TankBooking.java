package com.brewery.craft.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 待入罐预约（维护时间线上的「未来占罐」）。
 *
 * <p>当前占罐批次看 batch.fermenter_no；计划批次（PLAN 等）哪天入哪个罐由本表表达，
 * 这样维护窗口才能和「在制批次 + 未来待入罐批次」放在同一条时间线上判断
 * 无冲突 / 需要迁罐 / 必须延期 / 无法处理。
 *
 * <p>与维护窗口冲突而被延期时，{@link #status} 置 DELAYED，原值保存在
 * {@link #originalPlannedEnterAt}，并在维护事件里留 BOOKING_DELAYED 流水；
 * 延期不是删除，后续窗口提前结束后排程可按新窗口重新建议。
 */
@Data
@Entity
@Table(name = "fermenter_booking")
public class TankBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 预约单号，如 BK-2026-001 */
    @Column(name = "booking_no", nullable = false, length = 24)
    private String bookingNo;

    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** PLANNED 待入罐 / DELAYED 因维护延期 / FULFILLED 已入罐 / CANCELLED 取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 当前生效的计划入罐时间 */
    @Column(name = "planned_enter_at", nullable = false)
    private LocalDateTime plannedEnterAt;

    /** 计划出罐时间（排产预计，可空） */
    @Column(name = "planned_exit_at")
    private LocalDateTime plannedExitAt;

    /** 登记时的原始入罐时间（延期留痕） */
    @Column(name = "original_planned_enter_at", nullable = false)
    private LocalDateTime originalPlannedEnterAt;

    /** 触发延期的维护窗口 id */
    @Column(name = "delayed_by_window_id")
    private Long delayedByWindowId;

    @Column(name = "delay_reason", length = 512)
    private String delayReason;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
