package com.brewery.craft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块四（原料库存）入参。 */
public final class IngredientDtos {

    private IngredientDtos() {
    }

    @Data
    public static class IngredientSaveReq {

        @NotBlank(message = "原料编号不能为空")
        private String code;

        @NotBlank(message = "原料名称不能为空")
        private String name;

        @NotBlank(message = "原料类型不能为空")
        private String type;

        private String unit;

        private BigDecimal stock;

        private BigDecimal safetyStock;

        private BigDecimal unitPrice;

        private String supplier;

        private LocalDate shelfLifeTo;
    }

    /** 库存变动：IN 入库 / OUT 领用扣减 */
    @Data
    public static class StockReq {

        private BigDecimal quantity;

        /** IN / OUT，缺省 IN */
        private String mode;
    }
}
