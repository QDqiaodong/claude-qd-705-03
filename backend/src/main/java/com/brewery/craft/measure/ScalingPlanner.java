package com.brewery.craft.measure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方缩放精度策略（单一事实源，接口/页面/迁移都引用这里，不允许各处各算一套）。
 *
 * <p>规则：
 * <ol>
 *   <li>在<b>基准单位</b>（g / mL / pkg）上按比例 factor = 目标批量 / 基准批量缩放；</li>
 *   <li>每条明细先落到该维度的取整网格（最小投放量 = 1 个基准单位）：向下取整；</li>
 *   <li>逐条取整产生的余差用<b>最大余数法（Largest Remainder）</b>按稳定顺序分配：
 *       余差为正 → 小数部分最大的行优先 +1 步长；余差为负 → 最接近上一格的行优先 -1 步长；
 *       使缩放后的规范化合计尽量与目标合计逐格一致；</li>
 *   <li>结果再换算回酿酒师习惯的显示单位（kg/L 保留 3 位，g/mL/pkg 按各自步长）；</li>
 *   <li>缩放始终以「当前规范化明细 × 绝对比例」计算，从不在已舍入结果上再叠乘，
 *       因此反复缩放、先放大再缩小不会持续累积误差（整数倍率严格可逆）。</li>
 * </ol>
 */
public final class ScalingPlanner {

    private ScalingPlanner() {
    }

    /** 缩放输入行（一条用量明细的规范化形态） */
    public record LineKey(int index, String stage, String ingredientCode) {
    }

    /** 每条明细的缩放结果（预览表格直接渲染） */
    public static class LineResult {
        public int index;
        public String stage;
        public String ingredientCode;
        public String ingredientName;
        public String ingredientType;
        public Dimension dimension;
        /** 原值（显示单位） */
        public BigDecimal originalQuantity;
        public String originalUnit;
        /** 原值换算到基准单位 */
        public BigDecimal originalBase;
        /** 精确比例换算后、尚未取整的基准单位值 */
        public BigDecimal exactBase;
        /** 向下取整后的基准单位值 */
        public BigDecimal flooredBase;
        /** 最终采用的基准单位值（已分配余差） */
        public BigDecimal adjustedBase;
        /** 余差去向：本行被分配到的基准单位增量（+step / -step / 0） */
        public BigDecimal remainderDelta;
        /** 最终采用值换算回显示单位 */
        public BigDecimal adjustedQuantity;
        /** 最小可投放量（基准单位） */
        public BigDecimal minBase;
        public String note;
    }

    /** 一个维度的缩放汇总 */
    public static class DimensionResult {
        public Dimension dimension;
        public String baseUnit;
        /** 缩放前规范化合计 */
        public BigDecimal originalTotal;
        /** 目标规范化合计 = 原合计 × factor */
        public BigDecimal targetTotal;
        /** 逐条缩放（未取整）合计 */
        public BigDecimal exactTotal;
        /** 最终采用的规范化合计 */
        public BigDecimal adjustedTotal;
        /** 仍未能分配出去的余差（受最小投放量约束时可能非 0，必须向用户明示） */
        public BigDecimal leftover;
        public List<LineResult> lines = new ArrayList<>();
    }

    /** 整张配方的缩放结果 */
    public static class Plan {
        public BigDecimal factor;
        public BigDecimal baseBatchL;
        public BigDecimal targetBatchL;
        public Map<Dimension, DimensionResult> dimensions = new LinkedHashMap<>();
        public List<LineResult> lines = new ArrayList<>();
    }

    /** 输入行（不可变值对象） */
    public record InputLine(int index, String stage, String ingredientCode, String ingredientName,
                            String ingredientType, Dimension dimension,
                            BigDecimal displayQuantity, String displayUnit, BigDecimal baseQuantity) {
    }

