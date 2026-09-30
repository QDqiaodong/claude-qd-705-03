package com.brewery.craft.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.brewery.craft.domain.BatchRecipeItem;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.RecipeItem;
import com.brewery.craft.dto.Views;
import com.brewery.craft.measure.Dimension;
import com.brewery.craft.measure.RecipeMath;

/**
 * 批次配方用量快照装配：
 * <ul>
 *   <li>批次建档时从配方复制一份独立、不可变的明细（含规范化列），{@code snapshotSource=CREATED}；</li>
 *   <li>历史批次由数据迁移回填，{@code snapshotSource=MIGRATED}；</li>
 *   <li>详情展示用 {@link RecipeMath} 同一套聚合规则，但只读快照、永不按现配方重算。</li>
 * </ul>
 */
@Component
public class BatchSnapshotAssembler {

    /** 建档快照：复制配方当前明细（深拷贝独立行，不与配方共享 id/对象） */
    public List<BatchRecipeItem> snapshotFromRecipe(Long batchId, Recipe recipe, String source) {
        List<BatchRecipeItem> rows = new ArrayList<>();
        for (Map.Entry<String, RecipeItem> e : ordered(recipe).entrySet()) {
            rows.add(copy(batchId, e.getKey(), e.getValue(), source));
        }
        return rows;
    }

    private BatchRecipeItem copy(Long batchId, String stage, RecipeItem it, String source) {
        BatchRecipeItem s = new BatchRecipeItem();
        s.setBatchId(batchId);
        s.setStage(stage);
        s.setIngredientCode(it.getIngredientCode());
        s.setIngredientName(it.getIngredientName());
        s.setIngredientType(it.getIngredientType());
        s.setQuantity(it.getQuantity());
        s.setUnit(it.getUnit());
        s.setDimension(it.getDimension());
        s.setNormalizedUnit(it.getNormalizedUnit());
        s.setNormalizedQuantity(it.getNormalizedQuantity());
        s.setUnitStatus(it.getUnitStatus());
        s.setUnitNote(it.getUnitNote());
        s.setSnapshotSource(source);
        return s;
    }

    /** 组装批次详情快照视图（只读，按维度分列规范化合计） */
    public Views.BatchRecipeSnapshotView toView(Long batchId, String batchNo, String recipeName,
                                                Integer recipeVersion, List<BatchRecipeItem> rows) {
        Views.BatchRecipeSnapshotView v = new Views.BatchRecipeSnapshotView();
        v.setBatchId(batchId);
        v.setBatchNo(batchNo);
        v.setRecipeName(recipeName);
        v.setRecipeVersion(recipeVersion);
        boolean migrated = rows.stream().anyMatch(r -> "MIGRATED".equals(r.getSnapshotSource()));
        v.setMigrated(migrated);

        List<RecipeMath.Row> aggRows = new ArrayList<>();
        for (BatchRecipeItem s : rows) {
            v.getItems().add(itemView(s));
            aggRows.add(new RecipeAssembler.SnapshotRow(s.getStage(), s));
        }
        RecipeMath.Aggregate agg = RecipeMath.aggregate(aggRows, Views.STAGE_ORDER);
        v.setReviewCount(agg.reviewLines);
        for (RecipeMath.Cell c : agg.dimensionTotals.values()) {
            if (c.amount.signum() > 0) {
                Views.MeasureCell m = new Views.MeasureCell();
                m.setDimension(c.dimension);
                m.setDimensionName(c.dimensionName);
                m.setBaseUnit(c.baseUnit);
                m.setAmount(c.amount);
                v.getTotals().add(m);
            }
        }
        for (RecipeMath.StageTotal st : agg.stageTotals) {
            Views.StageTotalView sv = new Views.StageTotalView();
            sv.setStage(st.stage);
            sv.setStageName(st.stageName);
            sv.setLineCount(st.lineCount);
            for (RecipeMath.Cell c : st.byDimension.values()) {
                Views.MeasureCell m = new Views.MeasureCell();
                m.setDimension(c.dimension);
                m.setDimensionName(c.dimensionName);
                m.setBaseUnit(c.baseUnit);
                m.setAmount(c.amount);
                sv.getByDimension().add(m);
            }
            v.getStageTotals().add(sv);
        }
        return v;
    }

    private Views.BatchRecipeItemView itemView(BatchRecipeItem s) {
        Views.BatchRecipeItemView iv = new Views.BatchRecipeItemView();
        iv.setStage(s.getStage());
        iv.setStageName(Views.stageName(s.getStage()));
        iv.setIngredientCode(s.getIngredientCode());
        iv.setIngredientName(s.getIngredientName());
        iv.setIngredientType(s.getIngredientType());
        iv.setIngredientTypeName(Views.ingredientTypeName(s.getIngredientType()));
        iv.setQuantity(s.getQuantity());
        iv.setUnit(s.getUnit());
        iv.setDimension(s.getDimension());
        Dimension d = Dimension.nameOf(s.getDimension());
        iv.setDimensionName(d == null ? null : d.getDisplayName());
        iv.setNormalizedUnit(s.getNormalizedUnit());
        iv.setNormalizedQuantity(s.getNormalizedQuantity());
        iv.setUnitStatus(s.getUnitStatus());
        iv.setUnitNote(s.getUnitNote());
        iv.setDimMismatch(com.brewery.craft.measure.ResolvedQuantity.DIM_MISMATCH.equals(s.getUnitStatus()));
        iv.setNeedsReview(com.brewery.craft.measure.ResolvedQuantity.NEEDS_REVIEW.equals(s.getUnitStatus()));
        iv.setSnapshotSource(s.getSnapshotSource());
        iv.setSnapshotSourceName("MIGRATED".equals(s.getSnapshotSource()) ? "历史回填" : "建档快照");
        return iv;
    }

    private Map<String, RecipeItem> ordered(Recipe recipe) {
        Map<String, RecipeItem> ordered = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(recipe.getItems().keySet());
        keys.sort((a, b) -> Integer.compare(
                Views.STAGE_ORDER.indexOf(a) < 0 ? 99 : Views.STAGE_ORDER.indexOf(a),
                Views.STAGE_ORDER.indexOf(b) < 0 ? 99 : Views.STAGE_ORDER.indexOf(b)));
        for (String k : keys) {
            ordered.put(k, recipe.getItems().get(k));
        }
        return ordered;
    }
}
