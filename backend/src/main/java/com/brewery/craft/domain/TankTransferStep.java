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
 * 模块七：单次迁罐尝试的分步进度（处理进度）。
 *
 * 一次方案可以有多次尝试（迁移中途失败 → 回退到待处理 → 继续处理），每次尝试五行：
 * attempt_seq=1 的 DONE/DISCONNECT…；失败后 attempt_seq=2 再来一轮。
 * 这使得“已经完成到哪一步”和“回退了哪些动作”都可追溯，且失败回退时原罐/目标罐不会同时显示占用。
 */
@Data
@Entity
@Table(name = "tank_transfer_step")
public class TankTransferStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 第几次尝试（从 1 开始） */
    @Column(name = "attempt_seq", nullable = false)
    private Integer attemptSeq;

    /** 1~5：DISCONNECT/DRAIN/MOVE/REFILL/RECONNECT */
    @Column(name = "step_seq", nullable = false)
    private Integer stepSeq;

    /** 步骤码 */
    @Column(name = "step_code", nullable = false, length = 24)
    private String stepCode;

    /** DONE 已完成 / FAILED 失败 / ROLLED_BACK 已回退 / PENDING 待执行 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 失败/回退原因 */
    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "operator", length = 32)
    private String operator;
}