    /**
     * 生成缩放方案。
     *
     * @param inputs      全部可参与计算的明细（待处理明细不应传入）
     * @param factor      缩放比例
     * @param baseBatchL  配方基准批量（仅用于回显）
     * @param targetBatchL 目标批量（仅用于回显）
     */
    public static Plan plan(List<InputLine> inputs, BigDecimal factor, BigDecimal baseBatchL, BigDecimal targetBatchL) {
        Plan plan = new Plan();
        plan.factor = factor;
        plan.baseBatchL = baseBatchL;
        plan.targetBatchL = targetBatchL;

        Map<Dimension, List<InputLine>> byDim = new LinkedHashMap<>();
        for (Dimension d : List.of(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT)) {
            byDim.put(d, new ArrayList<>());
        }
        for (InputLine in : inputs) {
            byDim.computeIfAbsent(in.dimension(), k -> new ArrayList<>()).add(in);
        }

        for (Map.Entry<Dimension, List<InputLine>> e : byDim.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            plan.dimensions.put(e.getKey(), scaleDimension(e.getKey(), e.getValue(), factor, plan));
        }
        // 行顺序按输入顺序（= 阶段顺序），方便预览对照
        plan.lines.sort(Comparator.comparingInt(l -> l.index));
        return plan;
    }

    private static DimensionResult scaleDimension(Dimension dim, List<InputLine> rows, BigDecimal factor, Plan plan) {
        BigDecimal step = Dimension.BASE_STEP.get(dim);
        DimensionResult dr = new DimensionResult();
        dr.dimension = dim;
        dr.baseUnit = dim.getBaseUnit();
        dr.originalTotal = rows.stream().map(InputLine::baseQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        dr.targetTotal = dr.originalTotal.multiply(factor).setScale(6, RoundingMode.HALF_UP);

        List<LineResult> lines = new ArrayList<>();
        for (InputLine in : rows) {
            LineResult l = new LineResult();
            l.index = in.index();
            l.stage = in.stage();
            l.ingredientCode = in.ingredientCode();
            l.ingredientName = in.ingredientName();
            l.ingredientType = in.ingredientType();
            l.dimension = dim;
            l.originalQuantity = in.displayQuantity();
            l.originalUnit = in.displayUnit();
            l.originalBase = in.baseQuantity();
            l.exactBase = in.baseQuantity().multiply(factor).setScale(6, RoundingMode.HALF_UP);
            l.flooredBase = l.exactBase.divideToIntegralValue(step).multiply(step);
            // 最小可投放量 = 1 步长；缩放后只要原明细存在就至少要投 1 个最小单位
            l.minBase = step;
            if (l.flooredBase.compareTo(step) < 0 && l.exactBase.compareTo(BigDecimal.ZERO) > 0) {
                l.flooredBase = step;
            }
            l.adjustedBase = l.flooredBase;
            l.remainderDelta = BigDecimal.ZERO.setScale(0);
            lines.add(l);
        }

        // ---- 最大余数法分配余差，使规范化合计对齐目标网格 ----
        BigDecimal flooredSum = lines.stream().map(l -> l.adjustedBase).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 目标网格按四舍五入取到最接近的步长整数倍（Hamilton 最大余数法的标准席位总数），
        // 不能用向下取整——否则每次非整数回缩都会系统性丢掉 1 格，反复缩放会持续累积偏差。
        BigDecimal targetOnGrid = roundToGrid(dr.targetTotal, step);
        BigDecimal deltaSteps = targetOnGrid.subtract(flooredSum).divideToIntegralValue(step);

        if (deltaSteps.signum() > 0) {
            // 需要补格：按「自然小数部分」（exact 相对其数学地板的余量）从大到小；
            // 平局按原始用量大、再按行序号，保证分配顺序稳定、可解释、可复算
            List<LineResult> order = new ArrayList<>(lines);
            order.sort(Comparator
                    .comparing((LineResult l) -> naturalFraction(l, step)).reversed()
                    .thenComparing(Comparator.comparing((LineResult l) -> l.originalBase).reversed())
                    .thenComparing(l -> l.index));
            int given = 0;
            int need = deltaSteps.intValue();
            for (LineResult l : order) {
                if (given >= need) {
                    break;
                }
                l.adjustedBase = l.adjustedBase.add(step);
                l.remainderDelta = l.remainderDelta.add(step);
                given++;
            }
            if (given < need) {
                dr.leftover = BigDecimal.valueOf(need - given).multiply(step);
            }
        } else if (deltaSteps.signum() < 0) {
            // 需要回收：优先回收「被最小投放量抬升」的行，其次按（地板-精确）余量最大者；
            // 任何行都不能被压到最小投放量以下，回收不动的部分作为 leftover 明示
            List<LineResult> order = new ArrayList<>(lines);
            order.sort(Comparator
                    .comparing((LineResult l) -> lifted(l, step)).reversed()
                    .thenComparing(Comparator.comparing(
                            (LineResult l) -> l.flooredBase.subtract(l.exactBase)).reversed())
                    .thenComparing(l -> l.index));
            int need = -deltaSteps.intValue();
            int given = 0;
            for (LineResult l : order) {
                if (given >= need) {
                    break;
                }
                if (l.adjustedBase.compareTo(l.minBase.add(step)) >= 0) {
                    l.adjustedBase = l.adjustedBase.subtract(step);
                    l.remainderDelta = l.remainderDelta.subtract(step);
                    given++;
                }
            }
            if (given < need) {
                dr.leftover = BigDecimal.valueOf(need - given).multiply(step);
            }
        }

        dr.exactTotal = lines.stream().map(l -> l.exactBase).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(6, RoundingMode.HALF_UP);
        dr.adjustedTotal = lines.stream().map(l -> l.adjustedBase).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dr.leftover == null) {
            dr.leftover = dr.adjustedTotal.subtract(targetOnGrid);
        }

        // ---- 换算回显示单位 + 余差去向说明 ----
        for (LineResult l : lines) {
            UnitDef ud = UnitDef.byCode(l.originalUnit);
            int displayScale = ud == null ? 3 : ud.getDisplayScale();
            BigDecimal display = ud == null ? l.adjustedBase : ud.fromBase(l.adjustedBase);
            l.adjustedQuantity = cleanScale(display, displayScale);
            int cmp = l.remainderDelta.compareTo(BigDecimal.ZERO);
            if (cmp > 0) {
                l.note = "分配余差 +" + l.remainderDelta.stripTrailingZeros().toPlainString() + " "
                        + dim.getBaseUnit() + "（最大余数法）";
            } else if (cmp < 0) {
                l.note = "回收余差 " + l.remainderDelta.stripTrailingZeros().toPlainString() + " "
                        + dim.getBaseUnit() + "（最大余数法）";
            }
            dr.lines.add(l);
            plan.lines.add(l);
        }
        return dr;
    }

