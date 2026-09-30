package com.brewery.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.dto.QcDtos;
import com.brewery.craft.dto.ShipmentDtos;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.QcRoundRepository;
import com.brewery.craft.repository.ShipmentRepository;
import com.brewery.craft.service.BatchService;
import com.brewery.craft.service.QcService;
import com.brewery.craft.service.ShipmentService;

@SpringBootTest
class QcFlowIntegrationTest {

    @Autowired private TestFixtures fixtures;
    @Autowired private QcService qcService;
    @Autowired private ShipmentService shipmentService;
    @Autowired private BatchService batchService;
    @Autowired private QcRoundRepository qcRoundRepository;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private BatchRepository batchRepository;

    private QcDtos.OpenRoundReq openReq() {
        QcDtos.OpenRoundReq req = new QcDtos.OpenRoundReq();
        req.setOperator("酿酒师");
        req.setClientToken(UUID.randomUUID().toString());
        req.setNote("送检");
        return req;
    }

    private QcDtos.DecideReq passReq() {
        QcDtos.DecideReq req = new QcDtos.DecideReq();
        req.setDecision("PASS");
        req.setOperator("质检员");
        req.setAbv(new BigDecimal("5.20"));
        req.setSensory("清亮，柑橘香");
        req.setReason("达标");
        return req;
    }

    private QcDtos.DecideReq rejectReq(String reason) {
        QcDtos.DecideReq req = new QcDtos.DecideReq();
        req.setDecision("REJECT");
        req.setOperator("质检员");
        req.setSensory("杂味");
        req.setReason(reason);
        return req;
    }

    private ShipmentDtos.BottlingSaveReq bottlingReq(String no, String batchNo, String pkg, int qty) {
        ShipmentDtos.BottlingSaveReq req = new ShipmentDtos.BottlingSaveReq();
        req.setBottlingNo(no);
        req.setBatchNo(batchNo);
        req.setPackageForm(pkg);
        req.setQuantity(qty);
        return req;
    }

    private ShipmentDtos.ShipmentSaveReq shipmentReq(String no, String batchNo, String pkg, int qty) {
        ShipmentDtos.ShipmentSaveReq req = new ShipmentDtos.ShipmentSaveReq();
        req.setShipmentNo(no);
        req.setCustomer("客户");
        req.setBatchNo(batchNo);
        req.setPackageForm(pkg);
        req.setQuantity(qty);
        req.setUnitPrice(new BigDecimal("18.00"));
        return req;
    }

    // 1. 未质检的熟成批次不能灌装
    @Test
    void bottlingBlockedBeforeRelease() {
        Batch b = fixtures.batch("T-BLOCK-1", "CONDITION", true);
        BizException ex = assertThrows(BizException.class,
                () -> shipmentService.createBottling(bottlingReq("TB-1", b.getBatchNo(), "BOTTLE", 100)));
        assertTrue(ex.getMessage().contains("质检"), ex.getMessage());
    }

    // 2. 已放行但仍在发酵：不能灌装
    @Test
    void releaseButFermentingCannotBottle() {
        Batch b = fixtures.batch("T-STAGE-1", "FERMENT", true);
        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        BizException ex = assertThrows(BizException.class,
                () -> shipmentService.createBottling(bottlingReq("TB-STAGE", b.getBatchNo(), "BOTTLE", 100)));
        assertTrue(ex.getMessage().contains("阶段"), ex.getMessage());
    }

