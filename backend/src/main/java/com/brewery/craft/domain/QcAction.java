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
 * 质检操作流水：只追加，永不修改、永不删除。
 * 每一次 送检/通过/驳回/撤销 都留下操作人、时间、结论与原因。
 */
@Data
@Entity
@Table(name = "batch_qc_action")
public class QcAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "round_seq", nullable = false)
    private Integer roundSeq;

    /** SUBMIT/PASS/REJECT/REVOKE */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 动作之后该轮的结论：PENDING/PASS/REJECT/REVOKED */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
