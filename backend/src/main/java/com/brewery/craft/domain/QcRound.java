package com.brewery.craft.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块六：批次质检轮次。
 *
 * 不可变原则：送检快照（批次状态、逐日温度/糖度、送检人、送检时间）写入后不允许修改；
 * 复检只能开新轮次。一轮的生命周期仅为 PENDING → PASS/REJECT，或 PASS → REVOKED，
 * 所有动作另在 {@link QcAction} 留流水。
 */
@Data
@Entity
@Table(name = "batch_qc_round")
public class QcRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批号冗余，便于留痕/列表 */
    @Column(name = "batch_no", nullable = false, length = 24)
    private String batchNo;

    /** 第几轮：1 = 首检，2+ = 复检 */
    @Column(name = "round_seq", nullable = false)
    private Integer roundSeq;

    /** PENDING 检验中 / PASS 放行 / REJECT 驳回 / REVOKED 放行已撤销 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 送检瞬间的批次状态快照 */
    @Column(name = "batch_status_snapshot", nullable = false, length = 16)
    private String batchStatusSnapshot;

    /** 检验酒精度（下结论时钉死） */
    @Column(name = "abv", precision = 4, scale = 2)
    private BigDecimal abv;

    /** 感官结论（下结论时钉死） */
    @Column(name = "sensory", length = 256)
    private String sensory;

    /** 通过/驳回原因 */
    @Column(name = "conclusion_note", length = 512)
    private String conclusionNote;

    @Column(name = "submitted_by", nullable = false, length = 32)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "decided_by", length = 32)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "revoked_by", length = 32)
    private String revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 512)
    private String revokeReason;

    /** 送检幂等令牌：重复点击/网络重试同一 token 只能开出一轮 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

    /** 乐观锁：防止基于过期详情并发提交结论/撤销 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 送检时的逐日温度/糖度【快照】，只随本轮 INSERT 写入，后续不可改 */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "batch_qc_reading", joinColumns = @JoinColumn(name = "round_id"))
    @AttributeOverrides({
            @AttributeOverride(name = "dayKey", column = @Column(name = "day_key", length = 16)),
            @AttributeOverride(name = "temp", column = @Column(name = "temp_value", precision = 5, scale = 2)),
            @AttributeOverride(name = "gravity", column = @Column(name = "gravity_value", precision = 5, scale = 2))
    })
    @OrderColumn(name = "list_index")
    private List<QcReading> readings = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
