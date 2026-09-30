package com.brewery.craft.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块七（发酵罐维护窗口与迁罐）入参。 */
public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    @Data
    public static class MaintenanceSaveReq {

        @NotBlank(message = "罐号不能为空")
        private String tankNo;

        /** PLANNED 计划维护 / EMERGENCY 突发停机，缺省 PLANNED */
        private String maintType;

        @NotNull(message = "开始时间不能为空")
        private LocalDateTime startAt;

        @NotNull(message = "预计恢复时间不能为空")
        private LocalDateTime expectedRecoverAt;

        @NotBlank(message = "停机原因不能为空")
        private String reason;

        /** 影响范围 */
        private String impactScope;

        @NotBlank(message = "登记人不能为空")
        private String operator;
    }

    /** 改期/延期（计划中窗口可调开始与恢复时间；已开始窗口只能改恢复时间=延期） */
    @Data
    public static class MaintenanceAdjustReq {

        /** 计划中窗口允许改开始时间；已开始窗口传开始时间会被拒 */
        private LocalDateTime startAt;

        @NotNull(message = "新的预计恢复时间不能为空")
        private LocalDateTime expectedRecoverAt;

        @NotBlank(message = "变更原因不能为空")
        private String reason;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 乐观锁版本（打开详情时带回） */
        @NotNull(message = "缺少版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    @Data
    public static class MaintenanceCancelReq {

        @NotBlank(message = "取消原因不能为空")
        private String reason;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        @NotNull(message = "缺少版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    /** 开始 / 提前结束维护 */
    @Data
    public static class MaintenanceActionReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 提前结束时的说明/原因 */
        private String reason;

        @NotNull(message = "缺少版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    /** 给未来待入罐批次登记计划罐号/计划入罐时间（时间线排程用） */
    @Data
    public static class PlannedEnterReq {

        @NotBlank(message = "批号不能为空")
        private String batchNo;

        private String plannedTankNo;

        private LocalDateTime plannedEnterAt;
    }

    /** 迁罐预览（不落库）：给定维护窗口，列出每个受影响在制批次的可选目标罐与不适配原因 */
    @Data
    public static class TransferProposeReq {

        /** 不传 targetTankNo 时由系统按适配度自动选罐；指定时必须通过全部适配校验 */
        private String targetTankNo;

        /** 不传时取维护开始时间作为预计迁移时间 */
        private LocalDateTime plannedMoveAt;

        @NotBlank(message = "提案人不能为空")
        private String operator;
    }

    /** 确认迁罐：必须带回方案 id 与方案版本/条件指纹做过期校验 */
    @Data
    public static class TransferConfirmReq {

        @NotNull(message = "缺少迁罐方案 id")
        private Long planId;

        @NotBlank(message = "确认人不能为空")
        private String operator;

        @NotNull(message = "缺少方案版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    /** 迁移中途失败：上报失败步骤与原因，系统回退已完成动作并把方案退回待处理 */
    @Data
    public static class TransferFailReq {

        @NotNull(message = "缺少失败步骤序号")
        private Integer failedStep;

        @NotBlank(message = "失败原因不能为空")
        private String reason;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        @NotNull(message = "缺少方案版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    /** 失败后继续处理：从待处理重新尝试执行 */
    @Data
    public static class TransferResumeReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        @NotNull(message = "缺少方案版本号，请刷新后重试")
        private Integer expectedVersion;
    }

    /** 取消待处理方案 */
    @Data
    public static class TransferCancelReq {

        @NotBlank(message = "取消原因不能为空")
        private String reason;

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }
}
