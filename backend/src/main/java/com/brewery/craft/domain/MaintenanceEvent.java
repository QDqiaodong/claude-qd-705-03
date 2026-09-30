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
 * 模块七：维护窗口操作流水（只追加）。
 * 登记 / 改期 / 取消 / 开始 / 提前结束 / 延期 逐条留痕，旧值→新值与原因都钉死。
 */
@Data
@Entity
@Table(name = "fermenter_maintenance_event")
public class MaintenanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "maintenance_id", nullable = false)
    private Long maintenanceId;

    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    /** REGISTER 登记 / RESCHEDULE 改期 / CANCEL 取消 / START 开始 / FINISH_EARLY 提前结束 / EXTEND 延期 */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    /** 变更前开始时间（改期/延期用） */
    @Column(name = "from_start_at")
    private LocalDateTime fromStartAt;

    /** 变更后开始时间 */
    @Column(name = "to_start_at")
    private LocalDateTime toStartAt;

    /** 变更前预计恢复 */
    @Column(name = "from_recover_at")
    private LocalDateTime fromRecoverAt;

    /** 变更后预计恢复 */
    @Column(name = "to_recover_at")
    private LocalDateTime toRecoverAt;

    /** 原因（取消原因/延期原因/提前结束说明） */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
