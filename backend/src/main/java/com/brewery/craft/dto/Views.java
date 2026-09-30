package com.brewery.craft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Bottling;
import com.brewery.craft.domain.Fermenter;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.domain.QcAction;
import com.brewery.craft.domain.QcReading;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.RecipeItem;
import com.brewery.craft.domain.Shipment;

import lombok.Data;

/**
 * 出参视图 + 酿酒业务词典。
 *
 * 说明：common 包里的 Dict 由骨架Generator生成，本仓的业务字典集中在这里，
 * 把一个 {@code Fermenter} / {@code Batch} 之类的实体翻译成前端直接可渲染的 VO。
 */
public final class Views {

    private Views() {
    }

    // ---------------- 罐体材质 ----------------
    private static final Map<String, String> MATERIAL = Map.of(
            "STAINLESS", "不锈钢",
            "JACKETED", "夹套",
            "CONICAL", "锥底");

    // ---------------- 冷却方式 ----------------
    private static final Map<String, String> COOLING = Map.of(
            "GLYCOL", "乙二醇夹套",
            "ICE_WATER", "冰水盘管",
            "AIR", "强制风冷");

    // ---------------- 罐体状态（= 当前所处酿造阶段） ----------------
    private static final Map<String, String> TANK_STATUS = Map.of(
            "IDLE", "空闲",
            "MASHING", "糖化",
            "BOILING", "煮沸",
            "FERMENTING", "发酵",
            "CONDITIONING", "熟成",
            "CLEANING", "清洗",
            "DISABLED", "停用");

    /** 阶段在页面上从左到右的排列顺序 */
    public static final List<String> TANK_STATUS_ORDER = List.of(
            "MASHING", "BOILING", "FERMENTING", "CONDITIONING", "CLEANING", "IDLE", "DISABLED");

    /** 阶段对应液位配色 */
    private static final Map<String, String> TANK_STATUS_COLOR = Map.of(
            "IDLE", "#c8d3c0",
            "MASHING", "#f0d58a",
            "BOILING", "#ef9b52",
            "FERMENTING", "#8bc34a",
            "CONDITIONING", "#d99a2b",
            "CLEANING", "#9aa5ab",
            "DISABLED", "#7a8288");

    // ---------------- 啤酒风格 ----------------
    private static final Map<String, String> STYLE = Map.of(
            "IPA", "IPA",
            "STOUT", "世涛",
            "PILSNER", "皮尔森",
            "WHEAT", "小麦",
            "SOUR", "酸啤",
            "PALE_ALE", "淡色艾尔");

    // ---------------- 批次状态流转 ----------------
    private static final Map<String, String> BATCH_STATUS = Map.of(
            "PLAN", "计划",
            "MASH", "糖化",
            "BOIL", "煮沸",
            "FERMENT", "发酵",
            "CONDITION", "熟成",
            "PACKAGED", "已灌装",
            "SCRAPPED", "报废");

    /** 批次推进链路：key 的下一站是 value */
    private static final Map<String, String> BATCH_NEXT = Map.of(
            "PLAN", "MASH",
            "MASH", "BOIL",
            "BOIL", "FERMENT",
            "FERMENT", "CONDITION",
            "CONDITION", "PACKAGED");

    /** 批次状态 → 罐体状态（批次推进时同步刷罐） */
    private static final Map<String, String> BATCH_TO_TANK = Map.of(
            "PLAN", "IDLE",
            "MASH", "MASHING",
            "BOIL", "BOILING",
            "FERMENT", "FERMENTING",
            "CONDITION", "CONDITIONING",
            "PACKAGED", "CLEANING",
            "SCRAPPED", "CLEANING");

    // ---------------- 原料类型 ----------------
    private static final Map<String, String> INGREDIENT_TYPE = Map.of(
            "MALT", "麦芽",
            "HOP", "啤酒花",
            "YEAST", "酵母",
            "ADJUNCT", "辅料",
            "WATER", "水处理剂");

    /** 原料类型配色（配方卡 conic-gradient 环形图用） */
    private static final Map<String, String> INGREDIENT_COLOR = Map.of(
            "MALT", "#8d6e3f",
            "HOP", "#7cb342",
            "YEAST", "#c9a227",
            "ADJUNCT", "#26a69a",
            "WATER", "#5c9dd6");

    // ---------------- 投放阶段（Map 的 key） ----------------
    private static final Map<String, String> STAGE = Map.ofEntries(
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
            Map.entry("DRYHOP_2", "干投二次"));

    /** 投放阶段在 UI 上的固定顺序（Map 的 key 排序依据） */
    public static final List<String> STAGE_ORDER = List.of(
            "WATER", "MASH_IN", "MASH_OUT", "SPARGE",
            "BOIL_60", "BOIL_30", "BOIL_5", "WHIRLPOOL",
            "PITCH", "FRUIT", "DRYHOP_1", "DRYHOP_2");

