package com.brewery.craft.domain;

import java.math.BigDecimal;

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
 * 迁罐候选目标罐评估明细（随方案不可变快照）。
 *
 * <p>每个候选罐都留下「合格/不合格 + 不适配原因」：
 * 空闲、容量足够、温控范围兼容、不在维护窗口、时间段不被其他批次/预约占用，
 * 全部通过 eligible=true 才可作为目标罐。
 */
@Data
@Entity
@Table(name = "fermenter_transfer_candidate")
public class TransferCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TankTransferPlan plan;

    @Column(name = "tank_id", nullable = false)
    private Long tankId;

    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    @Column(name = "capacity_l", precision = 10, scale = 2)
    private BigDecimal capacityL;

    @Column(name = "temp_min", precision = 5, scale = 2)
    private BigDecimal tempMin;

    @Column(name = "temp_max", precision = 5, scale = 2)
    private BigDecimal tempMax;

    /** 是否全部条件通过 */
    @Column(name = "eligible", nullable = false)
    private boolean eligible;

    /** 是否被选为推荐目标罐 */
    @Column(name = "selected", nullable = false)
    private boolean selected;

    /** 评估时罐体状态 */
    @Column(name = "tank_status", length = 16)
    private String tankStatus;

    /**
     * 不适配原因码（多个以逗号拼接）：
     * SOURCE 即原罐不可迁回 / NOT_IDLE 非空闲 / CAPACITY 容量不足 /
     * TEMP_RANGE 温控范围不兼容 / IN_MAINTENANCE 处于维护窗口 /
     * OCCUPIED_BATCH 时段被在制批次占用 / OCCUPIED_BOOKING 时段被预约占用 /
     * TARGET_TAKEN 已被其他生效迁罐方案锁定。
     */
    @Column(name = "reject_reasons", length = 256)
    private String rejectReasons;
}
