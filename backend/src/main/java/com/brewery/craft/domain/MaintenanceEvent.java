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
 * 维护窗口操作流水（只追加）。
 *
 * <ul>
 *   <li>REGISTER 登记 / ADJUST 调整时间原因 / START 开工 / FINISH 完工（含提前结束）
 *       / CANCEL 取消 / BOOKING_DELAYED 关联待入罐批次被迫延期；</li>
 *   <li>已经开工的维护不能删除，所有后续变化都以新事件保留原因，绝不改写旧事件。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "fermenter_maintenance_event")
public class MaintenanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private MaintenanceWindow window;

    /** REGISTER/ADJUST/START/FINISH/CANCEL/BOOKING_DELAYED */
    @Column(name = "action", nullable = false, length = 24)
    private String action;

    /** 动作之后窗口所处状态 */
    @Column(name = "result_status", length = 16)
    private String resultStatus;

    /** 变更前的开始/预计恢复时间（调整留痕用） */
    @Column(name = "before_start_at")
    private LocalDateTime beforeStartAt;

    @Column(name = "before_recover_at")
    private LocalDateTime beforeRecoverAt;

    @Column(name = "after_start_at")
    private LocalDateTime afterStartAt;

    @Column(name = "after_recover_at")
    private LocalDateTime afterRecoverAt;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    /** 原因/说明（取消原因、延期原因、影响范围变化等） */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