    // ---------------- 包装形式 ----------------
    private static final Map<String, String> PACKAGE_FORM = Map.of(
            "BOTTLE", "瓶",
            "CAN", "易拉罐",
            "KEG", "桶");

    // ---------------- 出货状态 ----------------
    private static final Map<String, String> SHIPMENT_STATUS = Map.ofEntries(
            Map.entry("PENDING", "待发"),
            Map.entry("FROZEN", "冻结"),
            Map.entry("SHIPPED", "已发"),
            Map.entry("PAID", "已结款"));

    private static final Map<String, String> SHIPMENT_NEXT = Map.of(
            "PENDING", "SHIPPED",
            "SHIPPED", "PAID");

    // ---------------- 质检有效状态（由最新轮次推导） ----------------
    private static final Map<String, String> QC_STATUS = Map.of(
            "NONE", "待质检",
            "PENDING", "待质检",
            "REINSPECT", "复检中",
            "PASS", "已放行",
            "REJECT", "已驳回",
            "REVOKED", "已撤销");

    private static final Map<String, String> QC_ROUND_STATUS = Map.of(
            "PENDING", "检验中",
            "PASS", "放行",
            "REJECT", "驳回",
            "REVOKED", "已撤销");

    private static final Map<String, String> QC_ACTION = Map.of(
            "SUBMIT", "送检",
            "PASS", "通过放行",
            "REJECT", "驳回",
            "REVOKE", "撤销放行");

    public static String qcStatusName(String v) {
        return QC_STATUS.getOrDefault(v, v == null ? "" : v);
    }

    public static String qcRoundStatusName(String v) {
        return QC_ROUND_STATUS.getOrDefault(v, v == null ? "" : v);
    }

    public static String qcActionName(String v) {
        return QC_ACTION.getOrDefault(v, v == null ? "" : v);
    }

    // ---------------- 词典取值 ----------------
    public static String materialName(String v) {
        return MATERIAL.getOrDefault(v, v == null ? "" : v);
    }

    public static String coolingName(String v) {
        return COOLING.getOrDefault(v, v == null ? "" : v);
    }

    public static String tankStatusName(String v) {
        return TANK_STATUS.getOrDefault(v, v == null ? "" : v);
    }

    public static String tankStatusColor(String v) {
        return TANK_STATUS_COLOR.getOrDefault(v, "#8d9aa5");
    }

    public static String styleName(String v) {
        return STYLE.getOrDefault(v, v == null ? "" : v);
    }

    public static String batchStatusName(String v) {
        return BATCH_STATUS.getOrDefault(v, v == null ? "" : v);
    }

    public static String batchNextStatus(String v) {
        return BATCH_NEXT.get(v);
    }

    public static String tankStatusOfBatch(String batchStatus) {
        return BATCH_TO_TANK.getOrDefault(batchStatus, "IDLE");
    }

    public static String ingredientTypeName(String v) {
        return INGREDIENT_TYPE.getOrDefault(v, v == null ? "" : v);
    }

    public static String ingredientColor(String v) {
        return INGREDIENT_COLOR.getOrDefault(v, "#9aa5ab");
    }

    public static String stageName(String v) {
        return STAGE.getOrDefault(v, v == null ? "" : v);
    }

    public static String packageFormName(String v) {
        return PACKAGE_FORM.getOrDefault(v, v == null ? "" : v);
    }

    public static String shipmentStatusName(String v) {
        return SHIPMENT_STATUS.getOrDefault(v, v == null ? "" : v);
    }

    public static String shipmentNextStatus(String v) {
        return SHIPMENT_NEXT.get(v);
    }

