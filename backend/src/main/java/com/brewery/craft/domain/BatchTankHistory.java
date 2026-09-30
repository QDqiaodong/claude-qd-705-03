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
 * 批次历史罐号（只追加）。
 *
 * <p>迁罐确认后追加一条 MOVED：from → to，批次的发酵曲线（batch_temp/gravity_reading）、
 * 阶段时间（mash_start/ferment_start…）、批次状态都不动——换罐不拆断曲线、不重置阶段。
 * 批次首次入罐记 ASSIGNED；迁移中途失败的补偿记 ROLLED_BACK，明示「这一步做到哪又退回去」，
 * 保证原罐与目标罐不会同时显示占用。
 */
@Data
@Entity
@Table(name = "batch_tank_history")
public class BatchTankHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** ASSIGNED 首次入罐 / MOVED 维护迁罐 / ROLLED_BACK 迁移失败回退 */
    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    /** 原罐号（首次入罐时为空） */
    @Column(name = "from_tank_no", length = 16)
    private String fromTankNo;

    /** 新罐号（回退记录里仍为目标罐号，用 changeType 区分结果） */
    @Column(name = "to_tank_no", length = 16)
    private String toTankNo;

    /** 触发迁罐的维护窗口 id（首次入罐为空） */
    @Column(name = "window_id")
    private Long windowId;

    /** 关联迁罐方案 id（回退记录指向失败的方案） */
    @Column(name = "plan_id")
    private Long planId;

    /** 迁罐时批次所处阶段（FERMENT/CONDITION…），曲线/阶段时间均保持不变 */
    @Column(name = "batch_stage", length = 16)
    private String batchStage;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
