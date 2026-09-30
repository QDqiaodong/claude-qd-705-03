package com.brewery.craft.common;

/**
 * 模块七：迁罐方案过期（HTTP 409）。
 * 方案生成后维护窗口被改期/取消、目标罐状态变化或被其他批次抢占、批次已离开原罐等，
 * 旧方案对应的条件快照不再是当前条件，拒绝执行，必须重新计算方案后再确认。
 */
public class StaleTransferException extends BizException {

    public StaleTransferException(String message) {
        super(message);
    }
}
