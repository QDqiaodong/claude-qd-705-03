package com.brewery.craft.domain;

import java.time.LocalDateTime;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 发酵罐维护窗口（模块七）。
 *
 * <p>设备负责人为罐体登记的计划维护 / 突发停机。窗口本身可以调整、取消、提前结束、延期，
 * 但已经开始（{@link #status} = IN_PROGRESS）的维护不能再直接删除或取消；
 * 已经发生的迁罐更不会因窗口变化被改写——后续所有变化都以 {@link MaintenanceEvent}
 * 追加留痕，旧记录永远保留。
 *
 * <p>状态链：SCHEDULED 计划中 → IN_PROGRESS 进行中 → FINISHED 已完成；
 *           SCHEDULED → CANCELLED 已取消（仅计划中的窗口可取消）。
 */
@Data
@Entity
@Table(name = "fermenter_maintenance")
public class MaintenanceWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 维护单号，如 MW-2026-001 */
    @Column(name = "window_no", nullable = false, length = 24)
    private String windowNo;

    /** 受影响发酵罐号 */
    @Column(name = "fermenter_no", nullable = false, length = 16)
    private String fermenterNo;

    /** PLANNED 计划维护 / EMERGENCY 突发停机 */
    @Column(name = "maintenance_type", nullable = false, length = 16)
    private String maintenanceType;

    /** SCHEDULED/IN_PROGRESS/FINISHED/CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /** 预计恢复时间（窗口延长时更新原值，但事件流保留调整历史） */
    @Column(name = "expected_recover_at", nullable = false)
    private LocalDateTime expectedRecoverAt;

    /** 实际结束时间（提前结束/完工时钉死） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 维护原因（机械故障/温控校准/CIP 清洗/泄漏抢修…） */
    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    /** 影响范围说明（该罐不可用 / 关联批次需迁罐或延期 / 上下游排程影响） */
    @Column(name = "impact_scope", length = 512)
    private String impactScope;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁：窗口被别人改过（延期/提前结束/取消）时，旧页面提交要 409 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 只追加的操作历史：登记/调整/取消/开工/完工，永不原地覆盖 */
    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<MaintenanceEvent> events = new ArrayList<>();
}
