package com.brewery.craft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块二（啤酒批次）入参。 */
public final class BatchDtos {

    private BatchDtos() {
    }

    @Data
    public static class BatchSaveReq {

        @NotBlank(message = "批号不能为空")
        private String batchNo;

        /** 配方 id */
        private Long recipeId;

        private String fermenterNo;

        @NotBlank(message = "酿酒师不能为空")
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

        /** 缺省 PLAN */
        private String status;
    }

    /** 记录一次每日测温 / 测糖，写进 @ElementCollection Map */
    @Data
    public static class ReadingReq {

        private String dayKey;

        private BigDecimal temp;

        private BigDecimal gravity;
    }
}
