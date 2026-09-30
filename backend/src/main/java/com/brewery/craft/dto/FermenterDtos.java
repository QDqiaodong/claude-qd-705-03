package com.brewery.craft.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块一（发酵罐）入参。 */
public final class FermenterDtos {

    private FermenterDtos() {
    }

    @Data
    public static class FermenterSaveReq {

        @NotBlank(message = "罐号不能为空")
        private String tankNo;

        private BigDecimal capacityL;

        private String material;

        private BigDecimal tempMin;

        private BigDecimal tempMax;

        private String cooling;

        private String status;

        private String currentBatchNo;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "目标状态不能为空")
        private String status;

        /** 可选：连同批次号一起变更 */
        private String batchNo;
    }

    @Data
    public static class AssignReq {

        private String batchNo;
    }
}
