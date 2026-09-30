package com.brewery.craft.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块七（发酵罐维护窗口与在制批次迁罐）入参。 */
public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    @Data
    public static class WindowSaveReq {

        @NotBlank(message = "发酵罐不能为空")
        private String fermenterNo;

        /** PLANNED / EMERGENCY，缺省 PLANNED */
        private String maintenanceType;

        @NotNull(message = "开始时间不能为空")
        private LocalDateTime startAt;

        @NotNull(message = "预计恢复时间不能为空")
        private LocalDateTime expectedRecoverAt;

        @NotBlank(message = "维护原因不能为空")
        private String reason;

        /** 影响范围说明 */
        private String impactScope;

        /** 登记/操作人 */
        @NotBlank(message = "登记人不能为空")
        private String operator;
    }

    @Data
    public static class WindowAdjustReq {

        /** 必带：页面详情版本，期间被别人改过则 409 */
        @NotNull(message = "窗口版本不能为空")
        private Integer version;

        private LocalDateTime startAt;

        private LocalDateTime expectedRecoverAt;

        private String reason;

        private String impactScope;

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    @Data
    public static class WindowActionReq {

        @NotNull(message = "窗口版本不能为空")
        private Integer version;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 提前结束/取消的原因 */
        private String reason;

        /** 提前结束时可选的实际结束时间 */
        private LocalDateTime finishedAt;
    }

    @Data
    public static class BookingSaveReq {

        @NotBlank(message = "发酵罐不能为空")
        private String tankNo;

        @NotBlank(message = "批次不能为空")
        private String batchNo;

        @NotNull(message = "计划入罐时间不能为空")
        private LocalDateTime plannedEnterAt;

        private LocalDateTime plannedExitAt;

        @NotBlank(message = "登记人不能为空")
        private String operator;
    }

    @Data
    public static class BookingDelayReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 手工延期到的入罐时间（为空则取维护窗口预计恢复时间） */
        private LocalDateTime newEnterAt;

        private String reason;
    }

    @Data
    public static class PlanGenerateReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 可选：覆盖预计迁移时间，默认维护开始前一小段 */
        private LocalDateTime plannedMoveAt;

        /** 可选：强制指定目标罐（仍要通过全部适配校验） */
        private Long preferredTargetTankId;
    }

    @Data
    public static class PlanConfirmReq {

        /** 必带：方案乐观锁版本，两人同时确认时只允许一条成功 */
        @NotNull(message = "方案版本不能为空")
        private Integer version;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 可选：确认时调整迁移时间（仍要与维护窗口不冲突） */
        private LocalDateTime plannedMoveAt;
    }

    @Data
    public static class FaultInjectReq {

        /** RESERVE_TARGET / DETACH_SOURCE / ATTACH_TARGET / HISTORY / COMPLETE */
        @NotBlank(message = "故障步骤不能为空")
        private String failStep;

        /** 方案单号：为空表示下一次执行任意方案时生效 */
        private String planNo;
    }
}
