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
 * 模块七：批次历史罐号（只追加的占罐履历）。
 *
 * 换罐绝不直接“改掉”批次罐号了事：每入一个罐开一行（entered_at），出罐时把这行关闭（left_at/reason），
 * 批次发酵曲线、阶段时间仍挂在同一个 batch 上继续记，历史曲线不断、阶段不重置。
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

    /** 该段占罐期间批次所处阶段（入罐瞬间快照） */
    @Column(name = "stage", length = 16)
    private String stage;

    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    /** 离开时间；NULL = 当前正在占用 */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    /** ASSIGN 手工入罐 / TRANSFER 维护迁罐 / RELEASE 出罐灌装 */
    @Column(name = "enter_reason", length = 24)
    private String enterReason;

    /** 关联的迁罐方案（迁罐行才有） */
    @Column(name = "transfer_plan_id")
    private Long transferPlanId;

    /** 离开原因：TRANSFER_OUT 迁出 / RELEASE 出罐 */
    @Column(name = "leave_reason", length = 24)
    private String leaveReason;
}
