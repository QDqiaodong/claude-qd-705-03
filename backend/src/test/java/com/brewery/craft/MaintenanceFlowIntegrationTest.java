package com.brewery.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.brewery.craft.common.BizException;
import com.brewery.craft.common.StaleTransferException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Fermenter;
import com.brewery.craft.domain.FermenterMaintenance;
import com.brewery.craft.domain.TankTransferPlan;
import com.brewery.craft.dto.MaintenanceDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.FermenterMaintenanceRepository;
import com.brewery.craft.repository.FermenterRepository;
import com.brewery.craft.repository.TankTransferPlanRepository;
import com.brewery.craft.service.BatchService;
import com.brewery.craft.service.MaintenanceService;

/**
 * 模块七验收：计划维护无冲突 / 占罐冲突成功迁移 / 容量温区不兼容 / 目标罐被抢占 /
 * 迁移失败后继续处理 / 维护延期导致方案过期 / 并发只允许一条合法迁移。
 */
@SpringBootTest
class MaintenanceFlowIntegrationTest {

    @Autowired private MaintenanceService service;
    @Autowired private BatchService batchService;
    @Autowired private FermenterRepository fermenterRepository;
    @Autowired private FermenterMaintenanceRepository maintenanceRepository;
    @Autowired private BatchRepository batchRepository;
    @Autowired private TankTransferPlanRepository planRepository;
    @Autowired private TestFixtures fixtures;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 5, 8, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 6, 8, 0);

    private Fermenter tank(String no, int cap, BigDecimal tmin, BigDecimal tmax, String status, String batch) {
        Fermenter t = new Fermenter();
        t.setTankNo(no);
        t.setCapacityL(new BigDecimal(cap));
        t.setMaterial("CONICAL");
        t.setTempMin(tmin);
        t.setTempMax(tmax);
        t.setCooling("GLYCOL");
        t.setStatus(status);
        t.setCurrentBatchNo(batch);
        t.setUpdatedAt(LocalDateTime.now());
        return fermenterRepository.save(t);
    }

    private Batch occupyingBatch(String no, String tankNo, int volume, String status,
                                 BigDecimal tmin, BigDecimal tmax) {
        Batch b = fixtures.batch(no, status, false);
        b.setFermenterNo(tankNo);
        b.setVolumeL(new BigDecimal(volume));
        b.setFermentStart(LocalDateTime.now().minusDays(3));
        b.setExpectedMature(LocalDate.now().plusDays(10));
        b.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(b);
        // 夹具直接同步罐当前批次
        fermenterRepository.findByTankNo(tankNo).ifPresent(t -> {
            t.setCurrentBatchNo(no);
            t.setStatus(Views.tankStatusOfBatch(status));
            fermenterRepository.save(t);
        });
        return b;
    }

    private MaintenanceDtos.MaintenanceSaveReq windowReq(String tankNo, String type,
                                                          LocalDateTime from, LocalDateTime to,
                                                          String reason) {
        MaintenanceDtos.MaintenanceSaveReq req = new MaintenanceDtos.MaintenanceSaveReq();
        req.setTankNo(tankNo);
        req.setMaintType(type);
        req.setStartAt(from);
        req.setExpectedRecoverAt(to);
        req.setReason(reason);
        req.setImpactScope("测试影响范围");
        req.setOperator("设备-老郑");
        return req;
    }

    private MaintenanceDtos.TransferProposeReq proposeReq(String target, LocalDateTime moveAt) {
        MaintenanceDtos.TransferProposeReq req = new MaintenanceDtos.TransferProposeReq();
        req.setTargetTankNo(target);
        req.setPlannedMoveAt(moveAt);
        req.setOperator("设备-老郑");
        return req;
    }

    private MaintenanceDtos.TransferConfirmReq confirmReq(Views.TransferPlanView p) {
        MaintenanceDtos.TransferConfirmReq req = new MaintenanceDtos.TransferConfirmReq();
        req.setPlanId(p.getId());
        req.setOperator("设备-老郑");
        req.setExpectedVersion(p.getVersion());
        return req;
    }

    // 1. 计划维护无冲突：空闲罐的窗口时间线 = NO_CONFLICT
    @Test
    void plannedMaintenanceOnIdleTank_noConflict() {
        tank("MT-01", 1000, bd("10"), bd("20"), "IDLE", null);
        Views.MaintenanceView w = service.register(windowReq("MT-01", "PLANNED", T0, T1, "季度保养"));

        Views.MaintenanceTimelineView tl = service.timeline(w.getId());
        assertEquals("NO_CONFLICT", tl.getOverallResult());
        assertTrue(tl.getItems().isEmpty());
        assertEquals(1, tl.getMaintenance().getEvents().size());
    }

    // 2. 占罐冲突成功迁移：候选罐全条件满足 → 五步执行完，批次换罐、曲线阶段保留、历史罐号两段
    @Test
    void occupyingConflict_transferSucceeds_keepsCurveStageAndHistory() {
        tank("MT-11", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-12", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b = occupyingBatch("MB-01", "MT-11", 900, "FERMENT", bd("16"), bd("22"));
        // 曲线与阶段时间在迁移前后必须原样保留
        fixtures.addReading(b.getId(), "D1", bd("18.5"), bd("12.0"));
        fixtures.addReading(b.getId(), "D2", bd("19.5"), bd("7.0"));
        LocalDateTime fermentStart = b.getFermentStart();

        Views.MaintenanceView w = service.register(windowReq("MT-11", "EMERGENCY", T0, T1, "夹套渗漏"));
        Views.MaintenanceTimelineView tl = service.timeline(w.getId());
        assertEquals("NEED_TRANSFER", tl.getOverallResult());
        Views.TimelineItemView item = tl.getItems().get(0);
        assertTrue(item.getCandidates().stream().anyMatch(c -> "MT-12".equals(c.getTankNo()) && c.isSuitable()));

        Views.TransferPlanView plan = service.propose(w.getId(), b.getId(), proposeReq("MT-12", T0.minusHours(2)));
        plan = service.confirm(plan.getId(), confirmReq(plan));
        assertEquals("EXECUTING", plan.getStatus());
        assertEquals(1, plan.getAttemptCount());

        // 五步全部完成前，批次仍在原罐、目标罐仍空闲（不会两罐同时占用）
        service.completeStep(plan.getId(), 1, "设备-老郑");
        Batch mid = batchRepository.findById(b.getId()).orElseThrow();
        assertEquals("MT-11", mid.getFermenterNo());
        Fermenter targetMid = fermenterRepository.findByTankNo("MT-12").orElseThrow();
        assertNull(targetMid.getCurrentBatchNo());

        for (int step = 2; step <= 5; step++) {
            service.completeStep(plan.getId(), step, "设备-老郑");
        }

        Batch after = batchRepository.findById(b.getId()).orElseThrow();
        assertEquals("MT-12", after.getFermenterNo());
        assertEquals("FERMENT", after.getStatus());           // 阶段不重置
        // 阶段时间保留（H2 DATETIME 存到微秒，比较时统一截断到秒）
        assertEquals(fermentStart.toLocalDate(), after.getFermentStart().toLocalDate());
        assertEquals(fermentStart.toLocalTime().withNano(0),
                after.getFermentStart().toLocalTime().withNano(0));
        // 曲线不拆不断（用服务视图读取，避免事务外懒加载）
        Views.BatchView afterView = batchService.get(b.getId());
        assertEquals(2, afterView.getCurve().size());
        // 迁移后新测温继续记到同一批次
        fixtures.addReading(after.getId(), "D3", bd("19.0"), bd("4.5"));
        assertEquals(3, batchService.get(b.getId()).getCurve().size());

        Fermenter source = fermenterRepository.findByTankNo("MT-11").orElseThrow();
        Fermenter target = fermenterRepository.findByTankNo("MT-12").orElseThrow();
        assertNull(source.getCurrentBatchNo());
        assertEquals("CLEANING", source.getStatus());
        assertEquals("MB-01", target.getCurrentBatchNo());

        List<Views.TankHistoryView> hist = batchService.tankHistory(b.getId());
        assertEquals(2, hist.size());
        assertEquals("MT-11", hist.get(0).getTankNo());
        assertNotNull(hist.get(0).getLeftAt());
        assertEquals("TRANSFER_OUT", hist.get(0).getLeaveReason());
        assertEquals("MT-12", hist.get(1).getTankNo());
        assertEquals("TRANSFER", hist.get(1).getEnterReason());
        assertTrue(hist.get(1).isCurrent());

        Views.TransferPlanView done = service.planDetail(plan.getId());
        assertEquals("EXECUTED", done.getStatus());
        assertEquals(5, done.getSteps().size());
        assertTrue(done.getSteps().stream().allMatch(s -> "DONE".equals(s.getStatus())));
        // 已执行的迁罐不能取消/删除
        assertThrows(BizException.class, () ->
                service.cancelPlan(done.getId(), cancelReq("想删掉")));
    }

    // 3. 容量或温区不兼容：所有候选都不适配 → 无法处理；强制指定不适配罐被服务端拒绝
    @Test
    void capacityAndTempIncompatible_unresolvable() {
        tank("MT-21", 3000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-22", 900, bd("16"), bd("22"), "IDLE", null);   // 容量不足
        tank("MT-23", 3000, bd("0"), bd("6"), "IDLE", null);    // 温区不兼容
        tank("MT-24", 3000, bd("16"), bd("22"), "DISABLED", null); // 停用
        Batch b = occupyingBatch("MB-02", "MT-21", 2200, "FERMENT", bd("16"), bd("22"));

        Views.MaintenanceView w = service.register(windowReq("MT-21", "PLANNED", T0, T1, "换阀"));
        Views.MaintenanceTimelineView tl = service.timeline(w.getId());
        assertEquals("UNRESOLVABLE", tl.getOverallResult());
        Views.TargetCandidateView small = tl.getItems().get(0).getCandidates().stream()
                .filter(c -> "MT-22".equals(c.getTankNo())).findFirst().orElseThrow();
        assertTrue(small.getMismatchReasons().stream().anyMatch(r -> r.contains("容量不足")));
        Views.TargetCandidateView cold = tl.getItems().get(0).getCandidates().stream()
                .filter(c -> "MT-23".equals(c.getTankNo())).findFirst().orElseThrow();
        assertTrue(cold.getMismatchReasons().stream().anyMatch(r -> r.contains("温区不兼容")));

        // 自动选罐：没有满足全部条件的目标
        assertThrows(BizException.class, () ->
                service.propose(w.getId(), b.getId(), proposeReq(null, T0.minusHours(2))));
        // 强制指定温区不兼容罐：不是前端筛掉，服务端硬拦
        BizException ex = assertThrows(BizException.class, () ->
                service.propose(w.getId(), b.getId(), proposeReq("MT-23", T0.minusHours(2))));
        assertTrue(ex.getMessage().contains("温区"));
    }

    // 4. 目标罐被抢占：两个不同批次同时确认迁入同一目标罐，只有一条成功，另一条过期需重算
    @Test
    void targetTankPreempted_onlyOneTransferSucceeds() throws Exception {
        tank("MT-31", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-32", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-33", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b1 = occupyingBatch("MB-03", "MT-31", 900, "FERMENT", bd("16"), bd("22"));
        Batch b2 = occupyingBatch("MB-04", "MT-32", 900, "FERMENT", bd("16"), bd("22"));

        Views.MaintenanceView w1 = service.register(
                windowReq("MT-31", "EMERGENCY", T0, T1, "停机1"));
        Views.MaintenanceView w2 = service.register(
                windowReq("MT-32", "EMERGENCY", T0.plusMinutes(30), T1, "停机2"));
        Views.TransferPlanView p1 = service.propose(w1.getId(), b1.getId(), proposeReq("MT-33", T0.minusHours(2)));
        Views.TransferPlanView p2 = service.propose(w2.getId(), b2.getId(),
                proposeReq("MT-33", T0.plusMinutes(30).minusHours(2)));

        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        for (Views.TransferPlanView p : List.of(p1, p2)) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.confirm(p.getId(), confirmReq(p));
                    ok.incrementAndGet();
                } catch (StaleTransferException e) {
                    stale.incrementAndGet();
                } catch (Exception e) {
                    // 乐观锁/行锁并发失败也计为未成功
                    stale.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, ok.get(), "同一目标罐只能有一条迁移成功");
        assertEquals(1, stale.get());

        // 一条 EXECUTING（继续五步可完成），另一条 EXPIRED 且带过期原因
        List<TankTransferPlan> finalPlans = planRepository.findAllByOrderByIdDesc();
        long executing = finalPlans.stream().filter(x -> "EXECUTING".equals(x.getStatus())).count();
        TankTransferPlan expired = finalPlans.stream()
                .filter(x -> "EXPIRED".equals(x.getStatus())).findFirst().orElseThrow();
        assertEquals(1, executing);
        assertNotNull(expired.getExpiredReason());
        assertTrue(expired.getExpiredReason().contains("抢占") || expired.getExpiredReason().contains("占用"));

        // 过期方案不能再确认，必须重新计算
        Long expiredId = expired.getId();
        assertThrows(StaleTransferException.class, () ->
                service.confirm(expiredId, confirmReq(service.planDetail(expiredId))));
    }

    // 5. 迁移中途失败：回退到待处理并保留进度，继续处理后成功，历史不丢、尝试次数累加
    @Test
    void transferFails_thenResumeSucceeds() {
        tank("MT-41", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-42", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b = occupyingBatch("MB-05", "MT-41", 900, "CONDITION", bd("16"), bd("22"));

        Views.MaintenanceView w = service.register(windowReq("MT-41", "EMERGENCY", T0, T1, "探头漂移"));
        Views.TransferPlanView plan = service.propose(w.getId(), b.getId(), proposeReq("MT-42", T0.minusHours(2)));
        service.confirm(plan.getId(), confirmReq(plan));
        service.completeStep(plan.getId(), 1, "设备-老郑");
        service.completeStep(plan.getId(), 2, "设备-老郑");

        MaintenanceDtos.TransferFailReq failReq = new MaintenanceDtos.TransferFailReq();
        failReq.setFailedStep(3);
        failReq.setReason("行车抱闸报警");
        failReq.setOperator("设备-老郑");
        failReq.setExpectedVersion(2); // confirm 后版本号：propose 0 → confirm 1 → fail 提交基于最新读取
        // 版本号以实时读取为准，避免硬编码：先取详情
        Views.TransferPlanView running = service.planDetail(plan.getId());
        failReq.setExpectedVersion(running.getVersion());
        Views.TransferPlanView failed = service.fail(plan.getId(), failReq);

        assertEquals("PENDING", failed.getStatus());
        assertEquals(3, failed.getFailedStep());
        assertTrue(failed.getFailReason().contains("行车抱闸"));
        // 回退后批次仍在原罐、目标罐仍空闲，不存在两罐同时占用
        assertEquals("MT-41", batchRepository.findById(b.getId()).orElseThrow().getFermenterNo());
        assertNull(fermenterRepository.findByTankNo("MT-42").orElseThrow().getCurrentBatchNo());
        // 失败后不能重复确认，只能继续处理
        assertThrows(BizException.class, () ->
                service.confirm(plan.getId(), confirmReq(failed)));

        MaintenanceDtos.TransferResumeReq resumeReq = new MaintenanceDtos.TransferResumeReq();
        resumeReq.setOperator("设备-老郑");
        resumeReq.setExpectedVersion(failed.getVersion());
        Views.TransferPlanView resumed = service.resume(plan.getId(), resumeReq);
        assertEquals(2, resumed.getAttemptCount());
        for (int step = 1; step <= 5; step++) {
            service.completeStep(plan.getId(), step, "设备-老郑");
        }
        Views.TransferPlanView done = service.planDetail(plan.getId());
        assertEquals("EXECUTED", done.getStatus());
        assertEquals(10, done.getSteps().size(), "两轮各五步都保留");
        assertTrue(done.getSteps().stream().anyMatch(s -> "FAILED".equals(s.getStatus())));
        assertTrue(done.getSteps().stream().anyMatch(s -> "ROLLED_BACK".equals(s.getStatus())));
        assertEquals("MT-42", batchRepository.findById(b.getId()).orElseThrow().getFermenterNo());
    }

    // 6. 维护延期：旧方案判过期（409），重新按新窗口计算的方案可确认；已发生历史不改写
    @Test
    void maintenanceExtended_oldPlanExpired_recomputeWorks() {
        tank("MT-51", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-52", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b = occupyingBatch("MB-06", "MT-51", 900, "FERMENT", bd("16"), bd("22"));

        LocalDateTime from = T0;
        LocalDateTime to = T1;
        Views.MaintenanceView w = service.register(windowReq("MT-51", "PLANNED", from, to, "压力表更换"));
        Views.TransferPlanView plan = service.propose(w.getId(), b.getId(), proposeReq("MT-52", from.minusHours(2)));

        // 延期：恢复时间推后 12 小时
        MaintenanceDtos.MaintenanceAdjustReq adj = new MaintenanceDtos.MaintenanceAdjustReq();
        adj.setExpectedRecoverAt(to.plusHours(12));
        adj.setReason("配件延期到货");
        adj.setOperator("设备-老郑");
        adj.setExpectedVersion(w.getVersion());
        Views.MaintenanceView adjusted = service.adjust(w.getId(), adj);
        assertEquals(to.plusHours(12), adjusted.getExpectedRecoverAt());

        // 旧待确认方案自动过期
        TankTransferPlan old = planRepository.findById(plan.getId()).orElseThrow();
        assertEquals("EXPIRED", old.getStatus());
        assertNotNull(old.getExpiredReason());
        // 强行确认旧方案 → 409
        assertThrows(StaleTransferException.class, () ->
                service.confirm(plan.getId(), confirmReq(service.planDetail(plan.getId()))));

        // 按新窗口重新计算 → 新方案可确认
        FermenterMaintenance renewed = maintenanceRepository.findById(w.getId()).orElseThrow();
        Views.TransferPlanView plan2 = service.propose(w.getId(), b.getId(),
                proposeReq("MT-52", renewed.getStartAt().minusHours(2)));
        service.confirm(plan2.getId(), confirmReq(plan2));
        for (int step = 1; step <= 5; step++) {
            service.completeStep(plan2.getId(), step, "设备-老郑");
        }
        assertEquals("EXECUTED", service.planDetail(plan2.getId()).getStatus());
        assertEquals("MT-52", batchRepository.findById(b.getId()).orElseThrow().getFermenterNo());
        // 旧方案记录仍在（EXPIRED 历史保留）
        assertEquals("EXPIRED", planRepository.findById(plan.getId()).orElseThrow().getStatus());
        // 维护流水含 RESCHEDULE/EXTEND 一条
        assertTrue(service.get(w.getId()).getEvents().stream()
                .anyMatch(e -> "EXTEND".equals(e.getEventType()) || "RESCHEDULE".equals(e.getEventType())));
    }

    // 7. 已开始的维护不能取消/删除，只能提前结束；提前结束让待处理方案过期但不动已执行历史
    @Test
    void startedMaintenanceCannotCancel_butCanFinishEarly() {
        tank("MT-61", 1000, bd("16"), bd("22"), "IDLE", null);
        // 登记一个开始时间已过的窗口 → 直接 IN_PROGRESS
        Views.MaintenanceView w = service.register(windowReq("MT-61", "EMERGENCY",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(4), "突发"));
        assertEquals("IN_PROGRESS", w.getStatus());

        MaintenanceDtos.MaintenanceCancelReq cancel = new MaintenanceDtos.MaintenanceCancelReq();
        cancel.setReason("想取消");
        cancel.setOperator("设备-老郑");
        cancel.setExpectedVersion(w.getVersion());
        assertTrue(assertThrows(BizException.class, () -> service.cancel(w.getId(), cancel))
                .getMessage().contains("不能直接删除"));

        MaintenanceDtos.MaintenanceActionReq finish = new MaintenanceDtos.MaintenanceActionReq();
        finish.setOperator("设备-老郑");
        finish.setReason("提前修好");
        finish.setExpectedVersion(w.getVersion());
        Views.MaintenanceView ended = service.finishEarly(w.getId(), finish);
        assertEquals("FINISHED", ended.getStatus());
        assertNotNull(ended.getActualRecoverAt());
        // 没有删除接口、记录仍在
        assertTrue(maintenanceRepository.findById(w.getId()).isPresent());
    }

    // 8. 两人同时确认同一批次的同一条方案：只有一次成功（悲观锁 + 乐观版本）
    @Test
    void concurrentConfirmSamePlan_singleSuccess() throws Exception {
        tank("MT-71", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-72", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b = occupyingBatch("MB-07", "MT-71", 900, "FERMENT", bd("16"), bd("22"));
        Views.MaintenanceView w = service.register(windowReq("MT-71", "EMERGENCY", T0, T1, "漏液"));
        Views.TransferPlanView plan = service.propose(w.getId(), b.getId(), proposeReq("MT-72", T0.minusHours(2)));

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.confirm(plan.getId(), confirmReq(plan));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, ok.get());
        assertEquals(n - 1, rejected.get());
        assertEquals("EXECUTING", planRepository.findById(plan.getId()).orElseThrow().getStatus());
    }

    // 9. 确认前目标罐被别人手工占用/停用：旧方案 409 过期并提示重算（不是前端隐藏）
    @Test
    void targetChangedBeforeConfirm_planStale() {
        tank("MT-81", 1000, bd("16"), bd("22"), "FERMENTING", null);
        tank("MT-82", 1200, bd("15"), bd("23"), "IDLE", null);
        Batch b = occupyingBatch("MB-08", "MT-81", 900, "FERMENT", bd("16"), bd("22"));
        Views.MaintenanceView w = service.register(windowReq("MT-81", "EMERGENCY", T0, T1, "漏液"));
        Views.TransferPlanView plan = service.propose(w.getId(), b.getId(), proposeReq("MT-82", T0.minusHours(2)));

        // 别人把目标罐停用
        Fermenter target = fermenterRepository.findByTankNo("MT-82").orElseThrow();
        target.setStatus("DISABLED");
        fermenterRepository.save(target);

        StaleTransferException ex = assertThrows(StaleTransferException.class,
                () -> service.confirm(plan.getId(), confirmReq(plan)));
        assertTrue(ex.getMessage().contains("停用") || ex.getMessage().contains("过期"));
        assertFalse(planRepository.findById(plan.getId()).orElseThrow().getStatus().equals("EXECUTING"));
    }

    // 10. 维护窗口内手工安排入罐被服务端拒绝（不是只在前端筛掉）
    @Test
    void cannotAssignBatchIntoTankUnderMaintenance() {
        Fermenter t = tank("MT-91", 1000, bd("10"), bd("20"), "IDLE", null);
        // 开始时间已过 → 登记后直接为 IN_PROGRESS，当前时刻落在窗口内
        Views.MaintenanceView w = service.register(windowReq("MT-91", "EMERGENCY",
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(4), "突发停机"));
        assertEquals("IN_PROGRESS", w.getStatus());
        BizException ex = assertThrows(BizException.class, () -> service.assertTankAvailableNow(t.getTankNo()));
        assertTrue(ex.getMessage().contains("维护"));
    }

    private MaintenanceDtos.TransferCancelReq cancelReq(String reason) {
        MaintenanceDtos.TransferCancelReq req = new MaintenanceDtos.TransferCancelReq();
        req.setReason(reason);
        req.setOperator("设备-老郑");
        return req;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
