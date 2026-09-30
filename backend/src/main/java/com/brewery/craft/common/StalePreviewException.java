package com.brewery.craft.common;

/**
 * 缩放预览过期：预览生成后配方明细/基准批量被其他人改过，
 * 旧预览对应的配方版本已不是当前版本，拒绝把旧结果覆盖到新数据上（HTTP 409）。
 */
public class StalePreviewException extends BizException {

    public StalePreviewException(String message) {
        super(message);
    }
}
