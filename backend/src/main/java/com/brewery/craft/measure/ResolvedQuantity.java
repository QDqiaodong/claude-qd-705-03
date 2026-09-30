package com.brewery.craft.measure;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单条用量的单位解析与规范化结果。
 *
 * @param dimension        计量维度；无法可靠推导时为 null（待处理记录不参与任何汇总）
 * @param displayUnit      页面保留的显示单位：能归一即写规范码（kg/g/L/mL/pkg），否则保留原始写法
 * @param displayQuantity  显示数量（酿酒师录入的原始数量，不改动）
 * @param baseQuantity     换算到维度基准单位（g/mL/pkg）的规范化数量；待处理时为 null
 * @param status           OK / NORMALIZED / INFERRED / NEEDS_REVIEW
 * @param resolutionNote   可解释的推导说明（给页面与审计看）
 */
public record ResolvedQuantity(Dimension dimension,
                               String displayUnit,
                               BigDecimal displayQuantity,
                               BigDecimal baseQuantity,
                               String status,
                               String resolutionNote) {

    /** 单位写法直接就是规范码 */
    public static final String OK = "OK";
    /** 旧写法/大小写不一致，已归一到规范码 */
    public static final String NORMALIZED = "NORMALIZED";
    /** 单位为空，按原料档案/类别推导出单位 */
    public static final String INFERRED = "INFERRED";
    /** 单位可解析，但与原料档案计量维度不一致：保留参与计算并明确告警，不静默处理 */
    public static final String DIM_MISMATCH = "DIM_MISMATCH";
    /** 无法可靠推导：保留原值，标记待处理，不参与规范化汇总 */
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    public boolean usable() {
        return dimension != null && baseQuantity != null;
    }

    public boolean needsReview() {
        return NEEDS_REVIEW.equals(status);
    }

    /**
     * 解析一条用量。
     *
     * @param ingredientType 原料类别 MALT/HOP/YEAST/ADJUNCT/WATER（可空）
     * @param quantity       原始数量（不为空且大于 0）
     * @param rawUnit        明细上的原始单位（可能为空、大小写不一致、未知写法）
     * @param masterUnit     原料库存档案上的单位（空单位时优先按它推导；可空）
     */
    public static ResolvedQuantity resolve(String ingredientType,
                                           BigDecimal quantity,
                                           String rawUnit,
                                           String masterUnit) {
        boolean rawBlank = rawUnit == null || rawUnit.isBlank();

        // 1) 明细本身写了能识别的单位（含历史别名）——以明细为准，绝不猜成别的维度
        UnitDef def = UnitDef.parse(rawUnit);
        if (def != null) {
            boolean codeMatched = def.getCode().equals(rawUnit.trim());
            String status = codeMatched ? OK : NORMALIZED;
            String note = codeMatched ? null
                    : "旧单位写法「" + rawUnit.trim() + "」已归一为「" + def.getCode() + "」";
            // 维度与原料档案不一致：不擅自改单位，但明确告警（可能填错了质量/体积）
            UnitDef masterDef = UnitDef.parse(masterUnit);
            if (masterDef != null && masterDef.getDimension() != def.getDimension()) {
                String warn = "单位「" + def.getCode() + "」（" + def.getDimension().getDisplayName()
                        + "）与原料档案单位「" + masterDef.getCode() + "」（"
                        + masterDef.getDimension().getDisplayName() + "）不属于同一计量维度，请确认是否填错";
                note = note == null ? warn : note + "；" + warn;
                return new ResolvedQuantity(def.getDimension(), def.getCode(), quantity,
                        def.toBase(quantity), DIM_MISMATCH, note);
            }
            return new ResolvedQuantity(def.getDimension(), def.getCode(), quantity,
                    def.toBase(quantity), status, note);
        }

        // 2) 空单位：优先按原料档案单位推导（档案是该原料的既有计量事实）
        if (rawBlank) {
            UnitDef master = UnitDef.parse(masterUnit);
            if (master != null) {
                return new ResolvedQuantity(master.getDimension(), master.getCode(), quantity,
                        master.toBase(quantity), INFERRED,
                        "单位为空，按原料档案单位「" + master.getCode() + "」计入");
            }
            // 档案也没有可用单位：按原料类别给行业惯例默认（推导结果明确标记，可事后人工修正）
            UnitDef byType = categoryDefault(ingredientType);
            if (byType != null) {
                return new ResolvedQuantity(byType.getDimension(), byType.getCode(), quantity,
                        byType.toBase(quantity), INFERRED,
                        "单位为空且档案无单位，按原料类别「" + ingredientType + "」惯例单位「"
                                + byType.getCode() + "」计入，请核对");
            }
        }

        // 3) 写了无法识别的单位（如 斤、桶…），无法可靠推导：保留原值，标记待处理，不参与计算
        String keptUnit = rawBlank ? "" : rawUnit.trim();
        String reason = rawBlank
                ? "单位为空，且原料类别/档案都无法推导出可靠单位"
                : "无法识别的单位「" + keptUnit + "」，未做猜测";
        return new ResolvedQuantity(null, keptUnit, quantity, null, NEEDS_REVIEW, reason);
    }

    /** 原料类别 → 行业惯例单位（仅在明细与档案都拿不到单位时兜底推导） */
    public static UnitDef categoryDefault(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toUpperCase()) {
            case "MALT", "HOP", "ADJUNCT", "WATER" -> UnitDef.byCode("kg");
            case "YEAST" -> UnitDef.byCode("pkg");
            default -> null;
        };
    }

    /** 按类别给出建议单位码（前端选原料时联动默认显示单位） */
    public static String suggestUnit(String ingredientType, String masterUnit) {
        UnitDef master = UnitDef.parse(masterUnit);
        if (master != null) {
            return master.getCode();
        }
        UnitDef byType = categoryDefault(ingredientType);
        return byType == null ? "kg" : byType.getCode();
    }
}