    /** 取到最接近的步长整数倍（HALF_UP），如 step=1g 时 1501.5 → 1502、1501.4 → 1501 */
    private static BigDecimal roundToGrid(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    /** 该行从精确值到自然地板的小数部分（未受最小投放量抬升影响）——最大余数法的「余数」 */
    private static BigDecimal naturalFraction(LineResult l, BigDecimal step) {
        BigDecimal naturalFloor = l.exactBase.divideToIntegralValue(step).multiply(step);
        return l.exactBase.subtract(naturalFloor);
    }

    /** 该行从精确值到地板值被抬升了多少（最小投放量抬升计入） */
    private static BigDecimal lifted(LineResult l, BigDecimal step) {
        BigDecimal naturalFloor = l.exactBase.divideToIntegralValue(step).multiply(step);
        return l.adjustedBase.subtract(naturalFloor);
    }

    /** 按显示精度落位：末尾多余的 0 去掉，0 值固定为 0（避免 stripTrailingZeros 出现负标度/科学计数） */
    public static BigDecimal cleanScale(BigDecimal value, int displayScale) {
        BigDecimal v = value.setScale(displayScale, RoundingMode.HALF_UP);
        if (v.signum() == 0) {
            return BigDecimal.ZERO.setScale(Math.min(displayScale, 1));
        }
        String plain = v.stripTrailingZeros().toPlainString();
        return new BigDecimal(plain);
    }
}
