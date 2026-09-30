package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Bottling;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.dto.ShipmentDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.BottlingRepository;
import com.brewery.craft.repository.QcRoundRepository;
import com.brewery.craft.repository.ShipmentRepository;

/** 模块五：灌装与出货。质检放行是这里的真实约束（{@link QcGate}），不是提示文字。 */
@Service
public class ShipmentService {

    private final BottlingRepository bottlingRepository;
    private final ShipmentRepository shipmentRepository;
    private final BatchRepository batchRepository;
    private final QcRoundRepository qcRoundRepository;
    private final QcGate qcGate;

    public ShipmentService(BottlingRepository bottlingRepository,
                           ShipmentRepository shipmentRepository,
                           BatchRepository batchRepository,
                           QcRoundRepository qcRoundRepository,
                           QcGate qcGate) {
        this.bottlingRepository = bottlingRepository;
        this.shipmentRepository = shipmentRepository;
        this.batchRepository = batchRepository;
        this.qcRoundRepository = qcRoundRepository;
        this.qcGate = qcGate;
    }

    // ---------------- 灌装单 ----------------
    @Transactional(readOnly = true)
    public List<Views.BottlingView> bottlings() {
        List<Bottling> all = bottlingRepository.findAllByOrderByBottleDateDescIdAsc();
        Map<String, Batch> batchCache = new HashMap<>();
        List<Views.BottlingView> list = new ArrayList<>();
        for (Bottling b : all) {
            Batch batch = batchCache.computeIfAbsent(b.getBatchNo(),
                    k -> batchRepository.findByBatchNo(k).orElse(null));
            if (batch == null) {
                list.add(Views.BottlingView.of(b));
                continue;
            }
            QcRound latest = qcRoundRepository.findLatestByBatchId(batch.getId()).orElse(null);
            QcGate.QcState state = qcGate.evaluate(batch, latest, qcRoundRepository.countByBatchId(batch.getId()));
            list.add(Views.BottlingView.of(b, state.code, state.reason, batch.getStatus()));
        }
        return list;
    }

    /**
     * 新建灌装单的真实前提：批次未报废、已到熟成阶段、最新一轮质检明确放行、
     * 放行轮带必要的逐日温度/糖度测量。整个过程持批次行写锁，与放行/撤销串行。
     */
    @Transactional
    public Views.BottlingView createBottling(ShipmentDtos.BottlingSaveReq req) {
        if (bottlingRepository.existsByBottlingNo(req.getBottlingNo())) {
            throw new BizException("灌装单号 " + req.getBottlingNo() + " 已存在");
        }
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BizException("灌装数量必须大于 0");
        }
        Batch b = batchRepository.findByBatchNoForUpdate(req.getBatchNo())
                .orElseThrow(() -> new BizException("批次 " + req.getBatchNo() + " 不存在"));
        QcRound latest = qcRoundRepository.findLatestByBatchId(b.getId()).orElse(null);
        QcGate.QcState state = qcGate.evaluate(b, latest, qcRoundRepository.countByBatchId(b.getId()));
        qcGate.assertBottling(b, state);

