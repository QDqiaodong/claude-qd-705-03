package com.brewery.craft.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块三：配方原料用量明细。
 *
 * 它同时是 {@link Recipe#getItems()} 这张 Map 的 value —— 投放阶段不写在本实体里，
 * 而是由 {@code @MapKeyColumn(name = "stage")} 单独维护（Map 的 key 即这一行所处的投放阶段）。
 *
 * 计量双轨：
 * <ul>
 *   <li>{@code quantity + unit} 是酿酒师习惯的<b>显示值</b>（可能写 kg，也可能写 g），页面原样保留；</li>
 *   <li>{@code dimension + normalizedUnit + normalizedQuantity} 是换算到维度基准单位
 *       （质量 g / 体积 mL / 计数 pkg）后的<b>规范化值</b>，所有汇总、占比、缩放只认它。</li>
 * </ul>
 * {@code unitStatus = NEEDS_REVIEW} 的历史行保留原值但不参与任何计算，必须人工处理。
 */
@Data
@Entity
@Table(name = "recipe_item")
public class RecipeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属配方：这张表的关系由 Recipe.items 单向维护（Map 的 key 落在 stage 列） */
    @Column(name = "ingredient_code", nullable = false, length = 24)
    private String ingredientCode;

    /** 原料名称（冗余，便于列表展示） */
    @Column(name = "ingredient_name", length = 64)
    private String ingredientName;

    /** 原料大类：MALT/HOP/YEAST/ADJUNCT/WATER */
    @Column(name = "ingredient_type", length = 16)
    private String ingredientType;

    /** 用量（显示值，按 {@link #unit} 这个酿酒师习惯单位） */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    /** 显示单位：kg / g / L / mL / pkg（历史数据可能为空或旧写法，保留不改） */
    @Column(name = "unit", length = 8)
    private String unit;

    /** 计量维度 MASS/VOLUME/COUNT；无法推导时为 null（待处理） */
    @Column(name = "dimension", length = 16)
    private String dimension;

    /** 规范化时采用的规范单位码（kg/g/L/mL/pkg） */
    @Column(name = "normalized_unit", length = 8)
    private String normalizedUnit;

    /** 规范化数量（基准单位 g/mL/pkg）；待处理行为 null */
    @Column(name = "normalized_quantity", precision = 14, scale = 3)
    private BigDecimal normalizedQuantity;

    /** OK / NORMALIZED（旧写法归一）/ INFERRED（空单位推导）/ NEEDS_REVIEW（无法推导） */
    @Column(name = "unit_status", length = 16)
    private String unitStatus;

    /** 单位推导/归一说明（可解释，给页面与审计看） */
    @Column(name = "unit_note", length = 256)
    private String unitNote;

    /** 投放备注（如"60 分钟苦花"） */
    @Column(name = "note", length = 128)
    private String note;
}
