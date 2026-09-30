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
 * 批次创建当时的配方用量【快照】（不可变）。
 *
 * <p>批次建档时从配方明细复制一份独立副本：之后配方再改单位、改用量、做缩放，
 * 都不会改变已生产批次显示的投料量 —— 与质检轮次「送检快照不可变」同一原则。
 * 历史批次（本特性上线前已存在）由数据迁移回填，{@link #snapshotSource} = MIGRATED 明示来源；
 * 新建批次为 CREATED，记录的是创建当时的真实配方版本。
 */
@Data
@Entity
@Table(name = "batch_recipe_item")
public class BatchRecipeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 投放阶段（快照时的 Map key） */
    @Column(name = "stage", nullable = false, length = 24)
    private String stage;

    @Column(name = "ingredient_code", nullable = false, length = 24)
    private String ingredientCode;

    @Column(name = "ingredient_name", length = 64)
    private String ingredientName;

    @Column(name = "ingredient_type", length = 16)
    private String ingredientType;

    /** 显示值（原样复制） */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 8)
    private String unit;

    /** 规范化副本：维度 / 规范单位 / 基准单位数量 */
    @Column(name = "dimension", length = 16)
    private String dimension;

    @Column(name = "normalized_unit", length = 8)
    private String normalizedUnit;

    @Column(name = "normalized_quantity", precision = 14, scale = 3)
    private BigDecimal normalizedQuantity;

    @Column(name = "unit_status", length = 16)
    private String unitStatus;

    @Column(name = "unit_note", length = 256)
    private String unitNote;

    /** CREATED = 批次建档时快照；MIGRATED = 历史批次由迁移回填 */
    @Column(name = "snapshot_source", length = 16, nullable = false)
    private String snapshotSource;
}
