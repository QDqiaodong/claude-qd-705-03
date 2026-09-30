package com.brewery.craft.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/** 模块三：配方。 */
@Data
@Entity
@Table(name = "recipe")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配方编码 */
    @Column(name = "code", nullable = false, length = 24)
    private String code;

    /** 配方名 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 风格：IPA/STOUT/PILSNER/WHEAT/SOUR/PALE_ALE */
    @Column(name = "style", nullable = false, length = 16)
    private String style;

    /** 目标原浓 OG */
    @Column(name = "target_og", precision = 5, scale = 3)
    private BigDecimal targetOg;

    /** 目标酒精度 %vol */
    @Column(name = "target_abv", precision = 4, scale = 2)
    private BigDecimal targetAbv;

    /** 目标苦度 IBU */
    @Column(name = "target_ibu", precision = 5, scale = 1)
    private BigDecimal targetIbu;

    /** 目标色度 EBC */
    @Column(name = "target_ebc", precision = 5, scale = 1)
    private BigDecimal targetEbc;

    /**
     * 基准批量（L）：这张配方明细所对应的标准投料批量，缩放比例 = 目标批量 / 基准批量。
     * 种子/历史配方由数据迁移按 1000L 兜底；应用缩放后同步刷新为本次目标批量。
     */
    @Column(name = "base_batch_l", precision = 10, scale = 2)
    private BigDecimal baseBatchL;

    @Column(name = "process_note", length = 512)
    private String processNote;

    /**
     * 【主数据访问路线：@OneToMany + @MapKeyColumn 的 Map 集合映射】
     * key = 投放阶段（MASH_IN / BOIL_60 / DRYHOP_1 …），落在 recipe_item.stage 列；
     * value = 该阶段的原料用量明细实体。
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinTable(
            name = "recipe_item_slot",
            joinColumns = @JoinColumn(name = "recipe_id"),
            inverseJoinColumns = @JoinColumn(name = "item_id", unique = true))
    @MapKeyColumn(name = "stage")
    private Map<String, RecipeItem> items = new LinkedHashMap<>();

    /**
     * 乐观锁版本：缩放预览→确认之间若有人改过配方（增删改明细、改基准批量），
     * 应用时版本对不上会被拒绝并提示重新计算，绝不把旧预览覆盖到新数据上。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
