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

/** 模块四：原料库存。 */
@Data
@Entity
@Table(name = "ingredient")
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原料编号 */
    @Column(name = "code", nullable = false, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** MALT 麦芽 / HOP 啤酒花 / YEAST 酵母 / ADJUNCT 辅料 / WATER 水处理剂 */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "unit", nullable = false, length = 8)
    private String unit;

    /** 当前库存 */
    @Column(name = "stock", nullable = false, precision = 12, scale = 3)
    private BigDecimal stock;

    /** 安全库存 */
    @Column(name = "safety_stock", nullable = false, precision = 12, scale = 3)
    private BigDecimal safetyStock;

    /** 单价（元） */
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "supplier", length = 64)
    private String supplier;

    /** 保质期至 */
    @Column(name = "shelf_life_to")
    private LocalDate shelfLifeTo;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
