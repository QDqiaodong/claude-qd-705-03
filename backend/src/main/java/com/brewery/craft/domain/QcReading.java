package com.brewery.craft.domain;

import java.math.BigDecimal;

import jakarta.persistence.Embeddable;
import lombok.Data;

/**
 * 质检轮次内的单条逐日测量【快照】。
 * 送检瞬间从批次曲线复制进轮次，之后批次曲线再改也不会影响历史轮次。
 * 列名通过 {@code QcRound.readings} 上的 @AttributeOverride 统一映射。
 */
@Data
@Embeddable
public class QcReading {

    /** D1/D2… */
    private String dayKey;

    private BigDecimal temp;

    private BigDecimal gravity;
}
