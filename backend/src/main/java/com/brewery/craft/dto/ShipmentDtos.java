package com.brewery.craft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块五（灌装与出货）入参。 */
public final class ShipmentDtos {

    private ShipmentDtos() {
    }

    @Data
    public static class BottlingSaveReq {

        @NotBlank(message = "灌装单号不能为空")
        private String bottlingNo;

        @NotBlank(message = "批次号不能为空")
        private String batchNo;

        private String packageForm;

        private Integer quantity;

        private LocalDate bottleDate;

        private BigDecimal lossL;
    }

    @Data
    public static class ShipmentSaveReq {

        @NotBlank(message = "出货单号不能为空")
        private String shipmentNo;

        @NotBlank(message = "客户不能为空")
        private String customer;

        @NotBlank(message = "批次号不能为空")
        private String batchNo;

        private String packageForm;

        private Integer quantity;

        @NotNull(message = "单价不能为空")
        private BigDecimal unitPrice;

        private LocalDate shipDate;

        /** 缺省 PENDING */
        private String status;
    }
}
