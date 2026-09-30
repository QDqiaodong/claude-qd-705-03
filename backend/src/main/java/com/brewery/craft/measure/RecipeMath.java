package com.brewery.craft.measure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规范化聚合（占比 / 阶段小计 / 维度合计）的<b>唯一实现</b>。
 *
 * <p>列表明细、配方卡环形占比、批次详情快照三处必须共用这里的结果，
 * 不允许各自再算一套。规则：
 * <ol>
 *   <li>只用 {@link Row#baseQuantity()}（g/mL/pkg）参与汇总，绝不拿显示数量直接相加；</li>
 *   <li>不同维度分开汇总，质量与体积不相加；</li>
 *   <li>原料类型占比 = 该类型在<b>同一维度内</b>的规范化合计 ÷ 该维度规范化合计；</li>
 *   <li>{@code NEEDS_REVIEW} 行不参与分母与分子，单独计数由调用方提示「待处理」。</li>
 * </ol>
 */
public final class RecipeMath {

    public static final int PERCENT_SCALE = 1;

    private RecipeMath() {
    }

    /** 聚合输入行：既适用于当前配方明细，也适用于批次快照（不可变历史） */
    public interface Row {
        String stage();

        String ingredientType();

        String dimension();

        String unitStatus();

        BigDecimal baseQuantity();
    }

    /** 单个原料类型在某一维度内的占比片 */
    public static class Slice {
        public String dimension;
        public String baseUnit;
        public String ingredientType;
        public String ingredientTypeName;
        public String color;
        public BigDecimal amount;
        public BigDecimal percent;
    }

    /** 阶段小计（按维度分列） */
    public static class StageTotal {
        public String stage;
        public String stageName;
        public int lineCount;
        public Map<String, Cell> byDimension = new LinkedHashMap<>();
    }

    /** 一个维度上的一个合计单元格 */
    public static class Cell {
        public String dimension;
        public String dimensionName;
        public String baseUnit;
        public BigDecimal amount;
    }

    /** 整张配方（或一份快照）的规范化聚合结果 */
    public static class Aggregate {
        /** 维度 → 该维度规范化合计（基准单位） */
        public Map<String, Cell> dimensionTotals = new LinkedHashMap<>();
        /** 维度 → 该维度内的类型占比片 */
        public Map<String, List<Slice>> slicesByDimension = new LinkedHashMap<>();
        /** 阶段小计，按阶段顺序 */
        public List<StageTotal> stageTotals = new ArrayList<>();
        /** 可参与计算的明细行数 */
        public int usableLines;
        /** 无法可靠推导、未参与计算的明细行数 */
        public int reviewLines;
    }

    public static Aggregate aggregate(List<? extends Row> rows, List<String> stageOrder) {
        Aggregate agg = new Aggregate();
        for (Dimension d : List.of(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT)) {
            agg.dimensionTotals.put(d.name(), cell(d, BigDecimal.ZERO));
            agg.slicesByDimension.put(d.name(), new ArrayList<>());
        }

        // 维度 → 类型 → 合计；阶段 → 维度 → 合计
        Map<String, Map<String, BigDecimal>> byDimType = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> byStageDim = new LinkedHashMap<>();
        Map<String, Integer> stageCount = new LinkedHashMap<>();

        for (Row r : rows) {
            Dimension d = Dimension.nameOf(r.dimension());
            if (d == null || r.baseQuantity() == null
                    || ResolvedQuantity.NEEDS_REVIEW.equals(r.unitStatus())) {
                agg.reviewLines++;
                continue;
            }
            agg.usableLines++;
            byDimType.computeIfAbsent(d.name(), k -> new LinkedHashMap<>())
                    .merge(r.ingredientType() == null ? "UNKNOWN" : r.ingredientType(),
                            r.baseQuantity(), BigDecimal::add);
            byStageDim.computeIfAbsent(r.stage(), k -> new LinkedHashMap<>())
                    .merge(d.name(), r.baseQuantity(), BigDecimal::add);
            stageCount.merge(r.stage(), 1, Integer::sum);
        }

        // 维度合计 + 维度内类型占比（同一维度内先换算后再相除，绝不跨维度）
        for (Dimension d : List.of(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT)) {
            Map<String, BigDecimal> typeMap = byDimType.getOrDefault(d.name(), Map.of());
            BigDecimal total = typeMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            agg.dimensionTotals.get(d.name()).amount = total;
            List<Slice> slices = agg.slicesByDimension.get(d.name());
            for (String type : typeOrder()) {
                BigDecimal amount = typeMap.get(type);
                if (amount == null || amount.signum() <= 0) {
                    continue;
                }
                Slice s = new Slice();
                s.dimension = d.name();
                s.baseUnit = d.getBaseUnit();
                s.ingredientType = type;
                s.ingredientTypeName = ingredientTypeName(type);
                s.color = ingredientColor(type);
                s.amount = amount;
                s.percent = total.signum() == 0 ? BigDecimal.ZERO
                        : amount.multiply(BigDecimal.valueOf(100))
                                .divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
                slices.add(s);
            }
        }

        // 阶段小计，阶段顺序固定
        List<String> stages = new ArrayList<>(stageOrder);
        for (String st : byStageDim.keySet()) {
            if (!stages.contains(st)) {
                stages.add(st);
            }
        }
        for (String st : stages) {
            Map<String, BigDecimal> dimMap = byStageDim.get(st);
            if (dimMap == null) {
                continue;
            }
            StageTotal st0 = new StageTotal();
            st0.stage = st;
            st0.stageName = stageName(st);
            st0.lineCount = stageCount.getOrDefault(st, 0);
            for (Dimension d : List.of(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT)) {
                BigDecimal amt = dimMap.get(d.name());
                if (amt != null) {
                    st0.byDimension.put(d.name(), cell(d, amt));
                }
            }
            agg.stageTotals.add(st0);
        }
        return agg;
    }

    private static Cell cell(Dimension d, BigDecimal amount) {
        Cell c = new Cell();
        c.dimension = d.name();
        c.dimensionName = d.getDisplayName();
        c.baseUnit = d.getBaseUnit();
        c.amount = amount;
        return c;
    }

    /**
     * 主维度：固定优先级 质量 → 体积 → 计数，取第一个有用量的维度。
     * 啤酒配方以固形物投料为主，环形图稳定画质量构成；全液体配方才退到体积。
     */
    public static Dimension primaryDimension(Aggregate agg) {
        for (Dimension d : List.of(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT)) {
            Cell c = agg.dimensionTotals.get(d.name());
            if (c != null && c.amount.signum() > 0) {
                return d;
            }
        }
        return Dimension.MASS;
    }

    public static List<String> typeOrder() {
        return List.of("MALT", "HOP", "YEAST", "ADJUNCT", "WATER");
    }

    private static String ingredientTypeName(String v) {
        return Map.of(
                "MALT", "麦芽", "HOP", "啤酒花", "YEAST", "酵母",
                "ADJUNCT", "辅料", "WATER", "水处理剂").getOrDefault(v, v == null ? "" : v);
    }

    private static String ingredientColor(String v) {
        return Map.of(
                "MALT", "#8d6e3f", "HOP", "#7cb342", "YEAST", "#c9a227",
                "ADJUNCT", "#26a69a", "WATER", "#5c9dd6").getOrDefault(v, "#9aa5ab");
    }

    private static String stageName(String v) {
        return Map.ofEntries(
                Map.entry("WATER", "水处理"),
                Map.entry("MASH_IN", "糖化投料"),
                Map.entry("MASH_OUT", "糖化终止"),
                Map.entry("SPARGE", "洗糟"),
                Map.entry("BOIL_60", "煮沸60分钟"),
                Map.entry("BOIL_30", "煮沸30分钟"),
                Map.entry("BOIL_5", "煮沸5分钟"),
                Map.entry("WHIRLPOOL", "回旋沉淀"),
                Map.entry("PITCH", "接种酵母"),
                Map.entry("FRUIT", "加果泥"),
                Map.entry("DRYHOP_1", "干投一次"),
                Map.entry("DRYHOP_2", "干投二次"))
                .getOrDefault(v, v == null ? "" : v);
    }
}
