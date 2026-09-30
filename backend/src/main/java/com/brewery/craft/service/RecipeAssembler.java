package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.BatchRecipeItem;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.RecipeItem;
import com.brewery.craft.dto.Views;
import com.brewery.craft.measure.Dimension;
import com.brewery.craft.measure.RecipeMath;
import com.brewery.craft.measure.ResolvedQuantity;
import com.brewery.craft.measure.ScalingPlanner;
import com.brewery.craft.measure.UnitDef;
import com.brewery.craft.repository.IngredientRepository;

/**
 * 配方计量的唯一装配处：
 * <ol>
 *   <li>把每条用量明细解析成「显示值 + 规范化值」（{@link ResolvedQuantity}）；</li>
 *   <li>用 {@link RecipeMath} 统一产出占比 / 阶段小计 / 维度合计（列表、环形图共用）；</li>
 *   <li>用 {@link ScalingPlanner} 产出缩放预览。</li>
 * </ol>
 * 批次快照 {@link BatchRecipeItem} 也复用这里的解析结果，保证四处（写明细/列表/环形图/批次详情）一套规则。
 */
@Component
public class RecipeAssembler {

    /** 历史/新建配方缺省基准批量（L） */
    public static final BigDecimal DEFAULT_BASE_BATCH_L = new BigDecimal("1000.00");

    private final IngredientRepository ingredientRepository;

