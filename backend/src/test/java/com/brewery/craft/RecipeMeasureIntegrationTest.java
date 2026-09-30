package com.brewery.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.brewery.craft.common.BizException;
import com.brewery.craft.common.StalePreviewException;
import com.brewery.craft.domain.BatchRecipeItem;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.RecipeItem;
import com.brewery.craft.dto.RecipeDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRecipeItemRepository;
import com.brewery.craft.repository.IngredientRepository;
import com.brewery.craft.repository.RecipeRepository;
import com.brewery.craft.service.BatchService;
import com.brewery.craft.service.RecipeAssembler;
import com.brewery.craft.service.RecipeService;
import com.brewery.craft.dto.BatchDtos;

/**
 * 端到端：混合单位归一、占比/合计同源、缩放（预览/应用/过期/往返）、批次快照不可变。
 */
@SpringBootTest
class RecipeMeasureIntegrationTest {

    @Autowired private RecipeService recipeService;
    @Autowired private RecipeAssembler assembler;
    @Autowired private RecipeRepository recipeRepository;
    @Autowired private IngredientRepository ingredientRepository;
    @Autowired private BatchRecipeItemRepository batchRecipeItemRepository;
    @Autowired private BatchService batchService;

    private Ingredient ingredient(String code, String name, String type, String unit) {
        Ingredient e = new Ingredient();
        e.setCode(code);
        e.setName(name);
        e.setType(type);
        e.setUnit(unit);
        e.setStock(new BigDecimal("1000"));
        e.setSafetyStock(new BigDecimal("10"));
        return ingredientRepository.save(e);
    }

    private Recipe recipe(String code, BigDecimal baseBatch) {
        Recipe r = new Recipe();
        r.setCode(code);
        r.setName("测试-" + code);
        r.setStyle("IPA");
        r.setBaseBatchL(baseBatch);
        return recipeRepository.save(r);
    }

    private RecipeDtos.ItemSaveReq item(String ingCode, String qty, String unit) {
        RecipeDtos.ItemSaveReq req = new RecipeDtos.ItemSaveReq();
        req.setIngredientCode(ingCodeCode(ingCode));
        req.setQuantity(new BigDecimal(qty));
        req.setUnit(unit);
        return req;
    }

    private String ingCodeCode(String code) {
        return code;
    }

    // 1. kg 与 g 混用：先换算再汇总，占比在质量维度内正确
    @Test
    void mixedKilogramsAndGramsAggregateCorrectly() {
        String malt = ingredient("T-MALT-1", "测试麦芽", "MALT", "kg").getCode();
        String hop = ingredient("T-HOP-1", "测试酒花", "HOP", "kg").getCode();
        Recipe r = recipe("TR-001", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "kg"));
        recipeService.putItem(r.getId(), "BOIL_60", item(hop, "500", "g"));

        Views.RecipeView v = recipeService.get(r.getId());
        // 质量合计 = 1500 g
        Views.MeasureCell mass = v.getTotals().stream().filter(t -> "MASS".equals(t.getDimension())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1500").compareTo(mass.getAmount()));
        // 主维度占比：麦芽 66.7%、酒花 33.3%
        assertEquals("MASS", v.getPrimaryDimension());
        var byType = new java.util.HashMap<String, BigDecimal>();
        v.getComposition().forEach(s -> byType.put(s.getIngredientType(), s.getPercent()));
        assertEquals(0, new BigDecimal("66.7").compareTo(byType.get("MALT")));
        assertEquals(0, new BigDecimal("33.3").compareTo(byType.get("HOP")));
        // 明细保留显示单位
        Views.RecipeItemView maltRow = v.getItems().stream().filter(i -> "MASH_IN".equals(i.getStage())).findFirst().orElseThrow();
        assertEquals("kg", maltRow.getUnit());
        assertEquals(0, new BigDecimal("1000").compareTo(maltRow.getNormalizedQuantity()));
    }

    // 2. 体积（L/mL）与质量分列，不相加
    @Test
    void volumeAndMassAreSeparateDimensions() {
        String malt = ingredient("T-MALT-2", "测试麦芽2", "MALT", "kg").getCode();
        String water = ingredient("T-WAT-2", "乳酸", "WATER", "L").getCode();
        Recipe r = recipe("TR-002", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "2", "kg"));
        recipeService.putItem(r.getId(), "WATER", item(water, "1.5", "L"));

        Views.RecipeView v = recipeService.get(r.getId());
        Views.MeasureCell mass = v.getTotals().stream().filter(t -> "MASS".equals(t.getDimension())).findFirst().orElseThrow();
        Views.MeasureCell vol = v.getTotals().stream().filter(t -> "VOLUME".equals(t.getDimension())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2000").compareTo(mass.getAmount()));
        assertEquals(0, new BigDecimal("1500").compareTo(vol.getAmount()));
    }

