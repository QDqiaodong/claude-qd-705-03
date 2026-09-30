package com.brewery.craft.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块一：发酵罐。 */
@Data
@Entity
@Table(name = "fermenter")
public class Fermenter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 罐号，如 FK-01 */
    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    /** 有效容量（L） */
    @Column(name = "capacity_l", nullable = false, precision = 10, scale = 2)
    private BigDecimal capacityL;

    /** 材质：STAINLESS 不锈钢 / JACKETED 夹套 / CONICAL 锥底 */
    @Column(name = "material", nullable = false, length = 16)
    private String material;

    /** 温控区间下限（℃） */
    @Column(name = "temp_min", precision = 5, scale = 2)
    private BigDecimal tempMin;

    /** 温控区间上限（℃） */
    @Column(name = "temp_max", precision = 5, scale = 2)
    private BigDecimal tempMax;

    /** 冷却方式：GLYCOL 乙二醇 / ICE_WATER 冰水 / AIR 风冷 */
    @Column(name = "cooling", length = 16)
    private String cooling;

    /** 状态：IDLE/MASHING/BOILING/FERMENTING/CONDITIONING/CLEANING/DISABLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 当前批次号 */
    @Column(name = "current_batch_no", length = 24)
    private String currentBatchNo;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