    public RecipeAssembler(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    /** 原料编码 → 档案单位缓存（一次配方内只查库一轮） */
    public Map<String, String> masterUnitIndex(List<String> codes) {
        Map<String, String> idx = new HashMap<>();
        if (codes.isEmpty()) {
            return idx;
        }
        for (Ingredient ing : ingredientRepository.findAllByCodeIn(new java.util.HashSet<>(codes))) {
            idx.put(ing.getCode(), ing.getUnit());
        }
        return idx;
    }

    /** 解析一条配方明细并把规范化结果回写到实体（写路径：新增/修改明细、数据迁移） */
    public ResolvedQuantity resolveAndStamp(RecipeItem item, String masterUnit) {
        ResolvedQuantity rq = ResolvedQuantity.resolve(
                item.getIngredientType(), item.getQuantity(), item.getUnit(), masterUnit);
        if (rq.dimension() != null) {
            item.setDimension(rq.dimension().name());
            item.setNormalizedUnit(rq.displayUnit());
            item.setNormalizedQuantity(rq.baseQuantity().setScale(3, RoundingMode.HALF_UP));
        } else {
            item.setDimension(null);
            item.setNormalizedUnit(null);
            item.setNormalizedQuantity(null);
        }
        item.setUnitStatus(rq.status());
        item.setUnitNote(rq.resolutionNote());
        return rq;
    }

    /** 重新解析整张配方的全部明细（单位档案变更、数据迁移后重算规范化列） */
    public int resolveAll(Recipe recipe) {
        List<String> codes = new ArrayList<>();
        for (RecipeItem it : recipe.getItems().values()) {
            codes.add(it.getIngredientCode());
        }
        Map<String, String> master = masterUnitIndex(codes);
        int review = 0;
        for (RecipeItem it : recipe.getItems().values()) {
            ResolvedQuantity rq = resolveAndStamp(it, master.get(it.getIngredientCode()));
            if (rq.needsReview()) {
                review++;
            }
        }
        if (recipe.getBaseBatchL() == null || recipe.getBaseBatchL().signum() <= 0) {
            recipe.setBaseBatchL(DEFAULT_BASE_BATCH_L);
        }
        return review;
    }

    /** 组装带完整计量聚合的配方视图（列表与详情同一份数据） */
    public Views.RecipeView toView(Recipe recipe) {
        Views.RecipeView v = Views.RecipeView.of(recipe);
        List<RecipeMath.Row> rows = new ArrayList<>();
        for (Map.Entry<String, RecipeItem> e : recipe.getItems().entrySet()) {
            rows.add(new ItemRow(e.getKey(), e.getValue()));
        }
        v.applyAggregate(RecipeMath.aggregate(rows, Views.STAGE_ORDER));
        return v;
    }

    /** 环形图（与详情视图内的 composition 同源，独立接口时直接复用） */
    public List<Views.CompositionSlice> composition(Recipe recipe) {
        return toView(recipe).getComposition();
    }

    // ---------------- 缩放 ----------------

    public Views.ScalePreviewView preview(Recipe recipe, BigDecimal targetBatchL) {
        BigDecimal base = effectiveBase(recipe);
        validateTarget(targetBatchL);
        BigDecimal factor = targetBatchL.divide(base, 6, RoundingMode.HALF_UP);

        List<ScalingPlanner.InputLine> inputs = new ArrayList<>();
        int idx = 0;
        int review = 0;
        for (Map.Entry<String, RecipeItem> e : orderedItems(recipe).entrySet()) {
            RecipeItem it = e.getValue();
            if (ResolvedQuantity.NEEDS_REVIEW.equals(it.getUnitStatus()) || it.getDimension() == null) {
                review++;
                continue;
            }
            Dimension dim = Dimension.nameOf(it.getDimension());
            inputs.add(new ScalingPlanner.InputLine(idx++, e.getKey(), it.getIngredientCode(),
                    it.getIngredientName(), it.getIngredientType(), dim,
                    it.getQuantity(), displayUnit(it), it.getNormalizedQuantity()));
        }
        ScalingPlanner.Plan plan = ScalingPlanner.plan(inputs, factor, base, targetBatchL);
        return toPreview(recipe, plan, review);
    }

    /** 应用缩放：先校验预览版本与目标批量，再以当前明细重新生成方案落库（绝不直接写入客户端回传的数值） */
    public void apply(Recipe recipe, Integer baseVersion, BigDecimal targetBatchL) {
        if (baseVersion == null) {
            throw new BizException("应用缩放必须带预览版本号，请先发起预览");
        }
        if (recipe.getVersion() == null || !recipe.getVersion().equals(baseVersion)) {
            throw new com.brewery.craft.common.StalePreviewException(
                    "缩放预览已过期：预览基于配方版本 " + baseVersion + "，当前版本为 "
                            + recipe.getVersion() + "，明细或基准批量可能已被修改，请重新预览后再应用");
        }
        validateTarget(targetBatchL);
        Views.ScalePreviewView pv = preview(recipe, targetBatchL);
        if (pv.getReviewCount() > 0) {
            throw new BizException("配方存在 " + pv.getReviewCount()
                    + " 条单位待处理明细，无法缩放；请先在用量明细中补正确认单位");
        }
        // 用服务端重算结果按阶段覆盖每条明细（显示单位保持酿酒师习惯单位不变）
        for (Views.ScaleLineView line : pv.getLines()) {
            RecipeItem it = recipe.getItems().get(line.getStage());
            if (it == null || !safeEq(it.getIngredientCode(), line.getIngredientCode())
                    || it.getNormalizedQuantity() == null
                    || it.getNormalizedQuantity().compareTo(line.getOriginalBase()) != 0) {
                // 预览之后这条明细被增删/改过（版本号未及拦住的极端并发），同样按过期处理
                throw new com.brewery.craft.common.StalePreviewException(
                        "缩放预览已过期：阶段 " + line.getStage() + " 的明细已变化，请重新预览后再应用");
            }
            it.setQuantity(line.getAdjustedQuantity());
            // unit 不变（仍是酿酒师习惯单位）；规范化数量同步为采用值（基准单位）
            it.setNormalizedQuantity(line.getAdjustedBase().setScale(3, RoundingMode.HALF_UP));
            // 维度/规范单位/单位状态不随缩放改变
        }
        recipe.setBaseBatchL(targetBatchL.setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void validateTarget(BigDecimal targetBatchL) {
        if (targetBatchL == null || targetBatchL.signum() <= 0) {
            throw new BizException("目标批量必须大于 0");
        }
        if (targetBatchL.compareTo(new BigDecimal("100000")) > 0) {
            throw new BizException("目标批量超出合理范围（≤ 100000 L）");
        }
    }

    private BigDecimal effectiveBase(Recipe recipe) {
        if (recipe.getBaseBatchL() != null && recipe.getBaseBatchL().signum() > 0) {
            return recipe.getBaseBatchL();
        }
        return DEFAULT_BASE_BATCH_L;
    }

    private String displayUnit(RecipeItem it) {
        UnitDef ud = UnitDef.byCode(it.getNormalizedUnit());
        return ud == null ? it.getUnit() : ud.getCode();
    }

    private Map<String, RecipeItem> orderedItems(Recipe recipe) {
        Map<String, RecipeItem> ordered = new java.util.LinkedHashMap<>();
        List<String> keys = new ArrayList<>(recipe.getItems().keySet());
        keys.sort((a, b) -> Integer.compare(
                Views.STAGE_ORDER.indexOf(a) < 0 ? 99 : Views.STAGE_ORDER.indexOf(a),
                Views.STAGE_ORDER.indexOf(b) < 0 ? 99 : Views.STAGE_ORDER.indexOf(b)));
        for (String k : keys) {
            ordered.put(k, recipe.getItems().get(k));
        }
        return ordered;
    }

    private Views.ScalePreviewView toPreview(Recipe recipe, ScalingPlanner.Plan plan, int review) {
        Views.ScalePreviewView pv = new Views.ScalePreviewView();
        pv.setRecipeId(recipe.getId());
        pv.setRecipeName(recipe.getName());
        pv.setBaseVersion(recipe.getVersion());
        pv.setFactor(plan.factor.compareTo(BigDecimal.ONE) == 0
                ? BigDecimal.ONE
                : plan.factor.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros());
        pv.setBaseBatchL(plan.baseBatchL);
        pv.setTargetBatchL(plan.targetBatchL);
        pv.setReviewCount(review);
        for (ScalingPlanner.DimensionResult dr : plan.dimensions.values()) {
            Views.ScaleDimensionView dv = new Views.ScaleDimensionView();
            dv.setDimension(dr.dimension.name());
            dv.setDimensionName(dr.dimension.getDisplayName());
            dv.setBaseUnit(dr.baseUnit);
            dv.setOriginalTotal(dr.originalTotal);
            dv.setTargetTotal(dr.targetTotal);
            dv.setExactTotal(dr.exactTotal);
            dv.setAdjustedTotal(dr.adjustedTotal);
            dv.setLeftover(dr.leftover == null ? BigDecimal.ZERO : dr.leftover);
            for (ScalingPlanner.LineResult l : dr.lines) {
                dv.getLines().add(toLine(l));
            }
            pv.getDimensions().add(dv);
        }
        for (ScalingPlanner.LineResult l : plan.lines) {
            pv.getLines().add(toLine(l));
        }
        return pv;
    }

    private Views.ScaleLineView toLine(ScalingPlanner.LineResult l) {
        Views.ScaleLineView lv = new Views.ScaleLineView();
        lv.setStage(l.stage);
        lv.setStageName(Views.stageName(l.stage));
        lv.setIngredientCode(l.ingredientCode);
        lv.setIngredientName(l.ingredientName);
        lv.setIngredientType(l.ingredientType);
        lv.setIngredientTypeName(Views.ingredientTypeName(l.ingredientType));
        lv.setDimension(l.dimension.name());
        lv.setDimensionName(l.dimension.getDisplayName());
        lv.setOriginalQuantity(l.originalQuantity);
        lv.setOriginalUnit(l.originalUnit);
        lv.setOriginalBase(l.originalBase);
        lv.setExactBase(l.exactBase);
        lv.setFlooredBase(l.flooredBase);
        lv.setAdjustedBase(l.adjustedBase);
        lv.setRemainderDelta(l.remainderDelta);
        lv.setAdjustedQuantity(l.adjustedQuantity);
        lv.setAdjustedUnit(l.originalUnit);
        lv.setMinBase(l.minBase);
        lv.setNote(l.note);
        return lv;
    }

    /** RecipeItem → 统一聚合行 */
    private record ItemRow(String stage, RecipeItem item) implements RecipeMath.Row {
        @Override public String stage() { return stage; }
        @Override public String ingredientType() { return item.getIngredientType(); }
        @Override public String dimension() { return item.getDimension(); }
        @Override public String unitStatus() { return item.getUnitStatus(); }
        @Override public BigDecimal baseQuantity() { return item.getNormalizedQuantity(); }
    }

    /** BatchRecipeItem → 统一聚合行（批次快照与配方明细共用同一聚合器） */
    public record SnapshotRow(String stage, BatchRecipeItem item) implements RecipeMath.Row {
        @Override public String stage() { return stage; }
        @Override public String ingredientType() { return item.getIngredientType(); }
        @Override public String dimension() { return item.getDimension(); }
        @Override public String unitStatus() { return item.getUnitStatus(); }
        @Override public BigDecimal baseQuantity() { return item.getNormalizedQuantity(); }
    }
}
