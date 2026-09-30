package com.brewery.craft.common;

/**
 * 迁罐方案过期（HTTP 409）：
 * 方案生成之后，维护窗口时间、目标罐状态、批次占罐等条件被别人改动，
 * 或同一目标罐已被另一条迁移抢占——旧方案一律拒绝确认，必须按最新条件重新计算。
 */
public class StalePlanException extends BizException {

    public StalePlanException(String message) {
        super(message);
    }
}
