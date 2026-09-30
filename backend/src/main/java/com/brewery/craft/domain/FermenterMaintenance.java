package com.brewery.craft.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块七：发酵罐维护窗口。
 *
 * 生命周期：SCHEDULED（计划中）→ IN_PROGRESS（已开始）→ FINISHED（已结束）；
 * 计划中的窗口可被 CANCELLED（取消）。任何后续变化（改期/延期/提前结束/取消）都不删除原记录，
 * 而是原地更新当前字段并在 {@link MaintenanceEvent} 追加一条带原因与操作人的留痕。
 *
 * 不可删改红线：已开始（IN_PROGRESS/FINISHED）的窗口不能取消、不能删除，只能提前结束或延期；
 * 已经执行的迁罐历史也不会因为窗口变化而被改写。
 */
@Data
@Entity
@Table(name = "fermenter_maintenance")
public class FermenterMaintenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 维护罐号 */
    @Column(name = "tank_no", nullable = false, length = 16)
    private String tankNo;

    /** PLANNED 计划维护 / EMERGENCY 突发停机 */
    @Column(name = "maint_type", nullable = false, length = 16)
    private String maintType;

    /** SCHEDULED 计划中 / IN_PROGRESS 已开始 / FINISHED 已结束 / CANCELLED 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 计划开始时间（当前生效值；原始登记值见首条 MAINTAIN_REGISTER 事件） */
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /** 预计恢复时间（当前生效值，可改期/延期） */
    @Column(name = "expected_recover_at", nullable = false)
    private LocalDateTime expectedRecoverAt;

    /** 实际恢复时间（提前结束/到期结束时钉死） */
    @Column(name = "actual_recover_at")
    private LocalDateTime actualRecoverAt;

    /** 停机原因 */
    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    /** 影响范围说明（如：影响在罐批次、待入罐计划） */
    @Column(name = "impact_scope", length = 512)
    private String impactScope;

    /** 登记人 */
    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁：A 打开窗口准备改期时，B 先改了，A 提交必须报过期 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
