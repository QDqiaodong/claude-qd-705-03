package com.brewery.craft.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块二：啤酒批次。 */
@Data
@Entity
@Table(name = "batch")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批号，如 BC-2026-101 */
    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 所用配方 id */
    @Column(name = "recipe_id")
    private Long recipeId;

    /** 占用罐号 */
    @Column(name = "fermenter_no", length = 16)
    private String fermenterNo;

    /** 酿酒师 */
    @Column(name = "brewer", length = 32)
    private String brewer;

    @Column(name = "brew_date")
    private LocalDate brewDate;

    @Column(name = "mash_start")
    private LocalDateTime mashStart;

    @Column(name = "mash_end")
    private LocalDateTime mashEnd;

    @Column(name = "boil_end")
    private LocalDateTime boilEnd;

    @Column(name = "ferment_start")
    private LocalDateTime fermentStart;

    @Column(name = "expected_mature")
    private LocalDate expectedMature;

    @Column(name = "actual_mature")
    private LocalDate actualMature;

    /** 产量（L） */
    @Column(name = "volume_l", precision = 10, scale = 2)
    private BigDecimal volumeL;

    /** 酒精度 %vol */
    @Column(name = "abv", precision = 4, scale = 2)
    private BigDecimal abv;

    /** 苦度 IBU */
    @Column(name = "ibu", precision = 5, scale = 1)
    private BigDecimal ibu;

    /** 色度 EBC */
    @Column(name = "ebc", precision = 5, scale = 1)
    private BigDecimal ebc;

    /** PLAN/MASH/BOIL/FERMENT/CONDITION/PACKAGED/SCRAPPED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * 【@ElementCollection + @MapKeyColumn】发酵期逐日温度曲线：key = D1/D2…，value = 温度（℃）。
     */
    @ElementCollection
    @CollectionTable(name = "batch_temp_reading", joinColumns = @JoinColumn(name = "batch_id"))
    @MapKeyColumn(name = "day_key")
    @Column(name = "temp_value")
    private Map<String, BigDecimal> tempReadings = new LinkedHashMap<>();

    /**
     * 【@ElementCollection + @MapKeyColumn】发酵期逐日糖度曲线：key = D1/D2…，value = 表观糖度（°P）。
     */
    @ElementCollection
    @CollectionTable(name = "batch_gravity_reading", joinColumns = @JoinColumn(name = "batch_id"))
    @MapKeyColumn(name = "day_key")
    @Column(name = "gravity_value")
    private Map<String, BigDecimal> gravityReadings = new LinkedHashMap<>();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
