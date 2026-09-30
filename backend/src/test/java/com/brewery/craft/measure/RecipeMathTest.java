package com.brewery.craft.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * 聚合规则：同维度先换算再汇总；质量与体积不相加；占比只在同维度内成立；待处理行不参与。
 */
class RecipeMathTest {

    private record T(String stage, String type, String dim, String status, BigDecimal base)
            implements RecipeMath.Row {
        @Override public String stage() { return stage; }
        @Override public String ingredientType() { return type; }
        @Override public String dimension() { return dim; }
        @Override public String unitStatus() { return status; }
        @Override public BigDecimal baseQuantity() { return base; }
    }

    private static T mass(String stage, String type, String grams, String status) {
        return new T(stage, type, "MASS", status, new BigDecimal(grams));
    }

    private static T vol(String stage, String type, String ml) {
        return new T(stage, type, "VOLUME", "OK", new BigDecimal(ml));
    }

    private static T review(String stage, String type) {
        return new T(stage, type, null, ResolvedQuantity.NEEDS_REVIEW, null);
    }

    @Test
    void totalsPerDimensionNeverMixMassAndVolume() {
        // 麦芽 1000g（=1kg）、酒花 500g；水 1500mL —— 质量合计 1500g，体积合计 1500mL，二者不相加
        RecipeMath.Aggregate agg = RecipeMath.aggregate(List.of(
                mass("MASH_IN", "MALT", "1000", "OK"),
                mass("BOIL_60", "HOP", "500", "OK"),
                vol("WATER", "WATER", "1500")),
                List.of("WATER", "MASH_IN", "BOIL_60"));

        assertEquals(0, new BigDecimal("1500").compareTo(agg.dimensionTotals.get("MASS").amount));
        assertEquals(0, new BigDecimal("1500").compareTo(agg.dimensionTotals.get("VOLUME").amount));
        assertEquals(0, BigDecimal.ZERO.compareTo(agg.dimensionTotals.get("COUNT").amount));
        assertEquals(0, agg.reviewLines);
        assertEquals(3, agg.usableLines);
    }

    @Test
    void compositionPercentIsWithinDimension() {
        RecipeMath.Aggregate agg = RecipeMath.aggregate(List.of(
                mass("MASH_IN", "MALT", "900", "OK"),
                mass("BOIL_60", "HOP", "100", "OK"),
                vol("WATER", "WATER", "2000")),
                List.of("WATER", "MASH_IN", "BOIL_60"));

        Map<String, BigDecimal> massPct = agg.slicesByDimension.get("MASS").stream()
                .collect(Collectors.toMap(s -> s.ingredientType, s -> s.percent));
        assertEquals(0, new BigDecimal("90.0").compareTo(massPct.get("MALT")));
        assertEquals(0, new BigDecimal("10.0").compareTo(massPct.get("HOP")));

        // 体积维度单独算：水处理剂占 100%，不会被质量总量稀释
        RecipeMath.Slice water = agg.slicesByDimension.get("VOLUME").get(0);
        assertEquals("WATER", water.ingredientType);
        assertEquals(0, new BigDecimal("100.0").compareTo(water.percent));
    }

    @Test
    void stageSubtotalsAreSplitByDimension() {
        RecipeMath.Aggregate agg = RecipeMath.aggregate(List.of(
                mass("MASH_IN", "MALT", "900", "OK"),
                mass("MASH_IN", "MALT", "100", "OK"),
                vol("WATER", "WATER", "500"),
                review("DRYHOP_1", "HOP")),
                List.of("WATER", "MASH_IN", "DRYHOP_1"));

        RecipeMath.StageTotal mash = agg.stageTotals.stream()
                .filter(s -> "MASH_IN".equals(s.stage)).findFirst().orElseThrow();
        assertEquals(2, mash.lineCount);
        assertEquals(0, new BigDecimal("1000").compareTo(mash.byDimension.get("MASS").amount));
        assertNull(mash.byDimension.get("VOLUME"));
        // 待处理阶段不计入小计
        assertTrue(agg.stageTotals.stream().noneMatch(s -> "DRYHOP_1".equals(s.stage)));
        assertEquals(1, agg.reviewLines);
    }

    @Test
    void primaryDimensionPrefersMassWhenPresent() {
        RecipeMath.Aggregate agg = RecipeMath.aggregate(List.of(
                mass("MASH_IN", "MALT", "10", "OK"),
                vol("WATER", "WATER", "99999")),
                List.of("WATER", "MASH_IN"));
        // 质量优先的固定主维度（环形图展示稳定），即使体积数量值更大
        assertEquals(Dimension.MASS, RecipeMath.primaryDimension(agg));
    }
}
