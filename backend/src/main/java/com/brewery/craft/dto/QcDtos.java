package com.brewery.craft.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块六（批次质检放行）入参。 */
public final class QcDtos {

    private QcDtos() {
    }

    /**
     * 发起一轮送检。
     * 送检内容（批次状态、逐日温度/糖度）在服务端从批次快照；
     * clientToken 用于重复点击 / 网络重试幂等。
     */
    @Data
    public static class OpenRoundReq {

        @NotBlank(message = "送检操作人不能为空")
        private String operator;

        /** 幂等令牌：前端发起时生成（UUID）。同 token 重试不会产生第二轮 */
        private String clientToken;

        /** 备注（不强制） */
        private String note;

        /** 允许送检人手工补充测量摘要（可选，缺省则取批次当前曲线快照） */
        private List<Reading> readings;
    }

    @Data
    public static class Reading {
        private String dayKey;
        private BigDecimal temp;
        private BigDecimal gravity;
    }

    /** 下结论：通过 / 驳回（驳回必须填原因），酒精度与感官结论在此时钉死 */
    @Data
    public static class DecideReq {

        /** PASS / REJECT */
        @NotBlank(message = "结论不能为空")
        private String decision;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        private BigDecimal abv;

        @NotBlank(message = "感官结论不能为空")
        private String sensory;

        /** 通过备注 / 驳回原因（驳回必填） */
        private String reason;

        /** 乐观锁：基于打开详情时的版本提交，防止过期详情覆盖新结论 */
        private Integer expectedVersion;
    }

    /** 撤销放行（必须填原因） */
    @Data
    public static class RevokeReq {

        @NotBlank(message = "撤销操作人不能为空")
        private String operator;

        @NotBlank(message = "撤销原因不能为空")
        private String reason;

        private Integer expectedVersion;
    }
}