        Bottling bo = new Bottling();
        bo.setBottlingNo(req.getBottlingNo());
        bo.setBatchNo(req.getBatchNo());
        bo.setPackageForm(normalizePackage(req.getPackageForm()));
        bo.setQuantity(req.getQuantity());
        bo.setBottleDate(req.getBottleDate() == null ? LocalDate.now() : req.getBottleDate());
        bo.setLossL(req.getLossL() == null ? BigDecimal.ZERO : req.getLossL());
        bo.setUpdatedAt(LocalDateTime.now());
        Bottling saved = bottlingRepository.save(bo);
        return Views.BottlingView.of(saved, state.code, state.reason, b.getStatus());
    }

    // ---------------- 出货单 ----------------
    @Transactional(readOnly = true)
    public List<Views.ShipmentView> shipments(String status) {
        List<Shipment> list = status == null || status.isBlank()
                ? shipmentRepository.findAllByOrderByShipDateDescIdAsc()
                : shipmentRepository.findAllByStatusOrderByShipDateDescIdAsc(status);
        Map<String, Batch> batchCache = new HashMap<>();
        List<Views.ShipmentView> views = new ArrayList<>();
        for (Shipment s : list) {
            Batch batch = batchCache.computeIfAbsent(s.getBatchNo(),
                    k -> batchRepository.findByBatchNo(k).orElse(null));
            if (batch == null) {
                views.add(Views.ShipmentView.of(s));
                continue;
            }
            QcRound latest = qcRoundRepository.findLatestByBatchId(batch.getId()).orElse(null);
            QcGate.QcState state = qcGate.evaluate(batch, latest, qcRoundRepository.countByBatchId(batch.getId()));
            Views.ShipmentView v = Views.ShipmentView.of(s, state.code, state.reason, batch.getStatus());
            if ("PENDING".equals(s.getStatus())) {
                QcGate.Check check = qcGate.checkExistingShipmentAdvance(batch, state, s.getPackageForm());
                if (!check.allowed) {
                    v.setBlockedReason("当前不能发出：" + check.reason);
                }
            }
            views.add(v);
        }
        return views;
    }

    /**
     * 新建出货单：除金额规则外，必须
     *   1. 批次最新一轮明确放行且仍有效；
     *   2. 该批次存在【同包装形式】的灌装记录（出货基于灌装，不能凭空出货）；
     *   3. 不超灌装量。新建一律为 PENDING，带状态的历史数据不能用这个接口伪造。
     */
    @Transactional
    public Views.ShipmentView createShipment(ShipmentDtos.ShipmentSaveReq req) {
        if (shipmentRepository.existsByShipmentNo(req.getShipmentNo())) {
            throw new BizException("出货单号 " + req.getShipmentNo() + " 已存在");
        }
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BizException("出货数量必须大于 0");
        }
        if (req.getUnitPrice() == null || req.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("单价必须大于 0");
        }
        Batch b = batchRepository.findByBatchNoForUpdate(req.getBatchNo())
                .orElseThrow(() -> new BizException("批次 " + req.getBatchNo() + " 不存在"));
        QcRound latest = qcRoundRepository.findLatestByBatchId(b.getId()).orElse(null);
        QcGate.QcState state = qcGate.evaluate(b, latest, qcRoundRepository.countByBatchId(b.getId()));
        String pkg = normalizePackage(req.getPackageForm());
        qcGate.assertShipmentCreate(b, state, pkg, req.getQuantity());

        Shipment s = new Shipment();
        s.setShipmentNo(req.getShipmentNo());
        s.setCustomer(req.getCustomer());
        s.setBatchNo(req.getBatchNo());
        s.setPackageForm(pkg);
        s.setQuantity(req.getQuantity());
        s.setUnitPrice(req.getUnitPrice());
        s.setAmount(req.getUnitPrice().multiply(BigDecimal.valueOf(req.getQuantity())).setScale(2, RoundingMode.HALF_UP));
        s.setShipDate(req.getShipDate() == null ? LocalDate.now() : req.getShipDate());
        // 新建只能是待发；SHIPPED/PAID 是历史单据，不允许通过新建接口写入
        s.setStatus("PENDING");
        s.setUpdatedAt(LocalDateTime.now());
        Shipment saved = shipmentRepository.save(s);
        return Views.ShipmentView.of(saved, state.code, state.reason, b.getStatus());
    }

    /**
     * 待发 → 已发 → 已结款。
     * FROZEN 不能推进（并说明冻结原因）；待发→已发时再次确认放行结论仍有效，
     * 防止“撤销放行”和“推进出货”并发时产生非法状态链。
     */
    @Transactional
    public Views.ShipmentView nextStatus(Long id) {
        Shipment s = shipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("出货单不存在"));
        Batch b = batchRepository.findByBatchNoForUpdate(s.getBatchNo())
                .orElseThrow(() -> new BizException("出货单所属批次不存在"));
        QcRound latest = qcRoundRepository.findLatestByBatchId(b.getId()).orElse(null);
        QcGate.QcState state = qcGate.evaluate(b, latest, qcRoundRepository.countByBatchId(b.getId()));

        if ("FROZEN".equals(s.getStatus())) {
            throw new BizException("出货单已冻结：" + (s.getFrozenReason() == null ? "批次质检未放行" : s.getFrozenReason())
                    + "；需批次重新放行恢复后才能继续");
        }
        String next = Views.shipmentNextStatus(s.getStatus());
        if (next == null) {
            throw new BizException("该出货单已结款，没有后续状态");
        }
        if ("SHIPPED".equals(next)) {
            // 出货前再次确认：基于已有灌装记录 + 放行结论仍有效（持批次+出货单两把行锁）
            qcGate.checkExistingShipmentAdvance(b, state, s.getPackageForm()).assertAllowed();
        }
        s.setStatus(next);
        s.setUpdatedAt(LocalDateTime.now());
        Shipment saved = shipmentRepository.save(s);
        return Views.ShipmentView.of(saved, state.code, state.reason, b.getStatus());
    }

    /** 金额与出货量汇总（冻结单列） */
    @Transactional(readOnly = true)
    public Views.ShipmentSummary summary() {
        List<Shipment> all = shipmentRepository.findAllByOrderByShipDateDescIdAsc();
        Map<String, List<Shipment>> grouped = new java.util.LinkedHashMap<>();
        for (String st : List.of("PENDING", "FROZEN", "SHIPPED", "PAID")) {
            grouped.put(st, new ArrayList<>());
        }
        for (Shipment s : all) {
            grouped.computeIfAbsent(s.getStatus(), k -> new ArrayList<>()).add(s);
        }
        Views.ShipmentSummary summary = new Views.ShipmentSummary();
        BigDecimal amountTotal = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        long qtyTotal = 0;
        for (Map.Entry<String, List<Shipment>> e : grouped.entrySet()) {
            long qty = 0;
            BigDecimal amount = BigDecimal.ZERO;
            for (Shipment s : e.getValue()) {
                qty += s.getQuantity() == null ? 0 : s.getQuantity();
                amount = amount.add(s.getAmount() == null ? BigDecimal.ZERO : s.getAmount());
            }
            Views.ShipmentSummaryItem item = new Views.ShipmentSummaryItem();
            item.setStatus(e.getKey());
            item.setStatusName(Views.shipmentStatusName(e.getKey()));
            item.setCount(e.getValue().size());
            item.setQuantity(qty);
            item.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            summary.getItems().add(item);
            if ("FROZEN".equals(e.getKey())) {
                summary.setFrozenCount(e.getValue().size());
            }
            // 冻结单金额仍计入在途盘子，但单独有 frozenCount 提示
            amountTotal = amountTotal.add(amount);
            qtyTotal += qty;
            if ("PAID".equals(e.getKey())) {
                paid = amount;
            }
        }
        summary.setOrderTotal(all.size());
        summary.setQuantityTotal(qtyTotal);
        summary.setAmountTotal(amountTotal.setScale(2, RoundingMode.HALF_UP));
        summary.setPaidAmount(paid.setScale(2, RoundingMode.HALF_UP));
        return summary;
    }

    private String normalizePackage(String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return "BOTTLE";
        }
        if (!pkg.equals("BOTTLE") && !pkg.equals("CAN") && !pkg.equals("KEG")) {
            throw new BizException("包装形式取值非法：" + pkg);
        }
        return pkg;
    }
}
