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
 * 模块七：迁罐操作流水（只追加）。
 * PROPOSE/CONFIRM/STEP_DONE/FAIL/RESUME/EXECUTE/EXPIRE/CANCEL 全部留痕，
 * 已执行的迁罐只能被新记录补充，绝不原地改写。
 */
@Data
@Entity
@Table(name = "tank_transfer_event")
public class TankTransferEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** PROPOSE/CONFIRM/STEP_DONE/STEP_FAIL/ROLLBACK/RESUME/EXECUTE/EXPIRE/CANCEL */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    /** 关联步骤（STEP_DONE/STEP_FAIL/ROLLBACK 时） */
    @Column(name = "step_seq")
    private Integer stepSeq;

    /** 第几次尝试 */
    @Column(name = "attempt_seq")
    private Integer attemptSeq;

    @Column(name = "from_tank_no", length = 16)
    private String fromTankNo;

    @Column(name = "to_tank_no", length = 16)
    private String toTankNo;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
