package com.brewery.craft.measure;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;

/**
 * 计量维度：质量、体积、计数。
 *
 * <p>统一可解释的计量规则：同一维度内先换算到基准单位再汇总；
 * <b>质量与体积（以及计数）不能直接相加</b>。任何「总用量 / 占比 / 缩放」都必须带维度。
 */
@Getter
public enum Dimension {

    /** 质量：基准单位 g（克），支持 kg / g */
    MASS("g", "质量"),
    /** 体积：基准单位 mL（毫升），支持 L / mL */
    VOLUME("mL", "体积"),
    /** 计数：基准单位 pkg（包/袋等不可再分包装），仅支持 pkg */
    COUNT("pkg", "计数");

    private final String baseUnit;
    private final String displayName;

    Dimension(String baseUnit, String displayName) {
        this.baseUnit = baseUnit;
        this.displayName = displayName;
    }

    public static Dimension nameOf(String v) {
        if (v == null) {
            return null;
        }
        return switch (v.trim().toUpperCase()) {
            case "MASS", "质量" -> MASS;
            case "VOLUME", "体积" -> VOLUME;
            case "COUNT", "计数" -> COUNT;
            default -> null;
        };
    }

    /** 各维度在基准单位下的取整步长（最小可投放量）：克 / 毫升 / 整包 */
    public static final Map<Dimension, BigDecimal> BASE_STEP = new LinkedHashMap<>();

    static {
        BASE_STEP.put(MASS, new BigDecimal("1"));       // 最小投放 1 g
        BASE_STEP.put(VOLUME, new BigDecimal("1"));     // 最小投放 1 mL
        BASE_STEP.put(COUNT, new BigDecimal("1"));      // 最小投放 1 pkg
    }

    /** 维度是否存在（工具方法，便于模板与校验复用） */
    public static boolean isKnown(String v) {
        return nameOf(v) != null;
    }
}
