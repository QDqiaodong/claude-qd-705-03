package com.brewery.craft.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.common.StaleTransferException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.BatchTankHistory;
import com.brewery.craft.domain.Fermenter;
import com.brewery.craft.domain.FermenterMaintenance;
import com.brewery.craft.domain.MaintenanceEvent;
import com.brewery.craft.domain.TankTransferEvent;
import com.brewery.craft.domain.TankTransferPlan;
import com.brewery.craft.domain.TankTransferStep;
import com.brewery.craft.domain.TransferReservation;
import com.brewery.craft.dto.MaintenanceDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.BatchTankHistoryRepository;
import com.brewery.craft.repository.FermenterMaintenanceRepository;
import com.brewery.craft.repository.FermenterRepository;
import com.brewery.craft.repository.MaintenanceEventRepository;
import com.brewery.craft.repository.TankTransferEventRepository;
import com.brewery.craft.repository.TankTransferPlanRepository;
import com.brewery.craft.repository.TankTransferStepRepository;
import com.brewery.craft.repository.TransferReservationRepository;

/**
 * 模块七：发酵罐维护窗口 + 在制批次迁罐。
 *
 * 设计红线：
 * <ul>
 *   <li>不是给罐加个“维护中”状态完事：维护窗口是独立的时间区间实体，与占罐批次/待入罐批次
 *       在同一条时间线上计算，结论分 无冲突 / 需要迁罐 / 必须延期 / 无法处理；</li>
 *   <li>不是前端筛掉不可用罐：目标罐四条硬条件（空闲/容量够/温区兼容/不在维护窗口）+ 预留互斥
 *       全部在服务端确认事务内重算，悲观锁串行化同一罐/同一批次上的并发；</li>
 *   <li>不是直接改批次罐号：迁罐分步执行、失败回退待处理，批次曲线/阶段时间不动，历史罐号逐段留痕；</li>
 *   <li>历史不可删：窗口与方案都没有 DELETE 接口；改期/取消/过期/失败全部追加记录。</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class MaintenanceService {

    /** 未来待入罐批次按 14 天发酵周期估算占罐时段（PLAN 批次还没有实际成熟日） */
    private static final int PLANNED_OCCUPY_DAYS = 14;
    /** 预计迁移时间默认提前维护开始的小时数（酒必须在停机前搬走） */
    private static final int MOVE_BEFORE_HOURS = 2;
    /** 目标罐预留提前量 */
    private static final int RESERVE_BUFFER_HOURS = 1;

    private static final List<String> SEVERITY = List.of("NO_CONFLICT", "MUST_DELAY", "NEED_TRANSFER", "UNRESOLVABLE");

    private final FermenterMaintenanceRepository maintenanceRepository;
    private final MaintenanceEventRepository maintenanceEventRepository;
    private final TankTransferPlanRepository planRepository;
    private final TankTransferStepRepository stepRepository;
    private final TankTransferEventRepository transferEventRepository;
    private final TransferReservationRepository reservationRepository;
    private final BatchTankHistoryRepository historyRepository;
    private final FermenterRepository fermenterRepository;
    private final BatchRepository batchRepository;

    public MaintenanceService(FermenterMaintenanceRepository maintenanceRepository,
                              MaintenanceEventRepository maintenanceEventRepository,
                              TankTransferPlanRepository planRepository,
                              TankTransferStepRepository stepRepository,
                              TankTransferEventRepository transferEventRepository,
                              TransferReservationRepository reservationRepository,
                              BatchTankHistoryRepository historyRepository,
                              FermenterRepository fermenterRepository,
                              BatchRepository batchRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.maintenanceEventRepository = maintenanceEventRepository;
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.transferEventRepository = transferEventRepository;
        this.reservationRepository = reservationRepository;
        this.historyRepository = historyRepository;
        this.fermenterRepository = fermenterRepository;
        this.batchRepository = batchRepository;
    }

    // =====================================================================
    // 维护窗口登记 / 调整 / 取消 / 开始 / 提前结束
    // =====================================================================

    @Transactional
    public Views.MaintenanceView register(MaintenanceDtos.MaintenanceSaveReq req) {
        Fermenter tank = fermenterRepository.findByTankNo(req.getTankNo())
                .orElseThrow(() -> new BizException("发酵罐 " + req.getTankNo() + " 不存在"));
        LocalDateTime now = LocalDateTime.now();
        if (req.getExpectedRecoverAt().isBefore(req.getStartAt())
                || req.getExpectedRecoverAt().equals(req.getStartAt())) {
            throw new BizException("预计恢复时间必须晚于开始时间");
        }
        String type = req.getMaintType() == null || req.getMaintType().isBlank()
                ? "PLANNED" : req.getMaintType();
        if (!"PLANNED".equals(type) && !"EMERGENCY".equals(type)) {
            throw new BizException("维护类型取值非法，只能是 PLANNED（计划维护）或 EMERGENCY（突发停机）");
        }
        // 同罐时段重叠的生效窗口不允许重复登记（一条时间线一个区间）
        for (FermenterMaintenance m : maintenanceRepository.findActiveByTankNo(tank.getTankNo())) {
            if (overlap(req.getStartAt(), req.getExpectedRecoverAt(), m.getStartAt(), m.getExpectedRecoverAt())) {
                throw new BizException("罐 " + tank.getTankNo() + " 在该时段已有"
                        + Views.maintTypeName(m.getMaintType()) + "窗口（"
                        + fmt(m.getStartAt()) + " ~ " + fmt(m.getExpectedRecoverAt()) + "），不能重复登记");
            }
        }

        FermenterMaintenance m = new FermenterMaintenance();
        m.setTankNo(tank.getTankNo());
        m.setMaintType(type);
        // 登记的开始时间已过（突发停机事后补录）→ 直接进入维护中
        m.setStatus(req.getStartAt().isAfter(now) ? "SCHEDULED" : "IN_PROGRESS");
        m.setStartAt(req.getStartAt());
        m.setExpectedRecoverAt(req.getExpectedRecoverAt());
        m.setReason(req.getReason());
        m.setImpactScope(req.getImpactScope());
        m.setCreatedBy(req.getOperator());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        FermenterMaintenance saved = maintenanceRepository.saveAndFlush(m);
        addMaintenanceEvent(saved, "REGISTER", null, null, null, null, req.getReason(), req.getOperator(), now);
        return toMaintenanceView(saved);
    }

    /** 计划中窗口可改期（开始+恢复）；已开始窗口只能延期（恢复时间）。改期后旧迁罐方案全部过期。 */
    @Transactional
    public Views.MaintenanceView adjust(Long id, MaintenanceDtos.MaintenanceAdjustReq req) {
        FermenterMaintenance m = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        checkVersion(m.getVersion(), req.getExpectedVersion());
        if ("CANCELLED".equals(m.getStatus())) {
            throw new BizException("该维护窗口已取消，不能再调整；如需维护请重新登记");
        }
        if ("FINISHED".equals(m.getStatus())) {
            throw new BizException("该维护已结束，时间不能再改；后续变化请登记新窗口");
        }
        LocalDateTime newStart = req.getStartAt() == null ? m.getStartAt() : req.getStartAt();
        if ("IN_PROGRESS".equals(m.getStatus()) && req.getStartAt() != null
                && !req.getStartAt().equals(m.getStartAt())) {
            throw new BizException("维护已经开始，开始时间不能回改，只能把预计恢复时间延后（延期）");
        }
        if (!req.getExpectedRecoverAt().isAfter(newStart)) {
            throw new BizException("预计恢复时间必须晚于开始时间");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldStart = m.getStartAt();
        LocalDateTime oldRecover = m.getExpectedRecoverAt();
        boolean startChanged = !newStart.equals(oldStart);
        boolean recoverChanged = !req.getExpectedRecoverAt().equals(oldRecover);
        if (!startChanged && !recoverChanged) {
            throw new BizException("开始时间与预计恢复时间都没有变化，无需调整");
        }
        String eventType = "IN_PROGRESS".equals(m.getStatus()) ? "EXTEND" : "RESCHEDULE";

        m.setStartAt(newStart);
        m.setExpectedRecoverAt(req.getExpectedRecoverAt());
        m.setUpdatedAt(now);
        maintenanceRepository.saveAndFlush(m);
        addMaintenanceEvent(m, eventType, oldStart, newStart, oldRecover, req.getExpectedRecoverAt(),
                req.getReason(), req.getOperator(), now);
        // 窗口时间一变，所有待确认迁罐方案的条件指纹随即失效 —— 不允许拿旧时间窗的方案执行
        expirePendingPlans(m, "维护窗口已" + ("EXTEND".equals(eventType) ? "延期" : "改期")
                + "（" + fmt(oldStart) + "~" + fmt(oldRecover) + " → "
                + fmt(newStart) + "~" + fmt(req.getExpectedRecoverAt())
                + "），旧方案条件已过期，原因：" + req.getReason(), "系统");
        return toMaintenanceView(m);
    }

    /** 取消：仅计划中窗口可取消；已开始的维护不能删除/取消。挂起方案全部过期。 */
    @Transactional
    public Views.MaintenanceView cancel(Long id, MaintenanceDtos.MaintenanceCancelReq req) {
        FermenterMaintenance m = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        checkVersion(m.getVersion(), req.getExpectedVersion());
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new BizException("只有计划中的维护可以取消；维护"
                    + Views.maintStatusName(m.getStatus()) + "不能直接删除，只能提前结束或延期");
        }
        LocalDateTime now = LocalDateTime.now();
        m.setStatus("CANCELLED");
        m.setUpdatedAt(now);
        maintenanceRepository.saveAndFlush(m);
        addMaintenanceEvent(m, "CANCEL", null, null, null, null, req.getReason(), req.getOperator(), now);
        expirePendingPlans(m, "维护窗口已取消（原因：" + req.getReason() + "），迁罐方案作废", "系统");
        return toMaintenanceView(m);
    }

    /** 标记维护开始（现场停机）。 */
    @Transactional
    public Views.MaintenanceView markStarted(Long id, MaintenanceDtos.MaintenanceActionReq req) {
        FermenterMaintenance m = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        checkVersion(m.getVersion(), req.getExpectedVersion());
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new BizException("只有计划中的维护可以标记开始，当前为「"
                    + Views.maintStatusName(m.getStatus()) + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        m.setStatus("IN_PROGRESS");
        m.setUpdatedAt(now);
        maintenanceRepository.saveAndFlush(m);
        addMaintenanceEvent(m, "START", null, null, null, null, req.getReason(), req.getOperator(), now);
        return toMaintenanceView(m);
    }

    /** 提前结束：恢复时间钉死为当前时刻；未执行的迁罐方案不再需要，按新窗口排程过期。 */
    @Transactional
    public Views.MaintenanceView finishEarly(Long id, MaintenanceDtos.MaintenanceActionReq req) {
        FermenterMaintenance m = maintenanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        checkVersion(m.getVersion(), req.getExpectedVersion());
        if (!"IN_PROGRESS".equals(m.getStatus())) {
            throw new BizException("只有维护中的窗口可以提前结束，当前为「"
                    + Views.maintStatusName(m.getStatus()) + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        m.setStatus("FINISHED");
        m.setActualRecoverAt(now);
        m.setExpectedRecoverAt(now);
        m.setUpdatedAt(now);
        maintenanceRepository.saveAndFlush(m);
        addMaintenanceEvent(m, "FINISH_EARLY", null, null, null, now,
                req.getReason() == null ? "维护提前完成" : req.getReason(), req.getOperator(), now);
        expirePendingPlans(m, "维护提前于 " + fmt(now) + " 结束，已无需迁罐，方案按新窗口作废", "系统");
        return toMaintenanceView(m);
    }

    // =====================================================================
    // 查询：列表 / 维护日历 / 时间线（冲突清单）
    // =====================================================================

    public List<Views.MaintenanceView> list() {
        List<Views.MaintenanceView> list = new ArrayList<>();
        for (FermenterMaintenance m : maintenanceRepository.findAllByOrderByStartAtAscIdAsc()) {
            list.add(toMaintenanceView(m));
        }
        return list;
    }

    public Views.MaintenanceView get(Long id) {
        FermenterMaintenance m = maintenanceRepository.findById(id)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        return toMaintenanceView(m);
    }

    /** 维护日历：所有窗口（前端按时间范围过滤），附罐容/材质便于渲染 */
    public List<Views.MaintenanceView> calendar() {
        return list();
    }

    /**
     * 维护时间线：把窗口、当前占罐批次、未来待入罐批次摆到同一条时间线上，
     * 逐条给出 无冲突 / 需要迁罐 / 必须延期 / 无法处理，需要迁罐的附带全部候选罐评估。
     */
    public Views.MaintenanceTimelineView timeline(Long maintenanceId) {
        FermenterMaintenance m = maintenanceRepository.findById(maintenanceId)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        return buildTimeline(m);
    }

    public List<Views.TimelineItemView> conflictList() {
        List<Views.TimelineItemView> items = new ArrayList<>();
        for (FermenterMaintenance m : maintenanceRepository.findAllByOrderByStartAtAscIdAsc()) {
            if ("CANCELLED".equals(m.getStatus()) || "FINISHED".equals(m.getStatus())) {
                continue;
            }
            Views.MaintenanceTimelineView tl = buildTimeline(m);
            for (Views.TimelineItemView it : tl.getItems()) {
                if (!"NO_CONFLICT".equals(it.getResult())) {
                    items.add(it);
                }
            }
        }
        return items;
    }

    // =====================================================================
    // 未来待入罐批次排程
    // =====================================================================

    @Transactional
    public void planEntry(MaintenanceDtos.PlannedEnterReq req) {
        Batch b = batchRepository.findByBatchNo(req.getBatchNo())
                .orElseThrow(() -> new BizException("批次 " + req.getBatchNo() + " 不存在"));
        if (b.getFermenterNo() != null && !b.getFermenterNo().isBlank()) {
            throw new BizException("批次 " + b.getBatchNo() + " 已在罐 " + b.getFermenterNo()
                    + " 中，待入罐排程只对未入罐批次生效");
        }
        if (req.getPlannedTankNo() != null && !req.getPlannedTankNo().isBlank()) {
            Fermenter t = fermenterRepository.findByTankNo(req.getPlannedTankNo())
                    .orElseThrow(() -> new BizException("发酵罐 " + req.getPlannedTankNo() + " 不存在"));
            b.setPlannedTankNo(t.getTankNo());
        } else {
            b.setPlannedTankNo(null);
        }
        b.setPlannedEnterAt(req.getPlannedEnterAt());
        b.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(b);
    }

    // =====================================================================
    // 迁罐方案：生成（预览落库）/ 确认 / 分步执行 / 失败回退 / 继续 / 取消
    // =====================================================================

    /**
     * 生成可确认的迁罐方案（迁罐预览落库为 PENDING）。
     * 同一批次只允许一条生效方案：旧的待确认方案会被标记过期；迁移进行中则拒绝新开。
     */
    @Transactional
    public Views.TransferPlanView propose(Long maintenanceId, Long batchId, MaintenanceDtos.TransferProposeReq req) {
        FermenterMaintenance m = maintenanceRepository.findById(maintenanceId)
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        if ("CANCELLED".equals(m.getStatus()) || "FINISHED".equals(m.getStatus())) {
            throw new BizException("维护窗口已" + Views.maintStatusName(m.getStatus()) + "，不能再生成迁罐方案");
        }
        // 先锁批次：两人同时为同一批次出方案在此串行，保证同批次只剩一条生效方案
        Batch b = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BizException("批次不存在"));
        if (b.getFermenterNo() == null || !b.getFermenterNo().equals(m.getTankNo())) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前不在罐 " + m.getTankNo() + " 中，无需迁罐");
        }
        if (!List.of("MASH", "BOIL", "FERMENT", "CONDITION").contains(b.getStatus())) {
            throw new BizException("批次处于「" + Views.batchStatusName(b.getStatus())
                    + "」阶段，不是在制批次，不能迁罐");
        }
        for (TankTransferPlan open : planRepository.findOpenByBatchId(b.getId())) {
            if ("EXECUTING".equals(open.getStatus())) {
                throw new BizException("批次 " + b.getBatchNo() + " 的迁罐方案正在执行中（已完成到第 "
                        + open.getCompletedStep() + " 步），不能再开新方案");
            }
            expirePlan(open, "批次 " + b.getBatchNo() + " 已重新计算迁罐方案，旧待确认方案作废", req.getOperator());
        }

        Fermenter source = fermenterRepository.findByTankNo(m.getTankNo())
                .orElseThrow(() -> new BizException("原罐不存在"));
        LocalDateTime moveAt = req.getPlannedMoveAt() != null
                ? req.getPlannedMoveAt()
                : m.getStartAt().minusHours(MOVE_BEFORE_HOURS);
        if (!moveAt.isBefore(m.getStartAt())) {
            throw new BizException("预计迁移时间必须早于维护开始时间 " + fmt(m.getStartAt()));
        }
        LocalDateTime reserveFrom = moveAt.minusHours(RESERVE_BUFFER_HOURS);
        LocalDateTime reserveTo = m.getExpectedRecoverAt();

        // 评估全部候选罐
        List<Views.TargetCandidateView> candidates = evaluateCandidates(m, b, source, reserveFrom, reserveTo);
        Views.TargetCandidateView chosen;
        if (req.getTargetTankNo() != null && !req.getTargetTankNo().isBlank()) {
            chosen = candidates.stream()
                    .filter(c -> c.getTankNo().equals(req.getTargetTankNo()))
                    .findFirst()
                    .orElseThrow(() -> new BizException("目标罐 " + req.getTargetTankNo() + " 不存在"));
            if (!chosen.isSuitable()) {
                throw new BizException("目标罐 " + chosen.getTankNo() + " 不适配："
                        + String.join("；", chosen.getMismatchReasons()));
            }
        } else {
            chosen = candidates.stream().filter(Views.TargetCandidateView::isSuitable)
                    .min(Comparator.comparing(Views.TargetCandidateView::getCapacityL)
                            .thenComparing(Views.TargetCandidateView::getTankNo))
                    .orElseThrow(() -> new BizException("没有满足全部条件的目标罐（空闲/容量/温区/维护窗口/预留），"
                            + "该批次无法处理：" + candidates.stream()
                            .map(c -> c.getTankNo() + "（" + String.join("、", c.getMismatchReasons()) + "）")
                            .reduce((a, c) -> a + "；" + c).orElse("无候选罐")));
        }

        // 选中目标罐后在罐行锁内复查：并发的另一批次可能刚刚【直接占用】该罐。
        // 注意：与其他迁罐方案的预留重叠不在此拦截 —— 两条方案可以同时存在（前端预览可见竞争），
        // 抢占裁决发生在 confirm：先确认的一条成功，另一条按最新条件判过期并提示重新计算。
        Fermenter chosenTank = fermenterRepository.findByTankNoForUpdate(chosen.getTankNo())
                .orElseThrow(() -> new BizException("目标罐不存在"));
        if (!"IDLE".equals(chosenTank.getStatus())
                || (chosenTank.getCurrentBatchNo() != null && !chosenTank.getCurrentBatchNo().isBlank())) {
            throw new StaleTransferException("目标罐 " + chosen.getTankNo()
                    + " 刚被占用，请重新计算迁罐方案");
        }

        LocalDateTime now = LocalDateTime.now();
        TankTransferPlan p = new TankTransferPlan();
        p.setMaintenanceId(m.getId());
        p.setBatchId(b.getId());
        p.setBatchNo(b.getBatchNo());
        p.setSourceTankNo(source.getTankNo());
        p.setTargetTankNo(chosen.getTankNo());
        p.setBatchStage(b.getStatus());
        p.setBatchVolumeL(b.getVolumeL());
        p.setNeedTempMin(source.getTempMin());
        p.setNeedTempMax(source.getTempMax());
        p.setPlannedMoveAt(moveAt);
        p.setReserveFrom(reserveFrom);
        p.setReserveTo(reserveTo);
        p.setStatus("PENDING");
        p.setCompletedStep(0);
        p.setAttemptCount(0);
        p.setProposedBy(req.getOperator());
        p.setProposedAt(now);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setConditionsHash(conditionsHash(m, b, chosen.getTankNo(), moveAt, reserveFrom, reserveTo));
        TankTransferPlan saved = planRepository.saveAndFlush(p);

        TransferReservation r = new TransferReservation();
        r.setPlanId(saved.getId());
        r.setBatchId(b.getId());
        r.setBatchNo(b.getBatchNo());
        r.setTankNo(chosen.getTankNo());
        r.setReserveFrom(reserveFrom);
        r.setReserveTo(reserveTo);
        r.setStatus("ACTIVE");
        r.setCreatedAt(now);
        try {
            reservationRepository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // 极端并发：两条事务同时选中同一个空目标罐，业务重叠查询先通过，数据库唯一键兜底只放一条
            throw new StaleTransferException("目标罐 " + chosen.getTankNo()
                    + " 刚被其他批次的迁罐方案抢占，请重新计算方案");
        }

        addTransferEvent(saved, "PROPOSE", null, null, source.getTankNo(), chosen.getTankNo(),
                "生成迁罐方案，预计 " + fmt(moveAt) + " 迁移", req.getOperator(), now);
        return toPlanView(saved);
    }

    /**
     * 确认迁罐：最新条件复核（方案版本 + 条件指纹 + 四条目标罐硬条件 + 同批次唯一性 + 同目标罐互斥）。
     * 通过后进入 EXECUTING，建立第 1 轮五步进度；批次与罐体在五步全部完成前不做切换，
     * 因此任何一步失败都不可能出现原罐/目标罐同时占用。
     *
     * noRollbackFor=StaleTransferException：负方方案被判过期（释放预留+留痕）后抛 409，
     * 该过期结论必须随事务提交保留，而不是随异常一起回滚成“还是待确认”。
     */
    @Transactional(noRollbackFor = StaleTransferException.class)
    public Views.TransferPlanView confirm(Long planId, MaintenanceDtos.TransferConfirmReq req) {
        TankTransferPlan p = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        // 注意：确认不做 expectedVersion 预检 —— 两个不同批次抢同一目标罐时，方案各自版本都合法，
        // 谁合法应由“罐行悲观锁 + 最新条件复核”裁决（先到先得，后者过期重算），而不是被版本号误杀。
        // 同一方案的重复确认由方案行悲观锁 + 状态机串行保证只成功一次。
        switch (p.getStatus()) {
            case "EXECUTING" -> throw new BizException("方案已在迁移执行中（已完成到第 "
                    + p.getCompletedStep() + " 步），请勿重复确认");
            case "EXECUTED" -> throw new BizException("方案已执行完成，迁罐历史不能重复执行或删除");
            case "CANCELLED" -> throw new BizException("方案已取消，不能确认；请重新计算方案");
            case "EXPIRED" -> throw new StaleTransferException("方案已过期"
                    + (p.getExpiredReason() == null ? "" : "：" + p.getExpiredReason())
                    + "，请重新计算后再确认");
            default -> {
                // PENDING：可能是普通待确认，也可能是迁移失败后退回待处理（有 failedStep）
                if (p.getFailedStep() != null) {
                    throw new BizException("方案曾在第 " + p.getFailedStep()
                            + " 步迁移失败并已回退，请走「继续处理」按最新条件重试，不能重复确认");
                }
            }
        }

        // 锁顺序固定：方案 → 批次 → 两个罐（按罐号排序），避免并发死锁
        Batch b = batchRepository.findByIdForUpdate(p.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        FermenterMaintenance m = maintenanceRepository.findById(p.getMaintenanceId())
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        List<Fermenter> locked = lockSourceAndTarget(p.getSourceTankNo(), p.getTargetTankNo());
        Fermenter source = locked.get(0);
        Fermenter target = locked.get(1);

        revalidate(p, m, b, source, target);

        // 同批次只能有一条生效方案（理论上 propose 已保证，确认时兜底）
        for (TankTransferPlan other : planRepository.findOpenByBatchId(b.getId())) {
            if (!other.getId().equals(p.getId()) && "EXECUTING".equals(other.getStatus())) {
                throw new BizException("批次 " + b.getBatchNo() + " 已有进行中的迁罐方案，同一时刻只能执行一条");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        p.setStatus("EXECUTING");
        p.setAttemptCount(1);
        p.setConfirmedBy(req.getOperator());
        p.setConfirmedAt(now);
        p.setUpdatedAt(now);
        planRepository.saveAndFlush(p);
        createAttemptSteps(p.getId(), 1, now, req.getOperator());
        addTransferEvent(p, "CONFIRM", null, 1, p.getSourceTankNo(), p.getTargetTankNo(),
                "确认迁罐，开始执行五步迁移", req.getOperator(), now);
        return toPlanView(p);
    }

    /** 完成迁移中的某一步；必须严格按 1→5 顺序。第 5 步完成时原子切换批次与两个罐、留历史罐号。 */
    @Transactional
    public Views.TransferPlanView completeStep(Long planId, Integer stepSeq, String operator) {
        TankTransferPlan p = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        if (!"EXECUTING".equals(p.getStatus())) {
            throw new BizException("方案当前为「" + Views.transferStatusName(p.getStatus())
                    + "」，不能登记步骤完成");
        }
        int expected = p.getCompletedStep() + 1;
        if (stepSeq == null || stepSeq != expected) {
            throw new BizException("必须按顺序执行：当前应完成第 " + expected + " 步（"
                    + Views.transferStepName(Views.TRANSFER_STEP_ORDER.get(expected - 1)) + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        List<TankTransferStep> steps = currentAttemptSteps(p);
        TankTransferStep step = steps.get(stepSeq - 1);
        if (!"PENDING".equals(step.getStatus())) {
            throw new BizException("第 " + stepSeq + " 步状态为 " + step.getStatus() + "，不能重复完成");
        }
        step.setStatus("DONE");
        step.setStartedAt(step.getStartedAt() == null ? now : step.getStartedAt());
        step.setFinishedAt(now);
        step.setOperator(operator);
        stepRepository.saveAndFlush(step);
        p.setCompletedStep(stepSeq);
        p.setUpdatedAt(now);

        if (stepSeq == Views.TRANSFER_STEP_ORDER.size()) {
            finishTransfer(p, operator, now);
        } else {
            planRepository.saveAndFlush(p);
        }
        addTransferEvent(p, "STEP_DONE", stepSeq, p.getAttemptCount(),
                p.getSourceTankNo(), p.getTargetTankNo(),
                "第 " + stepSeq + " 步「" + Views.transferStepName(step.getStepCode()) + "」已完成",
                operator, now);
        return toPlanView(p);
    }

    /**
     * 迁移中途失败：标记失败步、把本轮已完成步骤全部回滚留痕，方案退回 PENDING（保留失败步与原因）。
     * 批次/罐体自始至终只在第 5 步才切换，所以回退后绝不会出现两罐同时占同一个批次。
     */
    @Transactional
    public Views.TransferPlanView fail(Long planId, MaintenanceDtos.TransferFailReq req) {
        TankTransferPlan p = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        if (!"EXECUTING".equals(p.getStatus())) {
            throw new BizException("只有迁移中的方案可以上报失败，当前为「"
                    + Views.transferStatusName(p.getStatus()) + "」");
        }
        int failed = req.getFailedStep();
        if (failed < 1 || failed > Views.TRANSFER_STEP_ORDER.size()) {
            throw new BizException("失败步骤序号必须在 1~" + Views.TRANSFER_STEP_ORDER.size() + " 之间");
        }
        if (failed > p.getCompletedStep() + 1) {
            throw new BizException("第 " + failed + " 步尚未开始，不能在该步上报失败");
        }
        LocalDateTime now = LocalDateTime.now();
        List<TankTransferStep> steps = currentAttemptSteps(p);
        // 失败步钉死
        TankTransferStep failStep = steps.get(failed - 1);
        if ("PENDING".equals(failStep.getStatus())) {
            failStep.setStartedAt(now);
        }
        failStep.setStatus("FAILED");
        failStep.setFinishedAt(now);
        failStep.setNote(req.getReason());
        failStep.setOperator(req.getOperator());
        stepRepository.saveAndFlush(failStep);
        // 已完成步回滚留痕（物理上退回原罐，原罐始终保留占用关系）
        for (int i = failed - 2; i >= 0; i--) {
            TankTransferStep s = steps.get(i);
            if ("DONE".equals(s.getStatus())) {
                s.setStatus("ROLLED_BACK");
                s.setNote("第 " + failed + " 步失败，回退本步：" + req.getReason());
                stepRepository.saveAndFlush(s);
            }
        }

        p.setStatus("PENDING");
        p.setCompletedStep(0);
        p.setFailedStep(failed);
        p.setFailReason(req.getReason());
        p.setUpdatedAt(now);
        planRepository.saveAndFlush(p);

        addTransferEvent(p, "STEP_FAIL", failed, p.getAttemptCount(),
                p.getSourceTankNo(), p.getTargetTankNo(),
                "第 " + failed + " 步失败：" + req.getReason(), req.getOperator(), now);
        addTransferEvent(p, "ROLLBACK", null, p.getAttemptCount(),
                p.getTargetTankNo(), p.getSourceTankNo(),
                "已完成步骤全部回退，批次仍在原罐 " + p.getSourceTankNo() + "，方案退回待处理",
                req.getOperator(), now);
        return toPlanView(p);
    }

    /** 失败后继续处理：按最新条件复核，通过后开出新一轮五步（上一轮失败/回退记录原样保留）。 */
    @Transactional(noRollbackFor = StaleTransferException.class)
    public Views.TransferPlanView resume(Long planId, MaintenanceDtos.TransferResumeReq req) {
        TankTransferPlan p = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        checkVersion(p.getVersion(), req.getExpectedVersion());
        if (!"PENDING".equals(p.getStatus()) || p.getFailedStep() == null) {
            throw new BizException("只有迁移失败后退回待处理的方案可以继续处理");
        }
        Batch b = batchRepository.findByIdForUpdate(p.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        FermenterMaintenance m = maintenanceRepository.findById(p.getMaintenanceId())
                .orElseThrow(() -> new BizException("维护窗口不存在"));
        List<Fermenter> locked = lockSourceAndTarget(p.getSourceTankNo(), p.getTargetTankNo());
        revalidate(p, m, b, locked.get(0), locked.get(1));

        LocalDateTime now = LocalDateTime.now();
        int attempt = p.getAttemptCount() + 1;
        p.setStatus("EXECUTING");
        p.setCompletedStep(0);
        p.setAttemptCount(attempt);
        p.setFailReason(null);
        p.setFailedStep(null);
        p.setUpdatedAt(now);
        planRepository.saveAndFlush(p);
        createAttemptSteps(p.getId(), attempt, now, req.getOperator());
        addTransferEvent(p, "RESUME", null, attempt, p.getSourceTankNo(), p.getTargetTankNo(),
                "失败后继续处理，开始第 " + attempt + " 轮尝试", req.getOperator(), now);
        return toPlanView(p);
    }

    /** 取消待处理方案（含失败回退后待处理的方案）；已执行历史不能取消。 */
    @Transactional
    public Views.TransferPlanView cancelPlan(Long planId, MaintenanceDtos.TransferCancelReq req) {
        TankTransferPlan p = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        if (!"PENDING".equals(p.getStatus())) {
            throw new BizException("只有待处理（含失败回退）的方案可以取消，当前为「"
                    + Views.transferStatusName(p.getStatus()) + "」；已执行的迁罐不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        p.setStatus("CANCELLED");
        p.setExpiredReason("人工取消：" + req.getReason());
        p.setUpdatedAt(now);
        planRepository.saveAndFlush(p);
        releaseReservations(p, "CANCELLED", now);
        addTransferEvent(p, "CANCEL", null, p.getAttemptCount(),
                p.getSourceTankNo(), p.getTargetTankNo(), req.getReason(), req.getOperator(), now);
        return toPlanView(p);
    }

    public Views.TransferPlanView planDetail(Long planId) {
        TankTransferPlan p = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("迁罐方案不存在"));
        return toPlanView(p);
    }

    public List<Views.TransferPlanView> plans(Long maintenanceId) {
        List<TankTransferPlan> all = maintenanceId == null
                ? planRepository.findAllByOrderByIdDesc()
                : planRepository.findAllByMaintenanceIdOrderByIdAsc(maintenanceId);
        List<Views.TransferPlanView> list = new ArrayList<>();
        for (TankTransferPlan p : all) {
            list.add(toPlanView(p));
        }
        return list;
    }

    // =====================================================================
    // 冲突计算 / 目标罐适配
    // =====================================================================

    private Views.MaintenanceTimelineView buildTimeline(FermenterMaintenance m) {
        Views.MaintenanceTimelineView tl = new Views.MaintenanceTimelineView();
        tl.setMaintenance(toMaintenanceView(m));

        // 1) 当前占罐批次
        for (Batch b : batchRepository.findAllByFermenterNoOrderByBatchNoAsc(m.getTankNo())) {
            if (List.of("PACKAGED", "SCRAPPED").contains(b.getStatus())) {
                continue;
            }
            LocalDateTime from = occupyFrom(b);
            LocalDateTime to = occupyTo(b);
            if (!overlapOpen(from, to, m.getStartAt(), m.getExpectedRecoverAt())) {
                continue;
            }
            Views.TimelineItemView item = baseItem("OCCUPYING", "当前占罐", b, from, to);
            Fermenter source = fermenterRepository.findByTankNo(m.getTankNo()).orElse(null);
            List<Views.TargetCandidateView> candidates = source == null ? List.of()
                    : evaluateCandidates(m, b, source,
                            m.getStartAt().minusHours(MOVE_BEFORE_HOURS + RESERVE_BUFFER_HOURS),
                            m.getExpectedRecoverAt());
            item.setCandidates(candidates);
            boolean hasTarget = candidates.stream().anyMatch(Views.TargetCandidateView::isSuitable);
            if ("CANCELLED".equals(m.getStatus())) {
                item.setResult("NO_CONFLICT");
                item.setResultDetail("窗口已取消");
            } else if (hasTarget) {
                item.setResult("NEED_TRANSFER");
                Views.TargetCandidateView best = candidates.stream()
                        .filter(Views.TargetCandidateView::isSuitable)
                        .min(Comparator.comparing(Views.TargetCandidateView::getCapacityL)
                                .thenComparing(Views.TargetCandidateView::getTankNo))
                        .orElseThrow();
                item.setResultDetail("维护开始前需迁出，推荐目标罐 " + best.getTankNo()
                        + "（罐容 " + best.getCapacityL() + "L，温区 "
                        + best.getTempMin() + "~" + best.getTempMax() + "℃）");
            } else {
                item.setResult("UNRESOLVABLE");
                item.setResultDetail("没有同时满足空闲/容量/温区/非维护的目标罐："
                        + candidates.stream()
                        .map(c -> c.getTankNo() + "（" + String.join("、", c.getMismatchReasons()) + "）")
                        .reduce((a, c) -> a + "；" + c).orElse("无候选罐"));
            }
            attachPlan(item, b, m.getId());
            applyResult(item);
            tl.getItems().add(item);
        }

        // 2) 未来待入罐批次（PLAN，登记了计划罐号）
        for (Batch b : batchRepository.findAllByOrderByBatchNoAsc()) {
            if (b.getFermenterNo() != null || !m.getTankNo().equals(b.getPlannedTankNo())) {
                continue;
            }
            LocalDateTime enter = b.getPlannedEnterAt();
            if (enter == null) {
                continue;
            }
            LocalDateTime from = enter;
            LocalDateTime to = enter.plusDays(PLANNED_OCCUPY_DAYS);
            if (!overlap(from, to, m.getStartAt(), m.getExpectedRecoverAt())) {
                continue;
            }
            Views.TimelineItemView item = baseItem("FUTURE", "未来待入罐", b, from, to);
            item.setPlannedEnterAt(enter);
            if ("CANCELLED".equals(m.getStatus())) {
                item.setResult("NO_CONFLICT");
                item.setResultDetail("窗口已取消");
            } else {
                item.setResult("MUST_DELAY");
                item.setResultDetail("计划入罐时间 " + fmt(enter) + " 落在维护窗口内，必须延后至 "
                        + fmt(m.getExpectedRecoverAt()) + " 恢复之后再入罐");
            }
            attachPlan(item, b, m.getId());
            applyResult(item);
            tl.getItems().add(item);
        }

        String worst = "NO_CONFLICT";
        for (Views.TimelineItemView it : tl.getItems()) {
            it.setMaintenanceId(m.getId());
            if (SEVERITY.indexOf(it.getResult()) > SEVERITY.indexOf(worst)) {
                worst = it.getResult();
            }
        }
        tl.setOverallResult(worst);
        tl.setOverallResultName(Views.conflictName(worst));
        tl.setOverallResultColor(Views.conflictColor(worst));
        return tl;
    }

    private void applyResult(Views.TimelineItemView item) {
        item.setResultName(Views.conflictName(item.getResult()));
        item.setResultColor(Views.conflictColor(item.getResult()));
    }

    private Views.TimelineItemView baseItem(String kind, String kindName, Batch b,
                                            LocalDateTime from, LocalDateTime to) {
        Views.TimelineItemView item = new Views.TimelineItemView();
        item.setMaintenanceId(0L); // 由 buildTimeline 按窗口回填
        item.setKind(kind);
        item.setKindName(kindName);
        item.setBatchId(b.getId());
        item.setBatchNo(b.getBatchNo());
        item.setBatchStatus(b.getStatus());
        item.setBatchStatusName(Views.batchStatusName(b.getStatus()));
        item.setStage(b.getStatus());
        item.setStageName(Views.batchStatusName(b.getStatus()));
        item.setVolumeL(b.getVolumeL());
        item.setTankNo(b.getFermenterNo());
        item.setOccupyFrom(from);
        item.setOccupyTo(to);
        item.setResult("NO_CONFLICT");
        item.setResultName(Views.conflictName("NO_CONFLICT"));
        item.setResultColor(Views.conflictColor("NO_CONFLICT"));
        return item;
    }

    private void attachPlan(Views.TimelineItemView item, Batch b, Long maintenanceId) {
        List<TankTransferPlan> mine = planRepository.findAllByBatchIdOrderByIdAsc(b.getId()).stream()
                .filter(p -> p.getMaintenanceId().equals(maintenanceId))
                .toList();
        if (mine.isEmpty()) {
            return;
        }
        TankTransferPlan p = mine.get(mine.size() - 1);
        item.setPlanId(p.getId());
        item.setPlanStatus(p.getStatus());
        item.setPlanStatusName(Views.transferStatusName(p.getStatus()));
        item.setTargetTankNo(p.getTargetTankNo());
    }

    /**
     * 逐罐评估目标候选。四条硬条件全部在服务端算：
     * 空闲（IDLE 且无占罐批次且非停用）、容量 ≥ 批次量、温控区间覆盖批次所需区间、
     * 不在任何生效维护窗口内；此外不得与其他生效预留重叠。
     */
    private List<Views.TargetCandidateView> evaluateCandidates(FermenterMaintenance m, Batch b,
                                                               Fermenter source,
                                                               LocalDateTime reserveFrom,
                                                               LocalDateTime reserveTo) {
        List<Views.TargetCandidateView> list = new ArrayList<>();
        for (Fermenter t : fermenterRepository.findAllByOrderByTankNoAsc()) {
            if (t.getTankNo().equals(source.getTankNo())) {
                continue;
            }
            Views.TargetCandidateView c = new Views.TargetCandidateView();
            c.setTankNo(t.getTankNo());
            c.setCapacityL(t.getCapacityL());
            c.setTempMin(t.getTempMin());
            c.setTempMax(t.getTempMax());
            c.setTankStatus(t.getStatus());
            c.setTankStatusName(Views.tankStatusName(t.getStatus()));

            boolean idle = "IDLE".equals(t.getStatus())
                    && (t.getCurrentBatchNo() == null || t.getCurrentBatchNo().isBlank());
            c.setIdle(idle);
            if ("DISABLED".equals(t.getStatus())) {
                c.getMismatchReasons().add("罐体已停用");
            } else if (!idle) {
                c.getMismatchReasons().add("非空闲（" + Views.tankStatusName(t.getStatus())
                        + (t.getCurrentBatchNo() == null ? "" : "，占用批次 " + t.getCurrentBatchNo()) + "）");
            }

            boolean capacityOk = b.getVolumeL() == null || t.getCapacityL() == null
                    || b.getVolumeL().compareTo(t.getCapacityL()) <= 0;
            c.setCapacityOk(capacityOk);
            if (!capacityOk) {
                c.getMismatchReasons().add("容量不足（批次 " + b.getVolumeL() + "L ＞ 罐容 "
                        + t.getCapacityL() + "L）");
            }

            boolean tempOk = tempCovers(source.getTempMin(), source.getTempMax(), t.getTempMin(), t.getTempMax());
            c.setTempCompatible(tempOk);
            if (!tempOk) {
                c.getMismatchReasons().add("温区不兼容（批次在原罐需 "
                        + textRange(source.getTempMin(), source.getTempMax()) + "℃，目标罐仅 "
                        + textRange(t.getTempMin(), t.getTempMax()) + "℃）");
            }

            boolean noMaint = true;
            for (FermenterMaintenance other : maintenanceRepository.findActiveByTankNo(t.getTankNo())) {
                // 只看目标罐自己的窗口；传入的 m 是【原罐】维护，本来就不是同一罐，无需排除
                if (overlap(reserveFrom, reserveTo, other.getStartAt(), other.getExpectedRecoverAt())) {
                    noMaint = false;
                    c.getMismatchReasons().add("处于维护窗口（" + fmt(other.getStartAt()) + " ~ "
                            + fmt(other.getExpectedRecoverAt()) + "）");
                    break;
                }
            }
            c.setNoMaintenance(noMaint);

            // 其他批次的预留不直接否决候选：允许两个方案同时存在（迁罐预览要能看到竞争），
            // 唯一合法迁移由确认事务裁决（先确认者得，后者过期重算）。这里仅作提示展示。
            boolean notReserved = true;
            for (TransferReservation r : reservationRepository.findActiveOverlap(
                    t.getTankNo(), reserveFrom, reserveTo)) {
                if (r.getBatchId().equals(b.getId())) {
                    // 本批次自己的预留不影响自己的候选评估
                    continue;
                }
                c.getMismatchReasons().add("与批次 " + r.getBatchNo() + " 的迁罐方案存在抢占竞争（"
                        + fmt(r.getReserveFrom()) + " ~ " + fmt(r.getReserveTo())
                        + "），需先到先得或重新选罐");
            }
            c.setNotReserved(notReserved);

            c.setSuitable(idle && capacityOk && tempOk && noMaint && notReserved);
            list.add(c);
        }
        return list;
    }

    /**
     * 确认/继续时的最新条件复核。任何一项不满足都抛 409 过期或 400 业务错误，绝不带着旧条件执行。
     */
    private void revalidate(TankTransferPlan p, FermenterMaintenance m, Batch b,
                            Fermenter source, Fermenter target) {
        List<String> mismatches = new ArrayList<>();

        if ("CANCELLED".equals(m.getStatus())) {
            throw stale(p, "维护窗口已被取消，方案 " + p.getId() + " 已过期，请重新计算");
        }
        if ("FINISHED".equals(m.getStatus())) {
            throw stale(p, "维护已于 " + fmt(m.getActualRecoverAt())
                    + " 提前结束，方案 " + p.getId() + " 已过期，无需迁罐，请按新窗口排程");
        }
        if (!m.getStartAt().equals(planWindowStart(p)) || !m.getExpectedRecoverAt().equals(p.getReserveTo())) {
            throw stale(p, "维护窗口时间已被其他人调整（现为 " + fmt(m.getStartAt())
                    + " ~ " + fmt(m.getExpectedRecoverAt()) + "），旧方案 " + p.getId()
                    + " 已过期，请重新计算");
        }

        if (b.getFermenterNo() == null || !b.getFermenterNo().equals(p.getSourceTankNo())) {
            throw stale(p, "批次 " + b.getBatchNo() + " 已不在原罐 "
                    + p.getSourceTankNo() + "（当前："
                    + (b.getFermenterNo() == null ? "未在罐" : b.getFermenterNo())
                    + "），旧方案已过期，请重新计算");
        }
        if (!b.getStatus().equals(p.getBatchStage())) {
            throw stale(p, "批次 " + b.getBatchNo() + " 阶段已从「"
                    + Views.batchStatusName(p.getBatchStage()) + "」变为「"
                    + Views.batchStatusName(b.getStatus()) + "」，旧方案已过期，请重新计算");
        }
        if (b.getVolumeL() != null && p.getBatchVolumeL() != null
                && b.getVolumeL().compareTo(p.getBatchVolumeL()) != 0) {
            mismatches.add("批次产量已变化");
        }

        if (!target.getTankNo().equals(p.getTargetTankNo())) {
            throw stale(p, "目标罐与方案不一致，请重新计算方案");
        }
        boolean idle = "IDLE".equals(target.getStatus())
                && (target.getCurrentBatchNo() == null || target.getCurrentBatchNo().isBlank());
        if ("DISABLED".equals(target.getStatus())) {
            mismatches.add("目标罐已被停用");
        } else if (!idle) {
            mismatches.add("目标罐已不是空闲状态（" + Views.tankStatusName(target.getStatus())
                    + (target.getCurrentBatchNo() == null ? "" : "，被批次 " + target.getCurrentBatchNo() + " 占用")
                    + "）");
        }
        if (b.getVolumeL() != null && target.getCapacityL() != null
                && b.getVolumeL().compareTo(target.getCapacityL()) > 0) {
            mismatches.add("目标罐容量已不足");
        }
        if (!tempCovers(p.getNeedTempMin(), p.getNeedTempMax(), target.getTempMin(), target.getTempMax())) {
            mismatches.add("目标罐温控区间已不兼容（需求 "
                    + textRange(p.getNeedTempMin(), p.getNeedTempMax()) + "℃，实际 "
                    + textRange(target.getTempMin(), target.getTempMax()) + "℃）");
        }
        for (FermenterMaintenance other : maintenanceRepository.findActiveByTankNo(target.getTankNo())) {
            if (other.getId().equals(m.getId())) {
                continue;
            }
            if (overlap(p.getReserveFrom(), p.getReserveTo(), other.getStartAt(), other.getExpectedRecoverAt())) {
                mismatches.add("目标罐在预留时段内新增了维护窗口（" + fmt(other.getStartAt())
                        + " ~ " + fmt(other.getExpectedRecoverAt()) + "）");
            }
        }

        // 同目标罐重叠时段互斥：只被【已经确认执行（EXECUTING）】的其他方案挡住。
        // 别人的 PENDING 方案不算抢占 —— 先到先得：先确认的赢，后确认的看到赢家 EXECUTING 才判过期。
        for (TankTransferPlan other : planRepository.findOpenByTargetOverlap(
                target.getTankNo(), p.getReserveFrom(), p.getReserveTo())) {
            if (other.getId().equals(p.getId())) {
                continue;
            }
            if ("EXECUTING".equals(other.getStatus())) {
                throw stale(p, "目标罐 " + target.getTankNo() + " 已被批次 " + other.getBatchNo()
                        + " 的合法迁罐方案先占用（方案 " + other.getId() + "），本方案过期，请重新选罐");
            }
        }
        for (TransferReservation r : reservationRepository.findActiveOverlap(
                target.getTankNo(), p.getReserveFrom(), p.getReserveTo())) {
            if (r.getPlanId().equals(p.getId())) {
                continue;
            }
            // 预留只在其方案已确认执行时才构成抢占；待确认方案的预留由上面的先到先得规则裁决
            boolean otherExecuting = planRepository.findById(r.getPlanId())
                    .map(x -> "EXECUTING".equals(x.getStatus())).orElse(false);
            if (otherExecuting) {
                throw stale(p, "目标罐 " + target.getTankNo() + " 已被批次 " + r.getBatchNo()
                        + " 的执行中迁罐方案占用，旧方案过期，请重新计算");
            }
        }

        if (!mismatches.isEmpty()) {
            throw stale(p, "确认前条件已变化：" + String.join("；", mismatches)
                    + "。旧方案 " + p.getId() + " 已过期，请重新计算");
        }

        // 条件指纹兜底：显式规则漏掉的任何字段变化都会在这里被拦住
        String hash = conditionsHash(m, b, target.getTankNo(),
                p.getPlannedMoveAt(), p.getReserveFrom(), p.getReserveTo());
        if (!hash.equals(p.getConditionsHash())) {
            throw stale(p, "方案条件指纹不一致（维护/批次/目标罐有未识别的变更），"
                    + "旧方案 " + p.getId() + " 已过期，请重新计算");
        }
    }

    /** 把方案持久化标记为过期（释放预留+留痕），再抛 409 —— 配合 noRollbackFor 使过期结论落库 */
    private StaleTransferException stale(TankTransferPlan p, String reason) {
        expirePlan(p, reason, "系统");
        return new StaleTransferException(reason);
    }

    /** 第 5 步完成：原子切换批次与两个罐、追加历史罐号、释放预留、方案置为已执行。 */
    private void finishTransfer(TankTransferPlan p, String operator, LocalDateTime now) {
        Batch b = batchRepository.findById(p.getBatchId()).orElseThrow();
        Fermenter source = fermenterRepository.findByTankNo(p.getSourceTankNo()).orElseThrow();
        Fermenter target = fermenterRepository.findByTankNo(p.getTargetTankNo()).orElseThrow();

        // 1) 历史罐号：关闭原罐占用段（履历缺失时先补开再立即关闭，保证换罐前后两段都在），新开目标罐段
        Optional<BatchTankHistory> openHistory =
                historyRepository.findFirstByBatchIdAndLeftAtIsNullOrderByEnteredAtDescIdDesc(b.getId());
        if (openHistory.isPresent()) {
            BatchTankHistory h = openHistory.get();
            h.setLeftAt(now);
            h.setLeaveReason("TRANSFER_OUT");
            historyRepository.save(h);
        } else {
            BatchTankHistory src = new BatchTankHistory();
            src.setBatchId(b.getId());
            src.setBatchNo(b.getBatchNo());
            src.setStage(p.getBatchStage());
            src.setTankNo(p.getSourceTankNo());
            src.setEnteredAt(p.getProposedAt() == null ? now : p.getProposedAt());
            src.setEnterReason("ASSIGN");
            src.setLeftAt(now);
            src.setLeaveReason("TRANSFER_OUT");
            historyRepository.save(src);
        }
        BatchTankHistory nh = new BatchTankHistory();
        nh.setBatchId(b.getId());
        nh.setBatchNo(b.getBatchNo());
        nh.setStage(b.getStatus());
        nh.setTankNo(target.getTankNo());
        nh.setEnteredAt(now);
        nh.setEnterReason("TRANSFER");
        nh.setTransferPlanId(p.getId());
        historyRepository.save(nh);

        // 2) 批次只换罐号：阶段不变、发酵开始/成熟时间不变、温度糖度曲线继续记到同一批次
        b.setFermenterNo(target.getTankNo());
        b.setUpdatedAt(now);
        batchRepository.save(b);

        // 3) 原罐排空（即将进入维护），目标罐承接批次并同步到批次当前阶段
        source.setCurrentBatchNo(null);
        source.setStatus("CLEANING");
        source.setUpdatedAt(now);
        fermenterRepository.save(source);
        target.setCurrentBatchNo(b.getBatchNo());
        target.setStatus(Views.tankStatusOfBatch(b.getStatus()));
        target.setUpdatedAt(now);
        fermenterRepository.save(target);

        // 4) 预留转“已兑现”，方案置已执行
        releaseReservations(p, "EXECUTED", now);
        p.setStatus("EXECUTED");
        p.setExecutedAt(now);
        p.setFailedStep(null);
        p.setFailReason(null);
        p.setUpdatedAt(now);
        planRepository.saveAndFlush(p);
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /** 固定加锁顺序：按罐号升序锁原罐/目标罐，避免两个互迁事务交叉死锁 */
    private List<Fermenter> lockSourceAndTarget(String sourceNo, String targetNo) {
        String first = sourceNo.compareTo(targetNo) <= 0 ? sourceNo : targetNo;
        String second = sourceNo.compareTo(targetNo) <= 0 ? targetNo : sourceNo;
        Fermenter f = fermenterRepository.findByTankNoForUpdate(first)
                .orElseThrow(() -> new BizException("发酵罐 " + first + " 不存在"));
        Fermenter s = fermenterRepository.findByTankNoForUpdate(second)
                .orElseThrow(() -> new BizException("发酵罐 " + second + " 不存在"));
        List<Fermenter> ordered = new ArrayList<>(2);
        ordered.add(sourceNo.equals(first) ? f : s);
        ordered.add(targetNo.equals(first) ? f : s);
        return ordered;
    }

    private void createAttemptSteps(Long planId, int attempt, LocalDateTime now, String operator) {
        for (int i = 0; i < Views.TRANSFER_STEP_ORDER.size(); i++) {
            TankTransferStep s = new TankTransferStep();
            s.setPlanId(planId);
            s.setAttemptSeq(attempt);
            s.setStepSeq(i + 1);
            s.setStepCode(Views.TRANSFER_STEP_ORDER.get(i));
            s.setStatus("PENDING");
            s.setOperator(operator);
            stepRepository.save(s);
        }
    }

    private List<TankTransferStep> currentAttemptSteps(TankTransferPlan p) {
        List<TankTransferStep> all =
                stepRepository.findAllByPlanIdOrderByAttemptSeqAscStepSeqAsc(p.getId());
        List<TankTransferStep> cur = new ArrayList<>();
        for (TankTransferStep s : all) {
            if (s.getAttemptSeq().equals(p.getAttemptCount())) {
                cur.add(s);
            }
        }
        if (cur.size() != Views.TRANSFER_STEP_ORDER.size()) {
            throw new BizException("第 " + p.getAttemptCount() + " 轮迁移步骤数据不完整，请联系管理员");
        }
        return cur;
    }

    private void expirePendingPlans(FermenterMaintenance m, String reason, String operator) {
        for (TankTransferPlan p : planRepository.findAllByMaintenanceIdOrderByIdAsc(m.getId())) {
            if ("PENDING".equals(p.getStatus())) {
                expirePlan(p, reason, operator);
            }
            // EXECUTING 方案现场正在搬酒，不能被改期打断；其条件差异在执行收尾时仍按实物落账
        }
    }

    private void expirePlan(TankTransferPlan p, String reason, String operator) {
        if (!"PENDING".equals(p.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        p.setStatus("EXPIRED");
        p.setExpiredReason(reason);
        p.setUpdatedAt(now);
        try {
            planRepository.saveAndFlush(p);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 并发下另一事务已更新该方案：过期/执行只应有一个最终态，直接返回让对方结果生效
            return;
        }
        releaseReservations(p, "EXPIRED", now);
        addTransferEvent(p, "EXPIRE", null, p.getAttemptCount(),
                p.getSourceTankNo(), p.getTargetTankNo(), reason, operator, now);
    }

    private void releaseReservations(TankTransferPlan p, String reason, LocalDateTime now) {
        for (TransferReservation r : reservationRepository.findAllByPlanIdOrderByIdAsc(p.getId())) {
            if (!"ACTIVE".equals(r.getStatus())) {
                continue;
            }
            r.setStatus("RELEASED");
            r.setReleasedAt(now);
            r.setReleaseReason(reason);
            reservationRepository.save(r);
        }
    }

    private void addMaintenanceEvent(FermenterMaintenance m, String type,
                                     LocalDateTime fromStart, LocalDateTime toStart,
                                     LocalDateTime fromRecover, LocalDateTime toRecover,
                                     String reason, String operator, LocalDateTime at) {
        MaintenanceEvent e = new MaintenanceEvent();
        e.setMaintenanceId(m.getId());
        e.setTankNo(m.getTankNo());
        e.setEventType(type);
        e.setFromStartAt(fromStart);
        e.setToStartAt(toStart);
        e.setFromRecoverAt(fromRecover);
        e.setToRecoverAt(toRecover);
        e.setReason(reason);
        e.setOperator(operator);
        e.setCreatedAt(at);
        maintenanceEventRepository.save(e);
    }

    private void addTransferEvent(TankTransferPlan p, String type, Integer stepSeq, Integer attempt,
                                  String from, String to, String detail, String operator,
                                  LocalDateTime at) {
        TankTransferEvent e = new TankTransferEvent();
        e.setPlanId(p.getId());
        e.setBatchId(p.getBatchId());
        e.setBatchNo(p.getBatchNo());
        e.setEventType(type);
        e.setStepSeq(stepSeq);
        e.setAttemptSeq(attempt);
        e.setFromTankNo(from);
        e.setToTankNo(to);
        e.setDetail(detail);
        e.setOperator(operator);
        e.setCreatedAt(at);
        transferEventRepository.save(e);
    }

    private Views.MaintenanceView toMaintenanceView(FermenterMaintenance m) {
        return Views.MaintenanceView.of(m,
                maintenanceEventRepository.findAllByMaintenanceIdOrderByIdAsc(m.getId()));
    }

    /** 方案出参：附带分步进度、操作流水；PENDING/EXECUTING 时实时复核目标罐是否仍然可用。 */
    private Views.TransferPlanView toPlanView(TankTransferPlan p) {
        List<TankTransferStep> steps =
                stepRepository.findAllByPlanIdOrderByAttemptSeqAscStepSeqAsc(p.getId());
        List<TankTransferEvent> events = transferEventRepository.findAllByPlanIdOrderByIdAsc(p.getId());
        boolean stillValid = true;
        List<String> mismatches = new ArrayList<>();
        if ("PENDING".equals(p.getStatus()) || "EXECUTING".equals(p.getStatus())) {
            Optional<Fermenter> target = fermenterRepository.findByTankNo(p.getTargetTankNo());
            Optional<Batch> batch = batchRepository.findById(p.getBatchId());
            Optional<FermenterMaintenance> m = maintenanceRepository.findById(p.getMaintenanceId());
            if (target.isEmpty() || batch.isEmpty() || m.isEmpty()) {
                stillValid = false;
                mismatches.add("关联数据缺失");
            } else {
                Fermenter t = target.get();
                Batch b = batch.get();
                FermenterMaintenance w = m.get();
                if (!"IDLE".equals(t.getStatus()) || (t.getCurrentBatchNo() != null
                        && !t.getCurrentBatchNo().isBlank()
                        && !t.getCurrentBatchNo().equals(b.getBatchNo()))) {
                    stillValid = false;
                    mismatches.add("目标罐已非空闲");
                }
                if (b.getFermenterNo() == null || !b.getFermenterNo().equals(p.getSourceTankNo())) {
                    stillValid = false;
                    mismatches.add("批次已不在原罐");
                }
                if ("CANCELLED".equals(w.getStatus()) || "FINISHED".equals(w.getStatus())
                        || !w.getStartAt().equals(planWindowStart(p))
                        || !w.getExpectedRecoverAt().equals(p.getReserveTo())) {
                    stillValid = false;
                    mismatches.add("维护窗口已变化");
                }
            }
        }
        return Views.TransferPlanView.of(p, steps, events, stillValid, mismatches);
    }

    /** 方案保存时窗口开始时间没有冗余列：用 reserveFrom + 缓冲 + 迁移提前量反推不可靠，
     *  这里把窗口开始时间钉进预留起点的推导链；确认时直接比对窗口实体。 */
    private LocalDateTime planWindowStart(TankTransferPlan p) {
        return p.getReserveFrom().plusHours(RESERVE_BUFFER_HOURS + MOVE_BEFORE_HOURS);
    }

    /**
     * 方案条件指纹：把窗口/批次/目标罐/迁移时间的关键字段摘要钉死。
     * 确认时按最新数据重算比对，任何一项被改动都会判定过期。
     */
    private String conditionsHash(FermenterMaintenance m, Batch b, String targetTankNo,
                                  LocalDateTime moveAt, LocalDateTime reserveFrom,
                                  LocalDateTime reserveTo) {
        Fermenter t = fermenterRepository.findByTankNo(targetTankNo).orElse(null);
        String raw = String.join("|",
                "M" + m.getId(),
                String.valueOf(m.getStartAt()),
                String.valueOf(m.getExpectedRecoverAt()),
                m.getStatus(),
                "B" + b.getId(),
                b.getBatchNo(),
                String.valueOf(b.getStatus()),
                b.getFermenterNo() == null ? "" : b.getFermenterNo(),
                b.getVolumeL() == null ? "" : b.getVolumeL().stripTrailingZeros().toPlainString(),
                "T" + targetTankNo,
                t == null ? "MISSING" : t.getStatus(),
                t == null || t.getCurrentBatchNo() == null ? "" : t.getCurrentBatchNo(),
                t == null || t.getCapacityL() == null ? "" : t.getCapacityL().stripTrailingZeros().toPlainString(),
                t == null || t.getTempMin() == null ? "" : t.getTempMin().stripTrailingZeros().toPlainString(),
                t == null || t.getTempMax() == null ? "" : t.getTempMax().stripTrailingZeros().toPlainString(),
                String.valueOf(moveAt),
                String.valueOf(reserveFrom),
                String.valueOf(reserveTo));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算方案条件指纹", e);
        }
    }

    private void checkVersion(Integer current, Integer expected) {
        if (expected != null && current != null && !current.equals(expected)) {
            // 走全局 ObjectOptimisticLockingFailureException → HTTP 409 提示
            throw new ObjectOptimisticLockingFailureException(
                    "维护窗口/迁罐方案",
                    "当前版本 " + current + "，提交基于版本 " + expected);
        }
    }

    /** 温控兼容：目标罐区间必须完整覆盖批次所需（原罐）区间；null 边界视为不限制 */
    private boolean tempCovers(BigDecimal needMin, BigDecimal needMax,
                               BigDecimal tankMin, BigDecimal tankMax) {
        if (needMin != null && tankMin != null && tankMin.compareTo(needMin) > 0) {
            return false;
        }
        if (needMax != null && tankMax != null && tankMax.compareTo(needMax) < 0) {
            return false;
        }
        return true;
    }

    private String textRange(BigDecimal min, BigDecimal max) {
        return (min == null ? "不限" : min.stripTrailingZeros().toPlainString()) + "~"
                + (max == null ? "不限" : max.stripTrailingZeros().toPlainString());
    }

    private boolean overlap(LocalDateTime from1, LocalDateTime to1,
                            LocalDateTime from2, LocalDateTime to2) {
        return from1.isBefore(to2) && to1.isAfter(from2);
    }

    /** 占罐区间右端可能为 null（没有预计成熟日 = 仍在罐，开放区间） */
    private boolean overlapOpen(LocalDateTime from1, LocalDateTime to1Nullable,
                                LocalDateTime from2, LocalDateTime to2) {
        return from1.isBefore(to2) && (to1Nullable == null || to1Nullable.isAfter(from2));
    }

    private LocalDateTime occupyFrom(Batch b) {
        if (b.getFermentStart() != null) {
            return b.getFermentStart();
        }
        if (b.getMashStart() != null) {
            return b.getMashStart();
        }
        if (b.getBrewDate() != null) {
            return b.getBrewDate().atStartOfDay();
        }
        return LocalDateTime.now();
    }

    private LocalDateTime occupyTo(Batch b) {
        // 预计成熟日当天结束视为占罐结束；没有成熟日 = 仍在罐（开放区间）
        return b.getExpectedMature() == null ? null : b.getExpectedMature().atTime(23, 59, 59);
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private String fmt(LocalDateTime t) {
        return t == null ? "—" : t.format(FMT);
    }

    /** 给 FermenterService.assign 用的占用校验：罐在当前时刻是否落在生效维护窗口内 */
    public void assertTankAvailableNow(String tankNo) {
        LocalDateTime now = LocalDateTime.now();
        for (FermenterMaintenance m : maintenanceRepository.findActiveByTankNo(tankNo)) {
            // IN_PROGRESS 以状态为准（登记/标记开始后即占用）；SCHEDULED 按时间区间兜底
            boolean inside = "IN_PROGRESS".equals(m.getStatus())
                    || (!now.isBefore(m.getStartAt()) && now.isBefore(m.getExpectedRecoverAt()));
            if (inside) {
                throw new BizException("罐 " + tankNo + " 正处于维护窗口（" + Views.maintTypeName(m.getMaintType())
                        + "，" + fmt(m.getStartAt()) + " ~ " + fmt(m.getExpectedRecoverAt())
                        + "），不能安排批次入罐");
            }
        }
    }

    /**
     * 启动迁移用：种子/历史方案的条件指纹是占位值时，按当前库内真实数据重算一次（幂等）。
     * 只处理 PENDING 方案；运行期产生的方案指纹本来就正确，指纹相等则跳过。
     */
    @Transactional
    public int rehashPendingPlanFingerprints() {
        int touched = 0;
        for (TankTransferPlan p : planRepository.findAllByOrderByIdDesc()) {
            if (!"PENDING".equals(p.getStatus())) {
                continue;
            }
            Optional<FermenterMaintenance> m = maintenanceRepository.findById(p.getMaintenanceId());
            Optional<Batch> b = batchRepository.findById(p.getBatchId());
            Optional<Fermenter> t = fermenterRepository.findByTankNo(p.getTargetTankNo());
            if (m.isEmpty() || b.isEmpty() || t.isEmpty()) {
                continue;
            }
            String hash = conditionsHash(m.get(), b.get(), p.getTargetTankNo(),
                    p.getPlannedMoveAt(), p.getReserveFrom(), p.getReserveTo());
            if (!hash.equals(p.getConditionsHash())) {
                p.setConditionsHash(hash);
                p.setUpdatedAt(LocalDateTime.now());
                planRepository.save(p);
                touched++;
            }
        }
        return touched;
    }
}
