package com.brewery.craft.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 缩放精度：按最小投放量取整、最大余数法分配余差、合计对齐目标、反复缩放不累积误差。
 */
class ScalingPlannerTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ScalingPlanner.InputLine line(int idx, String stage, String code, Dimension dim,
                                          String display, String unit, String base) {
        return new ScalingPlanner.InputLine(idx, stage, code, code + "-name",
                code.startsWith("MALT") || code.startsWith("HOP") ? "MALT" : "WATER",
                dim, bd(display), unit, bd(base));
    }

    private List<ScalingPlanner.InputLine> massOnly() {
        List<ScalingPlanner.InputLine> list = new ArrayList<>();
        // 合计 1500 g，显示单位混 kg / g
        list.add(line(0, "MASH_IN", "MALT-PIL", Dimension.MASS, "1", "kg", "1000"));
        list.add(line(1, "BOIL_60", "HOP-CAS", Dimension.MASS, "500", "g", "500"));
        return list;
    }

    @Test
    void factorTwoIsExactOnGridAndLineItemsKeepDisplayUnits() {
        ScalingPlanner.Plan plan = ScalingPlanner.plan(massOnly(), bd("2"), bd("1000"), bd("2000"));
        ScalingPlanner.DimensionResult mass = plan.dimensions.get(Dimension.MASS);
        assertEquals(0, bd("3000").compareTo(mass.adjustedTotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(mass.leftover));

        ScalingPlanner.LineResult malt = plan.lines.get(0);
        ScalingPlanner.LineResult hop = plan.lines.get(1);
        // 显示单位保持酿酒师习惯：kg 仍是 kg，g 仍是 g
        assertEquals("kg", malt.originalUnit);
        assertEquals("g", hop.originalUnit);
        assertEquals(0, bd("2").compareTo(malt.adjustedQuantity));
        assertEquals(0, bd("1000").compareTo(hop.adjustedQuantity));
    }

    @Test
    void fractionalFactorDistributesRemainderByLargestRemainder() {
        // ×1.0007：精确值 1000.7g / 500.35g，地板 1000 / 500（合计 1500）；
        // 目标 1501.05g 落网格 1501g，缺 1 格；小数部分 0.70 的麦芽行（>0.35）优先得到 +1
        ScalingPlanner.Plan plan = ScalingPlanner.plan(massOnly(), bd("1.0007"), bd("1000"), bd("1000.7"));
        ScalingPlanner.DimensionResult mass = plan.dimensions.get(Dimension.MASS);
        assertEquals(0, bd("1501").compareTo(mass.adjustedTotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(mass.leftover));
        ScalingPlanner.LineResult malt = plan.lines.get(0);
        ScalingPlanner.LineResult hop = plan.lines.get(1);
        assertEquals(1, malt.remainderDelta.intValue());
        assertEquals(0, hop.remainderDelta.intValue());
        assertEquals(0, bd("1001").compareTo(malt.adjustedBase));
        assertEquals(0, bd("500").compareTo(hop.adjustedBase));
        // 未取整的精确合计保留，便于页面解释余差去向
        assertEquals(0, bd("1501.05").compareTo(mass.exactTotal));
    }

    @Test
    void minimumPitchingQuantityIsOneBaseUnit() {
        // 极小缩放：精确值 0.4g / 0.2g，两行都抬到最小 1g，合计 2g；目标网格 0g 无法回收（低于最小值）→ leftover 明示
        ScalingPlanner.Plan plan = ScalingPlanner.plan(massOnly(), bd("0.0004"), bd("1000"), bd("0.4"));
        ScalingPlanner.DimensionResult mass = plan.dimensions.get(Dimension.MASS);
        assertTrue(mass.adjustedTotal.compareTo(bd("2")) >= 0);
        for (ScalingPlanner.LineResult l : plan.lines) {
            assertTrue(l.adjustedBase.compareTo(l.minBase) >= 0);
        }
        assertNotNull(mass.leftover);
        // leftover 必须显式提示未对齐，而不是静默吞掉
        assertTrue(mass.leftover.signum() > 0);
    }

    @Test
    void repeatedScalingDoesNotAccumulateErrorRoundTrip() {
        // 1000L → 1500L → 1000L：整数网格上严格回到原值（每次都以原始规范化量 × 绝对比例计算）
        BigDecimal up = bd("1.5").setScale(6, java.math.RoundingMode.HALF_UP);
        ScalingPlanner.Plan bigger = ScalingPlanner.plan(massOnly(), up, bd("1000"), bd("1500"));
        List<ScalingPlanner.InputLine> after = new ArrayList<>();
        int i = 0;
        for (ScalingPlanner.LineResult l : bigger.lines) {
            after.add(new ScalingPlanner.InputLine(i++, l.stage, l.ingredientCode, l.ingredientName,
                    l.ingredientType, l.dimension, l.adjustedQuantity, l.originalUnit, l.adjustedBase));
        }
        ScalingPlanner.Plan back = ScalingPlanner.plan(after,
                bd("1000").divide(bd("1500"), 6, java.math.RoundingMode.HALF_UP),
                bd("1500"), bd("1000"));
        // 关键：第二次缩放以 1500L 配方为基准，比例 1000/1500，结果应严格还原 1000g/500g
        ScalingPlanner.LineResult malt = back.lines.get(0);
        ScalingPlanner.LineResult hop = back.lines.get(1);
        assertEquals(0, bd("1000").compareTo(malt.adjustedBase),
                "先放大再缩小不应持续累积误差");
        assertEquals(0, bd("500").compareTo(hop.adjustedBase));
        assertEquals(0, bd("1500").compareTo(back.dimensions.get(Dimension.MASS).adjustedTotal));
    }

    @Test
    void manyRepeatedUpsAndDownsStayBounded() {
        // 反复 1000→1200→1000 十轮（非整数倍率 0.833333…）：逐次舍入误差必须有界、不发散。
        // 每轮只在 1g 网格上做最大余数分配，十轮后单行偏差不应超过 1g、总量偏差不超过明细数（2g）。
        List<ScalingPlanner.InputLine> current = massOnly();
        BigDecimal base = bd("1000");
        BigDecimal malt0 = current.get(0).baseQuantity();
        BigDecimal hop0 = current.get(1).baseQuantity();
        for (int round = 0; round < 10; round++) {
            ScalingPlanner.Plan up = ScalingPlanner.plan(current,
                    bd("1200").divide(base, 6, java.math.RoundingMode.HALF_UP), base, bd("1200"));
            current = reline(up);
            base = bd("1200");
            ScalingPlanner.Plan down = ScalingPlanner.plan(current,
                    bd("1000").divide(base, 6, java.math.RoundingMode.HALF_UP), base, bd("1000"));
            current = reline(down);
            base = bd("1000");
        }
        BigDecimal malt = current.get(0).baseQuantity();
        BigDecimal hop = current.get(1).baseQuantity();
        // 目标网格四舍五入 + 最大余数法：十轮非整数倍率往返后严格还原，不持续累积误差
        assertEquals(0, malt0.compareTo(malt), "麦芽反复缩放后应还原，实际 " + malt);
        assertEquals(0, hop0.compareTo(hop), "酒花反复缩放后应还原，实际 " + hop);
        BigDecimal totalDiff = malt.add(hop).subtract(malt0.add(hop0));
        assertEquals(0, BigDecimal.ZERO.compareTo(totalDiff), "总量偏差应为 0，实际 " + totalDiff.toPlainString());
    }

    private List<ScalingPlanner.InputLine> reline(ScalingPlanner.Plan plan) {
        List<ScalingPlanner.InputLine> out = new ArrayList<>();
        int i = 0;
        for (ScalingPlanner.LineResult l : plan.lines) {
            out.add(new ScalingPlanner.InputLine(i++, l.stage, l.ingredientCode, l.ingredientName,
                    l.ingredientType, l.dimension, l.adjustedQuantity, l.originalUnit, l.adjustedBase));
        }
        return out;
    }
}