    /** "D1" "D10" 这类 Day key 按数字排序 */
    public static int dayKeyOrder(String key) {
        if (key == null) {
            return Integer.MAX_VALUE;
        }
        String digits = key.replaceAll("\\D", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    // ---------------- 出参视图 ----------------
    @Data
    public static class FermenterView {

        private Long id;
        private String tankNo;
        private BigDecimal capacityL;
        private String material;
        private String materialName;
        private BigDecimal tempMin;
        private BigDecimal tempMax;
        private String cooling;
        private String coolingName;
        private String status;
        private String statusName;
        private String statusColor;
        private String currentBatchNo;
        /** 液位百分比（当前批次产量 / 罐容） */
        private BigDecimal fillPercent;
        private LocalDateTime updatedAt;

        public static FermenterView of(Fermenter e, BigDecimal fillPercent) {
            FermenterView v = new FermenterView();
            v.setId(e.getId());
            v.setTankNo(e.getTankNo());
            v.setCapacityL(e.getCapacityL());
            v.setMaterial(e.getMaterial());
            v.setMaterialName(materialName(e.getMaterial()));
            v.setTempMin(e.getTempMin());
            v.setTempMax(e.getTempMax());
            v.setCooling(e.getCooling());
            v.setCoolingName(coolingName(e.getCooling()));
            v.setStatus(e.getStatus());
            v.setStatusName(tankStatusName(e.getStatus()));
            v.setStatusColor(tankStatusColor(e.getStatus()));
            v.setCurrentBatchNo(e.getCurrentBatchNo());
            v.setFillPercent(fillPercent);
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }
    }

    @Data
    public static class TimelineNode {

        private String stage;
        private String stageName;
        private LocalDateTime time;
        private String status;
    }

    @Data
    public static class CurvePoint {

        private String dayKey;
        private BigDecimal temp;
        private BigDecimal gravity;
    }

    @Data
    public static class BatchView {

        private Long id;
        private String batchNo;
        private Long recipeId;
        private String recipeName;
        private String fermenterNo;
        private String brewer;
        private LocalDate brewDate;
        private LocalDateTime mashStart;
        private LocalDateTime mashEnd;
        private LocalDateTime boilEnd;
        private LocalDateTime fermentStart;
        private LocalDate expectedMature;
        private LocalDate actualMature;
        private BigDecimal volumeL;
        private BigDecimal abv;
        private BigDecimal ibu;
        private BigDecimal ebc;
        private String status;
        private String statusName;
        private String nextStatus;
        private String nextStatusName;
        /** 质检有效状态：NONE/PENDING/REINSPECT/PASS/REJECT/REVOKED */
        private String qcStatus;
        private String qcStatusName;
        /** 质检最新轮序号（null = 尚未送检） */
        private Integer qcRoundSeq;
        /** 最新有效结论的原因/说明（撤销/驳回原因、放行说明） */
        private String qcReason;
        /** 质检轮数 */
        private long qcRoundCount;
        /** 工艺时间线（由各阶段时间字段拼成） */
        private List<TimelineNode> timeline = new ArrayList<>();
        /** @ElementCollection Map 逐日温度/糖度曲线 */
        private List<CurvePoint> curve = new ArrayList<>();
        /**
         * 批次创建当时的配方用量快照（仅批次详情填充）。
         * 是独立历史副本：配方后续改单位/缩放不会改写已生产批次。
         */
        private BatchRecipeSnapshotView recipeSnapshot;
        private LocalDateTime updatedAt;

        public static BatchView of(Batch e, String recipeName) {
            return of(e, recipeName, null);
        }

        /**
         * @param qc 当前批次的质检状态；列表场景可传 null（不额外查库，前端可再拉详情）
         */
        public static BatchView of(Batch e, String recipeName, BatchQcStateView qc) {
            BatchView v = new BatchView();
            v.setId(e.getId());
            v.setBatchNo(e.getBatchNo());
            v.setRecipeId(e.getRecipeId());
            v.setRecipeName(recipeName);
            v.setFermenterNo(e.getFermenterNo());
            v.setBrewer(e.getBrewer());
            v.setBrewDate(e.getBrewDate());
            v.setMashStart(e.getMashStart());
            v.setMashEnd(e.getMashEnd());
            v.setBoilEnd(e.getBoilEnd());
            v.setFermentStart(e.getFermentStart());
            v.setExpectedMature(e.getExpectedMature());
            v.setActualMature(e.getActualMature());
            v.setVolumeL(e.getVolumeL());
            v.setAbv(e.getAbv());
            v.setIbu(e.getIbu());
            v.setEbc(e.getEbc());
            v.setStatus(e.getStatus());
            v.setStatusName(batchStatusName(e.getStatus()));
            String next = batchNextStatus(e.getStatus());
            v.setNextStatus(next);
            v.setNextStatusName(next == null ? "" : batchStatusName(next));
            if (qc != null) {
                v.setQcStatus(qc.getStatus());
                v.setQcStatusName(qc.getStatusName());
                v.setQcRoundSeq(qc.getRoundSeq());
                v.setQcReason(qc.getReason());
                v.setQcRoundCount(qc.getRoundCount());
            }
            v.buildTimeline();
            v.buildCurve(e);
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }

        private void buildTimeline() {
            addNode(timeline, "酿造日", brewDate == null ? null : brewDate.atStartOfDay());
            addNode(timeline, "糖化开始", mashStart);
            addNode(timeline, "糖化结束（转煮沸）", mashEnd);
            addNode(timeline, "煮沸结束", boilEnd);
            addNode(timeline, "发酵开始", fermentStart);
            addNode(timeline, "预计成熟日", expectedMature == null ? null : expectedMature.atStartOfDay());
            addNode(timeline, "实际成熟日", actualMature == null ? null : actualMature.atStartOfDay());
        }

        private void buildCurve(Batch e) {
            Map<String, BigDecimal> temps = e.getTempReadings() == null ? Map.of() : e.getTempReadings();
            Map<String, BigDecimal> gravities = e.getGravityReadings() == null ? Map.of() : e.getGravityReadings();
            List<String> keys = new ArrayList<>(temps.keySet());
            for (String k : gravities.keySet()) {
                if (!keys.contains(k)) {
                    keys.add(k);
                }
            }
            keys.sort((a, b) -> Integer.compare(dayKeyOrder(a), dayKeyOrder(b)));
            for (String k : keys) {
                CurvePoint p = new CurvePoint();
                p.setDayKey(k);
                p.setTemp(temps.get(k));
                p.setGravity(gravities.get(k));
                curve.add(p);
            }
        }
    }

    private static void addNode(List<TimelineNode> nodes, String stageName, LocalDateTime time) {
        TimelineNode node = new TimelineNode();
        node.setStage(stageName);
        node.setStageName(stageName);
        node.setTime(time);
        node.setStatus(time == null ? "PENDING" : "DONE");
        nodes.add(node);
    }

    /** 批次量价：某状态下的产量汇总 */
    @Data
    public static class BatchSummaryItem {

        private String status;
        private String statusName;
        private long count;
        private BigDecimal totalVolume;
    }

    @Data
    public static class BatchSummary {

        private List<BatchSummaryItem> items = new ArrayList<>();
        private long batchTotal;
        private BigDecimal volumeTotal;
        private BigDecimal packagedVolume;
    }

    // ---------------- 模块六：质检 ----------------
    @Data
    public static class BatchQcStateView {

        private String status;
        private String statusName;
        private Integer roundSeq;
        private String reason;
        private long roundCount;
        /** 放行轮版本（前端撤销/下结论时做乐观锁校验） */
        private Integer version;

        public static BatchQcStateView of(String code, QcRound latest, String reason, long roundCount) {
            BatchQcStateView v = new BatchQcStateView();
            v.setStatus(code);
            v.setStatusName(qcStatusName(code));
            v.setRoundSeq(latest == null ? null : latest.getRoundSeq());
            v.setReason(reason);
            v.setRoundCount(roundCount);
            v.setVersion(latest == null ? null : latest.getVersion());
            return v;
        }
    }

    @Data
    public static class QcReadingView {

        private String dayKey;
        private BigDecimal temp;
        private BigDecimal gravity;

        public static QcReadingView of(QcReading r) {
            QcReadingView v = new QcReadingView();
            v.setDayKey(r.getDayKey());
            v.setTemp(r.getTemp());
            v.setGravity(r.getGravity());
            return v;
        }
    }

    @Data
    public static class QcRoundView {

        private Long id;
        private Long batchId;
        private String batchNo;
        private Integer roundSeq;
        private String status;
        private String statusName;
        private String batchStatusSnapshot;
        private String batchStatusSnapshotName;
        private BigDecimal abv;
        private String sensory;
        private String conclusionNote;
        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String revokedBy;
        private LocalDateTime revokedAt;
        private String revokeReason;
        private Integer version;
        private List<QcReadingView> readings = new ArrayList<>();

        public static QcRoundView of(QcRound e) {
            QcRoundView v = new QcRoundView();
            v.setId(e.getId());
            v.setBatchId(e.getBatchId());
            v.setBatchNo(e.getBatchNo());
            v.setRoundSeq(e.getRoundSeq());
            v.setStatus(e.getStatus());
            v.setStatusName(qcRoundStatusName(e.getStatus()));
            v.setBatchStatusSnapshot(e.getBatchStatusSnapshot());
            v.setBatchStatusSnapshotName(batchStatusName(e.getBatchStatusSnapshot()));
            v.setAbv(e.getAbv());
            v.setSensory(e.getSensory());
            v.setConclusionNote(e.getConclusionNote());
            v.setSubmittedBy(e.getSubmittedBy());
            v.setSubmittedAt(e.getSubmittedAt());
            v.setDecidedBy(e.getDecidedBy());
            v.setDecidedAt(e.getDecidedAt());
            v.setRevokedBy(e.getRevokedBy());
            v.setRevokedAt(e.getRevokedAt());
            v.setRevokeReason(e.getRevokeReason());
            v.setVersion(e.getVersion());
            if (e.getReadings() != null) {
                for (QcReading r : e.getReadings()) {
                    v.getReadings().add(QcReadingView.of(r));
                }
            }
            return v;
        }
    }

    @Data
    public static class QcActionView {

        private Long id;
        private Long roundId;
        private Integer roundSeq;
        private String action;
        private String actionName;
        private String conclusion;
        private String operator;
        private String reason;
        private LocalDateTime createdAt;

        public static QcActionView of(QcAction e) {
            QcActionView v = new QcActionView();
            v.setId(e.getId());
            v.setRoundId(e.getRoundId());
            v.setRoundSeq(e.getRoundSeq());
            v.setAction(e.getAction());
            v.setActionName(qcActionName(e.getAction()));
            v.setConclusion(e.getConclusion());
            v.setOperator(e.getOperator());
            v.setReason(e.getReason());
            v.setCreatedAt(e.getCreatedAt());
            return v;
        }
    }

    /** 批次质检档案：当前有效状态 + 历史轮次（含测量快照）+ 操作流水 */
    @Data
    public static class BatchQcView {

        private Long batchId;
        private String batchNo;
        private String batchStatus;
        private String batchStatusName;
        private BatchQcStateView current;
        private List<QcRoundView> rounds = new ArrayList<>();
        private List<QcActionView> actions = new ArrayList<>();

        public static BatchQcView of(Batch b,
                                     com.brewery.craft.service.QcGate.QcState state,
                                     List<QcRound> roundEntities,
                                     List<QcAction> actionEntities) {
            BatchQcView v = new BatchQcView();
            v.setBatchId(b.getId());
            v.setBatchNo(b.getBatchNo());
            v.setBatchStatus(b.getStatus());
            v.setBatchStatusName(batchStatusName(b.getStatus()));
            v.setCurrent(BatchQcStateView.of(state.code, state.latestRound, state.reason, state.roundCount));
            for (QcRound r : roundEntities) {
                v.getRounds().add(QcRoundView.of(r));
            }
            for (QcAction a : actionEntities) {
                v.getActions().add(QcActionView.of(a));
            }
            return v;
        }
    }

    @Data
    public static class RecipeItemView {

        /** Map 的 key = 投放阶段 */
        private String stage;
        private String stageName;
        private Long itemId;
        private String ingredientCode;
        private String ingredientName;
        private String ingredientType;
        private String ingredientTypeName;
        /** 显示数量（酿酒师习惯单位，原样保留） */
        private BigDecimal quantity;
        private String unit;
        /** 计量维度 MASS/VOLUME/COUNT；待处理行为 null */
        private String dimension;
        private String dimensionName;
        /** 规范化单位（kg/g/L/mL/pkg 规范码） */
        private String normalizedUnit;
        /** 规范化数量（基准单位 g/mL/pkg）；待处理行为 null */
        private BigDecimal normalizedQuantity;
        /** OK/NORMALIZED/INFERRED/DIM_MISMATCH/NEEDS_REVIEW */
        private String unitStatus;
        /** 单位归一/推导说明 */
        private String unitNote;
        /** 单位可解析但与原料档案维度不一致（质量/体积可能填错） */
        private boolean dimMismatch;
        private boolean needsReview;
        private String note;

        public static RecipeItemView of(String stage, RecipeItem item) {
            RecipeItemView v = new RecipeItemView();
            v.setStage(stage);
            v.setStageName(stageName(stage));
            v.setItemId(item.getId());
            v.setIngredientCode(item.getIngredientCode());
            v.setIngredientName(item.getIngredientName());
            v.setIngredientType(item.getIngredientType());
            v.setIngredientTypeName(ingredientTypeName(item.getIngredientType()));
            v.setQuantity(item.getQuantity());
            v.setUnit(item.getUnit());
            v.setDimension(item.getDimension());
            com.brewery.craft.measure.Dimension d =
                    com.brewery.craft.measure.Dimension.nameOf(item.getDimension());
            v.setDimensionName(d == null ? null : d.getDisplayName());
            v.setNormalizedUnit(item.getNormalizedUnit());
            v.setNormalizedQuantity(item.getNormalizedQuantity());
            v.setUnitStatus(item.getUnitStatus());
            v.setUnitNote(item.getUnitNote());
            v.setDimMismatch(com.brewery.craft.measure.ResolvedQuantity.DIM_MISMATCH.equals(item.getUnitStatus()));
            v.setNeedsReview(com.brewery.craft.measure.ResolvedQuantity.NEEDS_REVIEW.equals(item.getUnitStatus()));
            v.setNote(item.getNote());
            return v;
        }
    }

    /** 一个维度上的规范化合计单元格 */
    @Data
    public static class MeasureCell {

        private String dimension;
        private String dimensionName;
        private String baseUnit;
        private BigDecimal amount;
    }

    /** 阶段小计：同一阶段、不同维度分列（质量与体积不相加） */
    @Data
    public static class StageTotalView {

        private String stage;
        private String stageName;
        private int lineCount;
        private List<MeasureCell> byDimension = new ArrayList<>();
    }

    @Data
    public static class CompositionSlice {

        private String ingredientType;
        private String ingredientTypeName;
        private String color;
        /** 所属维度（占比只在同一维度内成立） */
        private String dimension;
        private String dimensionName;
        /** 合计与占比所用单位 = 维度基准单位 g/mL/pkg */
        private String baseUnit;
        private BigDecimal amount;
        /** 该类型在【同维度内】的占比 */
        private BigDecimal percent;
    }

    @Data
    public static class RecipeView {

        private Long id;
        private String code;
        private String name;
        private String style;
        private String styleName;
        private BigDecimal targetOg;
        private BigDecimal targetAbv;
        private BigDecimal targetIbu;
        private BigDecimal targetEbc;
        private String processNote;
        private int itemCount;
        /** 基准批量（L）：缩放比例的分母 */
        private BigDecimal baseBatchL;
        /** 乐观锁版本（缩放预览/确认防过期） */
        private Integer version;
        /** 各原料类型在各维度内的占比（conic-gradient 环形图取主维度） */
        private List<CompositionSlice> composition = new ArrayList<>();
        /** 主维度：环形图绘制的那个维度（质量优先，其次体积、计数） */
        private String primaryDimension;
        private String primaryDimensionName;
        private String primaryBaseUnit;
        /** 整张配方按维度分列的规范化合计（g / mL / pkg，质量与体积不相加） */
        private List<MeasureCell> totals = new ArrayList<>();
        /** 按阶段的规范化小计 */
        private List<StageTotalView> stageTotals = new ArrayList<>();
        /** 无法可靠推导单位、未参与计算的明细数（页面需提示人工处理） */
        private int reviewCount;
        private List<RecipeItemView> items = new ArrayList<>();
        private LocalDateTime updatedAt;

        public static RecipeView of(Recipe e) {
            RecipeView v = new RecipeView();
            v.setId(e.getId());
            v.setCode(e.getCode());
            v.setName(e.getName());
            v.setStyle(e.getStyle());
            v.setStyleName(styleName(e.getStyle()));
            v.setTargetOg(e.getTargetOg());
            v.setTargetAbv(e.getTargetAbv());
            v.setTargetIbu(e.getTargetIbu());
            v.setTargetEbc(e.getTargetEbc());
            v.setProcessNote(e.getProcessNote());
            v.setBaseBatchL(e.getBaseBatchL());
            v.setVersion(e.getVersion());
            Map<String, RecipeItem> map = e.getItems() == null ? Map.of() : e.getItems();
            List<String> keys = new ArrayList<>(map.keySet());
            keys.sort((a, b) -> Integer.compare(STAGE_ORDER.indexOf(a) < 0 ? 99 : STAGE_ORDER.indexOf(a),
                    STAGE_ORDER.indexOf(b) < 0 ? 99 : STAGE_ORDER.indexOf(b)));
            for (String key : keys) {
                v.getItems().add(RecipeItemView.of(key, map.get(key)));
            }
            v.setItemCount(v.getItems().size());
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }

        /**
         * 用统一聚合结果填充占比/合计/阶段小计。
         * 列表、环形图、批次详情都走 {@link com.brewery.craft.measure.RecipeMath#aggregate}，同一份结果。
         */
        public void applyAggregate(com.brewery.craft.measure.RecipeMath.Aggregate agg) {
            reviewCount = agg.reviewLines;
            for (com.brewery.craft.measure.RecipeMath.Cell c : agg.dimensionTotals.values()) {
                if (c.amount.signum() > 0) {
                    totals.add(toCell(c));
                }
            }
            for (var st : agg.stageTotals) {
                StageTotalView sv = new StageTotalView();
                sv.setStage(st.stage);
                sv.setStageName(st.stageName);
                sv.setLineCount(st.lineCount);
                for (com.brewery.craft.measure.RecipeMath.Cell c : st.byDimension.values()) {
                    sv.getByDimension().add(toCell(c));
                }
                stageTotals.add(sv);
            }
            com.brewery.craft.measure.Dimension primary =
                    com.brewery.craft.measure.RecipeMath.primaryDimension(agg);
            // 没有任何可用明细时主维度保持为空，环形图由前端置灰
            boolean hasAny = agg.dimensionTotals.values().stream().anyMatch(c -> c.amount.signum() > 0);
            if (hasAny) {
                primaryDimension = primary.name();
                primaryDimensionName = primary.getDisplayName();
                primaryBaseUnit = primary.getBaseUnit();
                for (com.brewery.craft.measure.RecipeMath.Slice s : agg.slicesByDimension.get(primary.name())) {
                    composition.add(toSlice(s));
                }
            }
        }

        private static MeasureCell toCell(com.brewery.craft.measure.RecipeMath.Cell c) {
            MeasureCell m = new MeasureCell();
            m.setDimension(c.dimension);
            m.setDimensionName(c.dimensionName);
            m.setBaseUnit(c.baseUnit);
            m.setAmount(c.amount);
            return m;
        }

        private static CompositionSlice toSlice(com.brewery.craft.measure.RecipeMath.Slice s) {
            CompositionSlice sl = new CompositionSlice();
            sl.setIngredientType(s.ingredientType);
            sl.setIngredientTypeName(s.ingredientTypeName);
            sl.setColor(s.color);
            sl.setDimension(s.dimension);
            sl.setDimensionName(com.brewery.craft.measure.Dimension.nameOf(s.dimension).getDisplayName());
            sl.setBaseUnit(s.baseUnit);
            sl.setAmount(s.amount);
            sl.setPercent(s.percent);
            return sl;
        }
    }

    // ---------------- 模块三：配方缩放预览 / 应用结果 ----------------

    /** 缩放预览中的单条明细：原值 → 换算值 → 取整值 → 余差去向 */
    @Data
    public static class ScaleLineView {

        private String stage;
        private String stageName;
        private String ingredientCode;
        private String ingredientName;
        private String ingredientType;
        private String ingredientTypeName;
        private String dimension;
        private String dimensionName;
        /** 原值（显示单位） */
        private BigDecimal originalQuantity;
        private String originalUnit;
        /** 原值换算到基准单位（g/mL/pkg） */
        private BigDecimal originalBase;
        /** 精确比例换算值（基准单位，未取整） */
        private BigDecimal exactBase;
        /** 先按步长向下取整的值（基准单位） */
        private BigDecimal flooredBase;
        /** 最终采用值（基准单位，已分配余差） */
        private BigDecimal adjustedBase;
        /** 本行分到的余差增量（+1/-1/0 个最小投放量） */
        private BigDecimal remainderDelta;
        /** 最终采用值换算回显示单位 */
        private BigDecimal adjustedQuantity;
        private String adjustedUnit;
        /** 最小可投放量（基准单位） */
        private BigDecimal minBase;
        /** 余差去向说明 */
        private String note;
    }

    /** 一个维度的缩放汇总 */
    @Data
    public static class ScaleDimensionView {

        private String dimension;
        private String dimensionName;
        private String baseUnit;
        private BigDecimal originalTotal;
        private BigDecimal targetTotal;
        private BigDecimal exactTotal;
        private BigDecimal adjustedTotal;
        /** 受最小投放量约束仍未对齐目标的余差（必须向用户明示；正常为 0） */
        private BigDecimal leftover;
        private List<ScaleLineView> lines = new ArrayList<>();
    }

    /** 缩放预览（不落库）；应用时必须带回 baseVersion 做版本校验 */
    @Data
    public static class ScalePreviewView {

        private Long recipeId;
        private String recipeName;
        private Integer baseVersion;
        private BigDecimal factor;
        private BigDecimal baseBatchL;
        private BigDecimal targetBatchL;
        /** 预览时该配方存在的待处理单位明细数（存在则不允许应用，先修单位） */
        private int reviewCount;
        private List<ScaleDimensionView> dimensions = new ArrayList<>();
        private List<ScaleLineView> lines = new ArrayList<>();
    }

    // ---------------- 模块二：批次创建当时的配方用量快照（只读历史） ----------------
    @Data
    public static class BatchRecipeItemView {

        private String stage;
        private String stageName;
        private String ingredientCode;
        private String ingredientName;
        private String ingredientType;
        private String ingredientTypeName;
        private BigDecimal quantity;
        private String unit;
        private String dimension;
        private String dimensionName;
        private String normalizedUnit;
        private BigDecimal normalizedQuantity;
        private String unitStatus;
        private String unitNote;
        private boolean dimMismatch;
        private boolean needsReview;
        /** CREATED 建档快照 / MIGRATED 历史回填 */
        private String snapshotSource;
        private String snapshotSourceName;
    }

    /** 批次快照的规范化合计（按维度分列，与配方页同一套规则） */
    @Data
    public static class BatchRecipeSnapshotView {

        private Long batchId;
        private String batchNo;
        private String recipeName;
        /** 快照时的配方版本（历史回填为空） */
        private Integer recipeVersion;
        private boolean migrated;
        private List<BatchRecipeItemView> items = new ArrayList<>();
        private List<MeasureCell> totals = new ArrayList<>();
        private List<StageTotalView> stageTotals = new ArrayList<>();
        private int reviewCount;
    }

    @Data
    public static class IngredientView {

        private Long id;
        private String code;
        private String name;
        private String type;
        private String typeName;
        private String unit;
        private BigDecimal stock;
        private BigDecimal safetyStock;
        private BigDecimal unitPrice;
        private String supplier;
        private LocalDate shelfLifeTo;
        /** 库存 = 0 为 false；stock < safetyStock 时 true，页面标红 */
        private boolean low;
        private LocalDateTime updatedAt;

        public static IngredientView of(Ingredient e) {
            IngredientView v = new IngredientView();
            v.setId(e.getId());
            v.setCode(e.getCode());
            v.setName(e.getName());
            v.setType(e.getType());
            v.setTypeName(ingredientTypeName(e.getType()));
            v.setUnit(e.getUnit());
            v.setStock(e.getStock());
            v.setSafetyStock(e.getSafetyStock());
            v.setUnitPrice(e.getUnitPrice());
            v.setSupplier(e.getSupplier());
            v.setShelfLifeTo(e.getShelfLifeTo());
            v.setLow(e.getStock() != null && e.getSafetyStock() != null
                    && e.getStock().compareTo(e.getSafetyStock()) < 0);
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }
    }

    @Data
    public static class BottlingView {

        private Long id;
        private String bottlingNo;
        private String batchNo;
        private String packageForm;
        private String packageFormName;
        private Integer quantity;
        private LocalDate bottleDate;
        private BigDecimal lossL;
        /** 灌装时该批次的质检有效状态 */
        private String qcStatus;
        private String qcStatusName;
        private String qcReason;
        private String batchStatus;
        private String batchStatusName;
        private LocalDateTime updatedAt;

        public static BottlingView of(Bottling e) {
            return of(e, null, null, null);
        }

        public static BottlingView of(Bottling e, String qcStatus, String qcReason, String batchStatus) {
            BottlingView v = new BottlingView();
            v.setId(e.getId());
            v.setBottlingNo(e.getBottlingNo());
            v.setBatchNo(e.getBatchNo());
            v.setPackageForm(e.getPackageForm());
            v.setPackageFormName(packageFormName(e.getPackageForm()));
            v.setQuantity(e.getQuantity());
            v.setBottleDate(e.getBottleDate());
            v.setLossL(e.getLossL());
            v.setQcStatus(qcStatus);
            v.setQcStatusName(qcStatus == null ? "" : qcStatusName(qcStatus));
            v.setQcReason(qcReason);
            v.setBatchStatus(batchStatus);
            v.setBatchStatusName(batchStatus == null ? "" : batchStatusName(batchStatus));
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }
    }

    @Data
    public static class ShipmentView {

        private Long id;
        private String shipmentNo;
        private String customer;
        private String batchNo;
        private String packageForm;
        private String packageFormName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private LocalDate shipDate;
        private String status;
        private String statusName;
        private String nextStatus;
        private String nextStatusName;
        /** 冻结原因 / 质检状态说明（非冻结时为当前批次质检结论说明） */
        private String frozenReason;
        private LocalDateTime frozenAt;
        private Integer frozenRoundSeq;
        /** 该批次当前的质检有效状态 */
        private String qcStatus;
        private String qcStatusName;
        private String qcReason;
        private String batchStatus;
        private String batchStatusName;
        /** 出货推进被阻止时的业务原因（FROZEN/放行失效等） */
        private String blockedReason;
        private LocalDateTime updatedAt;

        public static ShipmentView of(Shipment e) {
            return of(e, null, null, null);
        }

        public static ShipmentView of(Shipment e, String qcStatus, String qcReason, String batchStatus) {
            ShipmentView v = new ShipmentView();
            v.setId(e.getId());
            v.setShipmentNo(e.getShipmentNo());
            v.setCustomer(e.getCustomer());
            v.setBatchNo(e.getBatchNo());
            v.setPackageForm(e.getPackageForm());
            v.setPackageFormName(packageFormName(e.getPackageForm()));
            v.setQuantity(e.getQuantity());
            v.setUnitPrice(e.getUnitPrice());
            v.setAmount(e.getAmount());
            v.setShipDate(e.getShipDate());
            v.setStatus(e.getStatus());
            v.setStatusName(shipmentStatusName(e.getStatus()));
            String next = shipmentNextStatus(e.getStatus());
            v.setNextStatus(next);
            v.setNextStatusName(next == null ? "" : shipmentStatusName(next));
            v.setFrozenReason(e.getFrozenReason());
            v.setFrozenAt(e.getFrozenAt());
            v.setFrozenRoundSeq(e.getFrozenRoundSeq());
            v.setQcStatus(qcStatus);
            v.setQcStatusName(qcStatus == null ? "" : qcStatusName(qcStatus));
            v.setQcReason(qcReason);
            v.setBatchStatus(batchStatus);
            v.setBatchStatusName(batchStatus == null ? "" : batchStatusName(batchStatus));
            if ("FROZEN".equals(e.getStatus())) {
                v.setBlockedReason(e.getFrozenReason());
            }
            v.setUpdatedAt(e.getUpdatedAt());
            return v;
        }
    }

    @Data
    public static class ShipmentSummaryItem {

        private String status;
        private String statusName;
        private long count;
        private long quantity;
        private BigDecimal amount;
    }

    @Data
    public static class ShipmentSummary {

        private List<ShipmentSummaryItem> items = new ArrayList<>();
        private long orderTotal;
        private long quantityTotal;
        private BigDecimal amountTotal;
        private BigDecimal paidAmount;
        private long frozenCount;
    }

    @Data
    public static class Overview {

        private long tankTotal;
        private long tankBusy;
        private long tankIdle;
        private Map<String, Long> tankStatusCount = new LinkedHashMap<>();
        private BigDecimal capacityTotal;
        private long batchTotal;
        private long batchFermenting;
        private long batchPackaged;
        private BigDecimal batchVolumeTotal;
        private BigDecimal packagedVolume;
        private long recipeTotal;
        private long ingredientLow;
        private long shipmentPending;
        private BigDecimal shipmentAmount;
    }
}
