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

/** 模块五之一：灌装单。 */
@Data
@Entity
@Table(name = "bottling")
public class Bottling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 灌装单号 */
    @Column(name = "bottling_no", nullable = false, length = 24)
    private String bottlingNo;

    /** 所属批次号 */
    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 包装形式：BOTTLE 瓶 / CAN 易拉罐 / KEG 桶 */
    @Column(name = "package_form", nullable = false, length = 16)
    private String packageForm;

    /** 数量（按包装单位：瓶/罐/桶） */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "bottle_date")
    private LocalDate bottleDate;

    /** 灌装损耗（L） */
    @Column(name = "loss_l", precision = 10, scale = 2)
    private BigDecimal lossL;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
