package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.RecipeItem;
import com.brewery.craft.dto.RecipeDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.measure.ResolvedQuantity;
import com.brewery.craft.measure.UnitDef;
import com.brewery.craft.repository.IngredientRepository;
import com.brewery.craft.repository.RecipeRepository;

/**
 * 模块三：配方与原料用量。
 *
 * 用量明细走 {@code Recipe#getItems()} 这张 {@code @OneToMany + @MapKeyColumn} 的 Map，
 * 这里集中实现 Map 的增（put）删（remove）改（拿到 entity 改字段）查（按 key 取）。
 *
 * 计量统一：写入即通过 {@link RecipeAssembler} 解析出规范化数量，
 * 占比/阶段小计/合计/缩放全部以规范化值为准（同维度先换算再汇总，质量与体积不相加）。
 */
@Service
@Transactional(readOnly = true)
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final RecipeAssembler assembler;

    public RecipeService(RecipeRepository recipeRepository,
                         IngredientRepository ingredientRepository,
                         RecipeAssembler assembler) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
        this.assembler = assembler;
    }

    public List<Views.RecipeView> list() {
        List<Views.RecipeView> list = new ArrayList<>();
        for (Recipe r : recipeRepository.findAllByOrderByCodeAsc()) {
            list.add(assembler.toView(r));
        }
        return list;
    }

    public Views.RecipeView get(Long id) {
        return assembler.toView(load(id));
    }

    @Transactional
    public Views.RecipeView create(RecipeDtos.RecipeSaveReq req) {
        if (recipeRepository.existsByCode(req.getCode())) {
            throw new BizException("配方编码 " + req.getCode() + " 已存在");
        }
        if (Views.styleName(req.getStyle()).equals(req.getStyle())) {
            throw new BizException("啤酒风格取值非法：" + req.getStyle());
        }
        Recipe r = new Recipe();
        r.setCode(req.getCode());
        r.setName(req.getName());
        r.setStyle(req.getStyle());
        r.setTargetOg(req.getTargetOg());
        r.setTargetAbv(req.getTargetAbv());
        r.setTargetIbu(req.getTargetIbu());
        r.setTargetEbc(req.getTargetEbc());
        r.setProcessNote(req.getProcessNote());
        r.setBaseBatchL(normalizeBase(req.getBaseBatchL(), RecipeAssembler.DEFAULT_BASE_BATCH_L));
        r.setUpdatedAt(LocalDateTime.now());
        return assembler.toView(recipeRepository.save(r));
    }

    /** 更新基准批量（缩放比例分母） */
    @Transactional
    public Views.RecipeView updateBaseBatch(Long id, BigDecimal baseBatchL) {
        Recipe r = load(id);
        r.setBaseBatchL(normalizeBase(baseBatchL, RecipeAssembler.DEFAULT_BASE_BATCH_L));
        r.setUpdatedAt(LocalDateTime.now());
        return assembler.toView(recipeRepository.save(r));
    }

    private BigDecimal normalizeBase(BigDecimal v, BigDecimal fallback) {
        if (v == null || v.signum() <= 0) {
            return fallback;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 【Map 查询】整张 Map：stage → 用量明细 */
    public List<Views.RecipeItemView> items(Long id) {
        return get(id).getItems();
    }

    /** 【Map 查询】按 key 查某一条 */
    public Views.RecipeItemView item(Long id, String stage) {
        Recipe r = load(id);
        RecipeItem item = r.getItems().get(stage);
        if (item == null) {
            throw new BizException("阶段 " + Views.stageName(stage) + " 还没有投放记录");
        }
        return Views.RecipeItemView.of(stage, item);
    }

    /**
     * 【Map 新增 / 修改】key 已存在 → 改用量；不存在 → put 一条新的。
     * 写入时立即解析单位并落规范化列；空单位按原料档案/类别推导，无法识别的非空单位直接拒绝。
     */
    @Transactional
    public Views.RecipeItemView putItem(Long id, String stage, RecipeDtos.ItemSaveReq req) {
        Recipe r = load(id);
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("用量必须大于 0");
        }
        Ingredient ing = ingredientRepository.findByCode(req.getIngredientCode())
                .orElseThrow(() -> new BizException("原料 " + req.getIngredientCode() + " 不在库存档案中"));

        // 显式单位校验：只接受受支持单位（含历史别名），从源头避免再产生待处理行
        if (req.getUnit() != null && !req.getUnit().isBlank() && UnitDef.parse(req.getUnit()) == null) {
            throw new BizException("不支持的单位「" + req.getUnit().trim()
                    + "」；质量请用 kg/g，体积请用 L/mL，包装请用 pkg；留空则按原料档案单位推导");
        }

        RecipeItem item = r.getItems().get(stage);
        boolean isNew = item == null;
        if (isNew) {
            item = new RecipeItem();
        }
        item.setIngredientCode(ing.getCode());
        item.setIngredientName(ing.getName());
        item.setIngredientType(ing.getType());
        item.setQuantity(req.getQuantity().setScale(3, RoundingMode.HALF_UP));
        // 空单位落 NULL（显示层回退档案单位），由解析器按档案/类别推导并打 INFERRED 标记；
        // 非空单位已过前面的 parse 校验，这里落规范码（旧写法只在历史数据里保留原样）
        boolean blankUnit = req.getUnit() == null || req.getUnit().isBlank();
        item.setUnit(blankUnit ? null : UnitDef.parse(req.getUnit()).getCode());
        item.setNote(req.getNote());
        ResolvedQuantity rq = assembler.resolveAndStamp(item, ing.getUnit());
        // 新写入路径不允许出现待处理（理论上前面的校验已挡住，双保险）
        if (rq.needsReview()) {
            throw new BizException("该用量单位无法可靠换算（" + rq.resolutionNote() + "），请明确单位后再保存");
        }
        if (isNew) {
            r.getItems().put(stage, item);
        }
        r.setUpdatedAt(LocalDateTime.now());
        recipeRepository.save(r);
        return Views.RecipeItemView.of(stage, item);
    }

    /** 【Map 删除】按 key 移除一条用量明细（orphanRemoval 会把行删掉） */
    @Transactional
    public Views.RecipeView removeItem(Long id, String stage) {
        Recipe r = load(id);
        RecipeItem removed = r.getItems().remove(stage);
        if (removed == null) {
            throw new BizException("阶段 " + Views.stageName(stage) + " 没有投放记录，无需删除");
        }
        r.setUpdatedAt(LocalDateTime.now());
        recipeRepository.save(r);
        return assembler.toView(r);
    }

    /** 环形图用的原料构成（主维度，与详情/列表同源） */
    public List<Views.CompositionSlice> composition(Long id) {
        return assembler.composition(load(id));
    }

    // ---------------- 配方缩放（预览 → 确认，确认带版本防过期） ----------------

    /** 预览缩放：返回 原值/换算值/调整后值/余差去向，不落库 */
    public Views.ScalePreviewView scalePreview(Long id, BigDecimal targetBatchL) {
        return assembler.preview(load(id), targetBatchL);
    }

    /** 确认应用：必须基于预览对应的配方版本，期间改过明细则 409 过期 */
    @Transactional
    public Views.RecipeView scaleApply(Long id, RecipeDtos.ScaleApplyReq req) {
        Recipe r = load(id);
        assembler.apply(r, req.getBaseVersion(), req.getTargetBatchL());
        r.setUpdatedAt(LocalDateTime.now());
        Recipe saved = recipeRepository.save(r);
        return assembler.toView(saved);
    }

    /** 所有可用投放阶段（UI 下拉用） */
    public List<Map<String, String>> stages() {
        List<Map<String, String>> list = new ArrayList<>();
        for (String key : Views.STAGE_ORDER) {
            list.add(Map.of("key", key, "name", Views.stageName(key)));
        }
        return list;
    }

    private Recipe load(Long id) {
        return recipeRepository.findById(id).orElseThrow(() -> new BizException("配方不存在"));
    }
}