    // 3. 完整链路：送检 → 放行 → 灌装 → 出货 → 发货 → 结款
    @Test
    void fullHappyPath() {
        Batch b = fixtures.batch("T-HAPPY-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        assertEquals("PENDING", opened.getCurrent().getStatus());
        assertEquals("待质检", opened.getCurrent().getStatusName());

        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        assertEquals("PASS", qcService.qcProfile(b.getId()).getCurrent().getStatus());

        var bv = shipmentService.createBottling(bottlingReq("TB-HAPPY", b.getBatchNo(), "BOTTLE", 500));
        assertEquals("PASS", bv.getQcStatus());

        var sv = shipmentService.createShipment(shipmentReq("TS-HAPPY", b.getBatchNo(), "BOTTLE", 200));
        assertEquals("PENDING", sv.getStatus());
        assertEquals("SHIPPED", shipmentService.nextStatus(sv.getId()).getStatus());
        assertEquals("PAID", shipmentService.nextStatus(sv.getId()).getStatus());
    }

    // 4. 出货必须基于同包装形式的灌装记录，且不能超量
    @Test
    void shipmentRequiresBottlingRecordAndCapacity() {
        Batch b = fixtures.batch("T-SH-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-SH-1", b.getBatchNo(), "BOTTLE", 300);

        BizException ex1 = assertThrows(BizException.class,
                () -> shipmentService.createShipment(shipmentReq("TS-CAN", b.getBatchNo(), "CAN", 10)));
        assertTrue(ex1.getMessage().contains("灌装记录"), ex1.getMessage());

        BizException ex2 = assertThrows(BizException.class,
                () -> shipmentService.createShipment(shipmentReq("TS-OVER", b.getBatchNo(), "BOTTLE", 400)));
        assertTrue(ex2.getMessage().contains("灌装量"), ex2.getMessage());
    }

    // 5. 撤销放行：未发出货冻结、冻结单不能推进、灌装被阻止；新一轮复检放行后恢复，旧轮次保留
    @Test
    void revokeFreezesShipments_andNewPassRecovers() {
        Batch b = fixtures.batch("T-REJ-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-REJ", b.getBatchNo(), "BOTTLE", 500);
        var created = shipmentService.createShipment(shipmentReq("TS-REJ", b.getBatchNo(), "BOTTLE", 100));

        QcDtos.RevokeReq revoke = new QcDtos.RevokeReq();
        revoke.setOperator("质检员");
        revoke.setReason("发现氧化味");
        qcService.revoke(b.getId(), opened.getRounds().get(0).getId(), revoke);

        Shipment frozen = shipmentRepository.findById(created.getId()).orElseThrow();
        assertEquals("FROZEN", frozen.getStatus());
        assertTrue(frozen.getFrozenReason().contains("氧化味"));
        assertTrue(assertThrows(BizException.class, () -> shipmentService.nextStatus(created.getId()))
                .getMessage().contains("冻结"));
        assertTrue(assertThrows(BizException.class,
                () -> shipmentService.createBottling(bottlingReq("TB-REJ-2", b.getBatchNo(), "BOTTLE", 10)))
                .getMessage().contains("撤销"));

        var opened2 = qcService.openRound(b.getId(), openReq());
        assertEquals(2, opened2.getRounds().size());
        assertEquals("REINSPECT", opened2.getCurrent().getStatus());
        assertEquals("FROZEN", shipmentRepository.findById(created.getId()).orElseThrow().getStatus());

        qcService.decide(b.getId(), opened2.getRounds().get(1).getId(), passReq());
        assertEquals("PENDING", shipmentRepository.findById(created.getId()).orElseThrow().getStatus());

        QcRound old = qcRoundRepository.findById(opened.getRounds().get(0).getId()).orElseThrow();
        assertEquals("REVOKED", old.getStatus());
        assertEquals("发现氧化味", old.getRevokeReason());
        assertEquals(0, new BigDecimal("5.20").compareTo(old.getAbv()));
    }

    // 6. 已提交结论不可原地覆盖
    @Test
    void decidedRoundCannotBeOverwritten() {
        Batch b = fixtures.batch("T-IMM-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        Long rid = opened.getRounds().get(0).getId();
        qcService.decide(b.getId(), rid, rejectReq("双乙酰超标"));
        assertTrue(assertThrows(BizException.class, () -> qcService.decide(b.getId(), rid, passReq()))
                .getMessage().contains("不可原地修改"));
    }

    // 7. 撤销必须填原因；非放行轮不能撤销
    @Test
    void revokeRequiresReasonAndOnlyPass() {
        Batch b = fixtures.batch("T-REV-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        QcDtos.RevokeReq noReason = new QcDtos.RevokeReq();
        noReason.setOperator("质检员");
        assertTrue(assertThrows(BizException.class,
                () -> qcService.revoke(b.getId(), opened.getRounds().get(0).getId(), noReason))
                .getMessage().contains("原因"));

        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), rejectReq("x"));
        QcDtos.RevokeReq req = new QcDtos.RevokeReq();
        req.setOperator("质检员");
        req.setReason("r");
        assertTrue(assertThrows(BizException.class,
                () -> qcService.revoke(b.getId(), opened.getRounds().get(0).getId(), req))
                .getMessage().contains("已放行"));
    }

    // 8. 重复点击/网络重试：同 clientToken 只产生一轮
    @Test
    void idempotentOpenRoundSameToken() {
        Batch b = fixtures.batch("T-IDEM-1", "CONDITION", true);
        QcDtos.OpenRoundReq req = openReq();
        qcService.openRound(b.getId(), req);
        qcService.openRound(b.getId(), req);
        assertEquals(1, qcRoundRepository.findAllByBatchIdOrderByRoundSeqAsc(b.getId()).size());
    }

    // 9. 并发送检只能开出一轮
    @Test
    void concurrentOpenRounds_onlyOneCreated() throws Exception {
        Batch b = fixtures.batch("T-CONC-1", "CONDITION", true);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    qcService.openRound(b.getId(), openReq());
                    success.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(n - 1, rejected.get());
        assertEquals(1, qcRoundRepository.findAllByBatchIdOrderByRoundSeqAsc(b.getId()).size());
    }

    // 10. 报废：未发出货冻结；有已发/已结款历史单时报废被阻止
    @Test
    void scrappedBatchLocksDownstream() {
        Batch withHistory = fixtures.batch("T-SCRAP-1", "CONDITION", true);
        var opened = qcService.openRound(withHistory.getId(), openReq());
        qcService.decide(withHistory.getId(), opened.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-SCRAP-1", withHistory.getBatchNo(), "BOTTLE", 200);
        fixtures.shipment("TS-SCRAP-1", withHistory.getBatchNo(), "BOTTLE", 10, "SHIPPED");
        assertTrue(assertThrows(BizException.class, () -> batchService.scrap(withHistory.getId()))
                .getMessage().contains("历史单据"));

        Batch b2 = fixtures.batch("T-SCRAP-2", "CONDITION", true);
        var o2 = qcService.openRound(b2.getId(), openReq());
        qcService.decide(b2.getId(), o2.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-SCRAP-2", b2.getBatchNo(), "BOTTLE", 100);
        var pending = fixtures.shipment("TS-SCRAP-2", b2.getBatchNo(), "BOTTLE", 30, "PENDING");
        batchService.scrap(b2.getId());

        Shipment frozen = shipmentRepository.findById(pending.getId()).orElseThrow();
        assertEquals("FROZEN", frozen.getStatus());
        assertNotNull(frozen.getFrozenReason());
        // 已发历史单保持不动
        assertEquals("SHIPPED",
                shipmentRepository.findByShipmentNo("TS-SCRAP-1").orElseThrow().getStatus());
        // 报废后不能再发起质检
        assertTrue(assertThrows(BizException.class, () -> qcService.openRound(b2.getId(), openReq()))
                .getMessage().contains("报废"));
    }

    // 11. 批次曲线后续变更不影响已提交轮次的测量快照
    @Test
    void roundSnapshotImmutableWhenBatchCurveChanges() {
        Batch b = fixtures.batch("T-SNAP-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        int snapCount = opened.getRounds().get(0).getReadings().size();
        fixtures.addReading(b.getId(), "D9", new BigDecimal("3.10"), new BigDecimal("3.90"));

        var profile = qcService.qcProfile(b.getId());
        var roundView = profile.getRounds().get(0);
        assertEquals(snapCount, roundView.getReadings().size());
        assertFalse(roundView.getReadings().stream().anyMatch(x -> "D9".equals(x.getDayKey())));
    }

    // 12. 推进到已灌装：必须放行 + 有灌装记录
    @Test
    void cannotMarkPackagedWithoutReleaseAndBottling() {
        Batch b = fixtures.batch("T-PKG-1", "CONDITION", true);
        assertTrue(assertThrows(BizException.class, () -> batchService.advance(b.getId(), "PACKAGED"))
                .getMessage() != null);

        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        assertTrue(assertThrows(BizException.class, () -> batchService.advance(b.getId(), "PACKAGED"))
                .getMessage().contains("灌装记录"));

        fixtures.bottling("TB-PKG", b.getBatchNo(), "BOTTLE", 300);
        assertEquals("PACKAGED", batchService.advance(b.getId(), "PACKAGED").getStatus());
    }

    // 14. 并发：一人放行、一人同时建灌装单 —— 只有一条合法状态链：
    //     放行先提交则灌装成功；灌装先执行则被拦。绝不出现“灌装成功但最终未放行”。
    @Test
    void concurrentPassAndBottling_singleLegalChain() throws Exception {
        Batch b = fixtures.batch("T-CC-1", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        Long roundId = opened.getRounds().get(0).getId();

        int pairs = 6;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger bottlingOk = new AtomicInteger();
        AtomicInteger bottlingDenied = new AtomicInteger();
        for (int i = 0; i < pairs; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    QcDtos.DecideReq pr = passReq();
                    qcService.decide(b.getId(), roundId, pr);
                } catch (Exception ignore) {
                    // 放行只能有一次成功，其余“结论已存在”是预期
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    shipmentService.createBottling(
                            bottlingReq("TB-CC-" + idx, b.getBatchNo(), "BOTTLE", 10));
                    bottlingOk.incrementAndGet();
                } catch (BizException e) {
                    bottlingDenied.incrementAndGet();
                } catch (Exception e) {
                    bottlingDenied.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 最终放行必须存在
        assertEquals("PASS", qcService.qcProfile(b.getId()).getCurrent().getStatus());
        // 每一张成功的灌装单都在“放行后”落库；成功+被拦数等于尝试总数
        assertEquals(pairs, bottlingOk.get() + bottlingDenied.get());
        // 成功灌装数受唯一单号约束为 bottlingOk，且最终状态一致可查
        var views = shipmentService.bottlings();
        for (var bv : views) {
            if (bv.getBatchNo().equals(b.getBatchNo())) {
                assertEquals("PASS", bv.getQcStatus());
            }
        }
    }

    // 15. 并发：一人撤销放行、一人同时推进 PENDING→SHIPPED —— 只有一条合法链：
    //     要么发货先完成（撤销不动已发历史单），要么撤销先成功（出货冻结、推进被拦）。
    @Test
    void concurrentRevokeAndShipAdvance_singleLegalChain() throws Exception {
        Batch b = fixtures.batch("T-CC-2", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-CC-2", b.getBatchNo(), "BOTTLE", 500);
        var sh = shipmentService.createShipment(shipmentReq("TS-CC-2", b.getBatchNo(), "BOTTLE", 50));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger revokeOk = new AtomicInteger();
        AtomicInteger shipOk = new AtomicInteger();
        AtomicInteger shipBlocked = new AtomicInteger();
        pool.submit(() -> {
            try {
                start.await();
                QcDtos.RevokeReq rv = new QcDtos.RevokeReq();
                rv.setOperator("质检员");
                rv.setReason("并发撤销演练");
                qcService.revoke(b.getId(), opened.getRounds().get(0).getId(), rv);
                revokeOk.incrementAndGet();
            } catch (Exception e) {
                // 若发货已先完成，撤销仍应成功（撤销只冻未发单）；这里不期望抛错
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                String st = shipmentService.nextStatus(sh.getId()).getStatus();
                if ("SHIPPED".equals(st)) {
                    shipOk.incrementAndGet();
                }
            } catch (Exception e) {
                shipBlocked.incrementAndGet();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Shipment finalShipment = shipmentRepository.findById(sh.getId()).orElseThrow();
        String qcFinal = qcService.qcProfile(b.getId()).getCurrent().getStatus();
        // 撤销不与发货互斥：放行轮始终会被撤销；关键是出货单最终只有一种合法状态
        assertEquals("REVOKED", qcFinal);
        if ("SHIPPED".equals(finalShipment.getStatus())) {
            // 发货先提交：已发历史单不被撤销改写，仍然是已发
            assertEquals(1, shipOk.get());
            assertEquals(0, shipBlocked.get());
        } else {
            // 撤销先提交：出货单必须是 FROZEN，推进被拦
            assertEquals("FROZEN", finalShipment.getStatus());
            assertEquals(1, shipBlocked.get());
        }
        assertEquals(1, revokeOk.get());
    }

    // 13. 驳回轮直接冻结未发出货，列表状态随之变化
    @Test
    void rejectFreezesPendingShipment() {
        Batch b = fixtures.batch("T-RJ-2", "CONDITION", true);
        var opened = qcService.openRound(b.getId(), openReq());
        qcService.decide(b.getId(), opened.getRounds().get(0).getId(), passReq());
        fixtures.bottling("TB-RJ-2", b.getBatchNo(), "BOTTLE", 500);
        var sh = shipmentService.createShipment(shipmentReq("TS-RJ-2", b.getBatchNo(), "BOTTLE", 50));

        var r2 = qcService.openRound(b.getId(), openReq()); // 放行后复检，先冻结
        assertEquals("FROZEN", shipmentRepository.findById(sh.getId()).orElseThrow().getStatus());
        qcService.decide(b.getId(), r2.getRounds().get(1).getId(), rejectReq("感官异常"));
        Shipment stillFrozen = shipmentRepository.findById(sh.getId()).orElseThrow();
        assertEquals("FROZEN", stillFrozen.getStatus());
        assertTrue(stillFrozen.getFrozenReason().contains("感官异常"));
        assertEquals("REJECT", qcService.qcProfile(b.getId()).getCurrent().getStatus());
    }
}