    // 3. 旧写法/大小写归一；空单位按档案推导
    @Test
    void legacyUnitsNormalizedAndBlankInferred() {
        String malt = ingredient("T-MALT-3", "麦芽3", "MALT", "kg").getCode();
        Recipe r = recipe("TR-003", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "KG"));
        RecipeDtos.ItemSaveReq blank = item(malt, "2", null);
        recipeService.putItem(r.getId(), "MASH_OUT", blank);

        Views.RecipeView v = recipeService.get(r.getId());
        List<Views.RecipeItemView> rows = v.getItems();
        Views.RecipeItemView kg = rows.stream().filter(i -> "MASH_IN".equals(i.getStage())).findFirst().orElseThrow();
        Views.RecipeItemView inferred = rows.stream().filter(i -> "MASH_OUT".equals(i.getStage())).findFirst().orElseThrow();
        // API 写入路径把旧写法直接归一成规范码落库（新数据保持干净），换算后 1000g
        assertEquals("kg", kg.getUnit());
        assertEquals("OK", kg.getUnitStatus());
        assertEquals(0, new BigDecimal("1000").compareTo(kg.getNormalizedQuantity()));
        assertEquals("INFERRED", inferred.getUnitStatus());
        // 空单位落 NULL，规范化采用档案单位 kg
        assertNull(inferred.getUnit());
        assertEquals("kg", inferred.getNormalizedUnit());
        assertEquals(0, new BigDecimal("2000").compareTo(inferred.getNormalizedQuantity()));
    }

    // 4. 写入无法识别的单位直接被拒绝（不产生新的待处理行）
    @Test
    void unknownUnitRejectedOnWrite() {
        String hop = ingredient("T-HOP-4", "酒花4", "HOP", "kg").getCode();
        Recipe r = recipe("TR-004", new BigDecimal("1000"));
        BizException ex = assertThrows(BizException.class,
                () -> recipeService.putItem(r.getId(), "BOIL_60", item(hop, "1", "罐")));
        assertTrue(ex.getMessage().contains("不支持的单位"), ex.getMessage());
    }

    // 5. 缩放预览含原值/换算值/调整后/余差；应用后规范化合计对齐目标
    @Test
    void scalePreviewAndApplyAlignsNormalizedTotal() {
        String malt = ingredient("T-MALT-5", "麦芽5", "MALT", "kg").getCode();
        String hop = ingredient("T-HOP-5", "酒花5", "HOP", "kg").getCode();
        Recipe r = recipe("TR-005", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "kg"));
        recipeService.putItem(r.getId(), "BOIL_60", item(hop, "500", "g"));

        Views.ScalePreviewView pv = recipeService.scalePreview(r.getId(), new BigDecimal("2000"));
        assertEquals(0, new BigDecimal("2").compareTo(pv.getFactor()));
        assertEquals(0, new BigDecimal("3000").compareTo(
                pv.getDimensions().get(0).getAdjustedTotal()));
        for (Views.ScaleLineView l : pv.getLines()) {
            assertNotNull(l.getOriginalBase());
            assertNotNull(l.getExactBase());
            assertNotNull(l.getAdjustedBase());
            assertNotNull(l.getAdjustedQuantity());
        }

        RecipeDtos.ScaleApplyReq apply = new RecipeDtos.ScaleApplyReq();
        apply.setTargetBatchL(new BigDecimal("2000"));
        apply.setBaseVersion(pv.getBaseVersion());
        Views.RecipeView after = recipeService.scaleApply(r.getId(), apply);
        Views.MeasureCell mass = after.getTotals().stream().filter(t -> "MASS".equals(t.getDimension())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3000").compareTo(mass.getAmount()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(after.getBaseBatchL()));
    }

    // 6. 预览期间明细被改 → 应用时 409 过期，不能用旧预览覆盖
    @Test
    void stalePreviewRejectedWhenItemsChanged() {
        String malt = ingredient("T-MALT-6", "麦芽6", "MALT", "kg").getCode();
        Recipe r = recipe("TR-006", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "kg"));
        Views.ScalePreviewView pv = recipeService.scalePreview(r.getId(), new BigDecimal("2000"));

        // 期间另一个人改了明细（版本号 +1）
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1.2", "kg"));

        RecipeDtos.ScaleApplyReq apply = new RecipeDtos.ScaleApplyReq();
        apply.setTargetBatchL(new BigDecimal("2000"));
        apply.setBaseVersion(pv.getBaseVersion());
        StalePreviewException ex = assertThrows(StalePreviewException.class,
                () -> recipeService.scaleApply(r.getId(), apply));
        assertTrue(ex.getMessage().contains("过期"), ex.getMessage());

        // 数据未被旧预览覆盖
        Views.RecipeItemView current = recipeService.item(r.getId(), "MASH_IN");
        assertEquals(0, new BigDecimal("1.200").compareTo(current.getQuantity()));
    }

    // 7. 整数倍率缩放严格往返：2000→1000 还原
    @Test
    void integerFactorScalesAndRestoresExactly() {
        String malt = ingredient("T-MALT-7", "麦芽7", "MALT", "kg").getCode();
        Recipe r = recipe("TR-007", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "kg"));

        scaleTo(r.getId(), "2000");
        Views.RecipeView up = recipeService.get(r.getId());
        Views.RecipeItemView upRow = up.getItems().get(0);
        assertEquals(0, new BigDecimal("2").compareTo(upRow.getQuantity()));

        scaleTo(r.getId(), "1000");
        Views.RecipeView back = recipeService.get(r.getId());
        Views.RecipeItemView backRow = back.getItems().get(0);
        assertEquals(0, new BigDecimal("1").compareTo(backRow.getQuantity()));
        assertEquals(0, new BigDecimal("1000").compareTo(backRow.getNormalizedQuantity()));
    }

    private void scaleTo(Long recipeId, String targetL) {
        Views.ScalePreviewView pv = recipeService.scalePreview(recipeId, new BigDecimal(targetL));
        RecipeDtos.ScaleApplyReq apply = new RecipeDtos.ScaleApplyReq();
        apply.setTargetBatchL(new BigDecimal(targetL));
        apply.setBaseVersion(pv.getBaseVersion());
        recipeService.scaleApply(recipeId, apply);
    }

    // 8. 批次建档固化快照；之后缩放配方，历史批次快照不变
    @Test
    void batchSnapshotImmutableWhenRecipeScalesLater() {
        String malt = ingredient("T-MALT-8", "麦芽8", "MALT", "kg").getCode();
        Recipe r = recipe("TR-008", new BigDecimal("1000"));
        recipeService.putItem(r.getId(), "MASH_IN", item(malt, "1", "kg"));

        BatchDtos.BatchSaveReq breq = new BatchDtos.BatchSaveReq();
        breq.setBatchNo("TB-MEAS-8");
        breq.setRecipeId(r.getId());
        breq.setBrewer("测试酿酒师");
        breq.setVolumeL(new BigDecimal("1000"));
        Views.BatchView batch = batchService.create(breq);
        assertNotNull(batch.getRecipeSnapshot());
        assertFalse(batch.getRecipeSnapshot().isMigrated());
        assertEquals(0, new BigDecimal("1000").compareTo(
                batch.getRecipeSnapshot().getItems().get(0).getNormalizedQuantity()));

        // 之后配方放大
        scaleTo(r.getId(), "3000");

        // 历史批次快照仍是创建当时的 1000g，且快照来源为建档
        List<BatchRecipeItem> rows = batchRecipeItemRepository.findAllByBatchIdOrderByIdAsc(batch.getId());
        assertEquals(1, rows.size());
        assertEquals("CREATED", rows.get(0).getSnapshotSource());
        assertEquals(0, new BigDecimal("1000").compareTo(rows.get(0).getNormalizedQuantity()));
        Views.BatchView again = batchService.get(batch.getId());
        Views.MeasureCell snapMass = again.getRecipeSnapshot().getTotals().stream()
                .filter(t -> "MASS".equals(t.getDimension())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1000").compareTo(snapMass.getAmount()));
    }

    // 9. assembler 对内存中"未知单位"行：保留原值、标记待处理、不参与合计
    @Test
    void unresolvedRowsFlaggedAndExcludedFromTotals() {
        Recipe r = recipe("TR-009", new BigDecimal("1000"));
        RecipeItem bad = new RecipeItem();
        bad.setIngredientCode("X-1");
        bad.setIngredientType("HOP");
        bad.setQuantity(new BigDecimal("0.300"));
        bad.setUnit("罐");
        r.getItems().put("DRYHOP_1", bad);
        int review = assembler.resolveAll(r);
        assertEquals(1, review);
        assertEquals("NEEDS_REVIEW", bad.getUnitStatus());
        assertNull(bad.getNormalizedQuantity());

        Views.RecipeView v = assembler.toView(r);
        assertEquals(1, v.getReviewCount());
        assertTrue(v.getTotals().isEmpty());
    }
}
