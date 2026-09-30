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
 * 模块七：目标罐时段预留。
 *
 * 一条 PENDING/EXECUTING 迁罐方案对应一行预留；方案过期/取消/执行结束（批次正式占罐后转占用关系）
 * 都会释放（status=RELEASED，行保留作历史）。
 *
 * 数据库层用唯一键兜底“同一目标罐重叠时段只接收一条生效预留”：
 * 业务先做时段重叠校验，极端并发下再靠本键挡住第二条（捕获唯一键冲突 → 旧方案过期提示重算）。
 */
@Data
@Entity
@Table(name = "tank_transfer_reservation")
public class TransferReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    @Column(name = "reserve_from", nullable = false)
    private LocalDateTime reserveFrom;

    @Column(name = "reserve_to", nullable = false)
    private LocalDateTime reserveTo;

    /** ACTIVE 生效中 / RELEASED 已释放（执行完成/过期/取消） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** 释放原因：EXECUTED/EXPIRED/CANCELLED */
    @Column(name = "release_reason", length = 24)
    private String releaseReason;
}
