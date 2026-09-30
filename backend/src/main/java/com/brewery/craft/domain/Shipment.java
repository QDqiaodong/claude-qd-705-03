package com.brewery.craft.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块五之二：出货单。 */
@Data
@Entity
@Table(name = "shipment")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 出货单号 */
    @Column(name = "shipment_no", nullable = false, length = 24)
    private String shipmentNo;

    /** 客户 */
    @Column(name = "customer", nullable = false, length = 64)
    private String customer;

    /** 批次号 */
    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 包装形式：BOTTLE 瓶 / CAN 易拉罐 / KEG 桶 */
    @Column(name = "package_form", nullable = false, length = 16)
    private String packageForm;

    /** 数量（瓶/罐/桶） */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 单价（元） */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 金额 = 数量 × 单价 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "ship_date")
    private LocalDate shipDate;

    /** PENDING 待发 / FROZEN 冻结 / SHIPPED 已发 / PAID 已结款 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 冻结原因（撤销/驳回/复检/报废） */
    @Column(name = "frozen_reason", length = 512)
    private String frozenReason;

    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    /** 触发冻结的质检轮次 */
    @Column(name = "frozen_round_seq")
    private Integer frozenRoundSeq;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
