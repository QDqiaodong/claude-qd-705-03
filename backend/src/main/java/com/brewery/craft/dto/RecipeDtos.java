package com.brewery.craft.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 模块三（配方与原料用量）入参。 */
public final class RecipeDtos {

    private RecipeDtos() {
    }

    @Data
    public static class RecipeSaveReq {

        @NotBlank(message = "配方编码不能为空")
        private String code;

        @NotBlank(message = "配方名不能为空")
        private String name;

        @NotBlank(message = "风格不能为空")
        private String style;

        private BigDecimal targetOg;

        private BigDecimal targetAbv;

        private BigDecimal targetIbu;

        private BigDecimal targetEbc;

        /** 基准批量（L）：缩放比例分母，留空按 1000L 兜底 */
        private BigDecimal baseBatchL;

        private String processNote;
    }

    /** 单独更新基准批量（L） */
    @Data
    public static class BaseBatchReq {

        private BigDecimal baseBatchL;
    }

    /**
     * 新增或修改「某一个投放阶段」的用量。
     * Map 的 key 走路径变量 {stage}，body 只带这条明细本身。
     */
    @Data
    public static class ItemSaveReq {

        @NotBlank(message = "原料编码不能为空")
        private String ingredientCode;

        private String ingredientName;

        private String ingredientType;

        private BigDecimal quantity;

        /**
         * 显示单位，酿酒师习惯单位。允许 kg/g/L/mL/pkg 及常见旧写法（自动归一）；
         * 留空则按原料档案单位/类别推导。无法识别的非空单位会被拒绝（新建时不允许产生待处理行）。
         */
        private String unit;

        private String note;
    }

    /** 缩放预览请求：按目标批量成比例换算，不落库 */
    @Data
    public static class ScalePreviewReq {

        /** 目标批量（L），必须为正 */
        @NotNull(message = "目标批量不能为空")
        @Positive(message = "目标批量必须大于 0")
        private BigDecimal targetBatchL;
    }

    /**
     * 应用缩放：必须回传预览时的配方版本与目标批量。
     * 期间配方被改过（版本不一致）→ 409 预览过期，拒绝覆盖。
     */
    @Data
    public static class ScaleApplyReq {

        @NotNull(message = "目标批量不能为空")
        @Positive(message = "目标批量必须大于 0")
        private BigDecimal targetBatchL;

        /** 预览响应里的 baseVersion，必填 */
        @NotNull(message = "缺少预览版本号，请先发起缩放预览")
        private Integer baseVersion;
    }
}
