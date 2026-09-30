package com.brewery.craft.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 单位解析：历史写法归一、空单位按档案/类别推导、无法识别保留并待处理。
 */
class ResolvedQuantityTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void canonicalUnitsAreOk() {
        ResolvedQuantity r = ResolvedQuantity.resolve("MALT", bd("10"), "kg", "kg");
        assertEquals(ResolvedQuantity.OK, r.status());
        assertEquals(Dimension.MASS, r.dimension());
        assertEquals(0, new BigDecimal("10000").compareTo(r.baseQuantity()));
    }

    @Test
    void oldCaseAndAliasesAreNormalizedWithinSameDimension() {
        assertEquals(ResolvedQuantity.NORMALIZED,
                ResolvedQuantity.resolve("MALT", bd("1"), "KG", "kg").status());
        assertEquals(ResolvedQuantity.NORMALIZED,
                ResolvedQuantity.resolve("MALT", bd("500"), "克", "kg").status());
        ResolvedQuantity ltr = ResolvedQuantity.resolve("WATER", bd("1.2"), "ltr", "L");
        assertEquals(ResolvedQuantity.NORMALIZED, ltr.status());
        assertEquals(Dimension.VOLUME, ltr.dimension());
        assertEquals(0, new BigDecimal("1200").compareTo(ltr.baseQuantity()));
    }

    @Test
    void gramsAndKilogramsConvertBeforeAggregation() {
        // 1 kg + 500 g = 1500 g（同维度先换算）
        ResolvedQuantity kg = ResolvedQuantity.resolve("MALT", bd("1"), "kg", "kg");
        ResolvedQuantity g = ResolvedQuantity.resolve("MALT", bd("500"), "g", "kg");
        assertEquals(0, new BigDecimal("1500").compareTo(kg.baseQuantity().add(g.baseQuantity())));
    }

    @Test
    void litersAndMillilitersAreVolumeNotMass() {
        ResolvedQuantity l = ResolvedQuantity.resolve("WATER", bd("1"), "L", "L");
        ResolvedQuantity ml = ResolvedQuantity.resolve("WATER", bd("500"), "mL", "L");
        assertEquals(Dimension.VOLUME, l.dimension());
        assertEquals(Dimension.VOLUME, ml.dimension());
        assertEquals(0, new BigDecimal("1500").compareTo(l.baseQuantity().add(ml.baseQuantity())));
    }

    @Test
    void emptyUnitInferredFromMasterThenCategory() {
        ResolvedQuantity byMaster = ResolvedQuantity.resolve("YEAST", bd("2"), null, "pkg");
        assertEquals(ResolvedQuantity.INFERRED, byMaster.status());
        assertEquals(Dimension.COUNT, byMaster.dimension());
        assertEquals("pkg", byMaster.displayUnit());

        ResolvedQuantity byCategory = ResolvedQuantity.resolve("MALT", bd("10"), "  ", null);
        assertEquals(ResolvedQuantity.INFERRED, byCategory.status());
        assertEquals(Dimension.MASS, byCategory.dimension());
        assertEquals("kg", byCategory.displayUnit());
    }

    @Test
    void unknownUnitIsKeptAndFlaggedForReview() {
        ResolvedQuantity r = ResolvedQuantity.resolve("HOP", bd("0.3"), "罐", "kg");
        assertEquals(ResolvedQuantity.NEEDS_REVIEW, r.status());
        assertNull(r.dimension());
        assertNull(r.baseQuantity());
        // 原始值与原始写法保留
        assertEquals("罐", r.displayUnit());
        assertEquals(0, bd("0.3").compareTo(r.displayQuantity()));
        assertTrue(r.needsReview());
        assertFalse(r.usable());
    }

    @Test
    void emptyUnitUnknownCategoryAlsoFlagged() {
        ResolvedQuantity r = ResolvedQuantity.resolve("UNKNOWN_TYPE", bd("1"), "", null);
        assertEquals(ResolvedQuantity.NEEDS_REVIEW, r.status());
        assertNull(r.baseQuantity());
    }

    @Test
    void dimensionMismatchWithMasterIsWarnedButStillUsable() {
        // 档案按 kg（质量）的原料被填成 L（体积）：可解析、仍按体积参与计算，但必须明确告警，不静默
        ResolvedQuantity r = ResolvedQuantity.resolve("WATER", bd("2"), "L", "kg");
        assertEquals(ResolvedQuantity.DIM_MISMATCH, r.status());
        assertEquals(Dimension.VOLUME, r.dimension());
        assertEquals(0, new BigDecimal("2000").compareTo(r.baseQuantity()));
        assertTrue(r.usable());
        assertTrue(r.resolutionNote().contains("计量维度"));
    }
}
