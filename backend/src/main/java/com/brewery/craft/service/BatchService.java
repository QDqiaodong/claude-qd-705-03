package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.BatchRecipeItem;
import com.brewery.craft.domain.Fermenter;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.domain.Recipe;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.dto.BatchDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRecipeItemRepository;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.FermenterRepository;
import com.brewery.craft.repository.QcRoundRepository;
import com.brewery.craft.repository.RecipeRepository;
import com.brewery.craft.repository.ShipmentRepository;

/** 模块二：啤酒批次（状态流转 + @ElementCollection 曲线）。 */
@Service
@Transactional(readOnly = true)
public class BatchService {

    private final BatchRepository batchRepository;
    private final RecipeRepository recipeRepository;
    private final FermenterRepository fermenterRepository;
    private final QcRoundRepository qcRoundRepository;
    private final ShipmentRepository shipmentRepository;
    private final BatchRecipeItemRepository batchRecipeItemRepository;
    private final BatchSnapshotAssembler snapshotAssembler;
    private final QcGate qcGate;

    public BatchService(BatchRepository batchRepository,
                        RecipeRepository recipeRepository,
                        FermenterRepository fermenterRepository,
                        QcRoundRepository qcRoundRepository,
                        ShipmentRepository shipmentRepository,
                        BatchRecipeItemRepository batchRecipeItemRepository,
                        BatchSnapshotAssembler snapshotAssembler,
                        QcGate qcGate) {
        this.batchRepository = batchRepository;
        this.recipeRepository = recipeRepository;
        this.fermenterRepository = fermenterRepository;
        this.qcRoundRepository = qcRoundRepository;
        this.shipmentRepository = shipmentRepository;
        this.batchRecipeItemRepository = batchRecipeItemRepository;
        this.snapshotAssembler = snapshotAssembler;
        this.qcGate = qcGate;
    }

    public List<Views.BatchView> list(String status) {
        List<Batch> batches = status == null || status.isBlank()
                ? batchRepository.findAllByOrderByBatchNoAsc()
                : batchRepository.findAllByStatusOrderByBatchNoAsc(status);
        List<Views.BatchView> list = new ArrayList<>();
        for (Batch b : batches) {
            list.add(view(b));
        }
        return list;
    }

    public Views.BatchView get(Long id) {
        return detailView(batchRepository.findById(id).orElseThrow(() -> new BizException("批次不存在")));
    }

    /** 列表场景：不带快照，避免列表逐条放大载荷 */
    private Views.BatchView view(Batch b) {
        String recipeName = "";
        if (b.getRecipeId() != null) {
            recipeName = recipeRepository.findById(b.getRecipeId()).map(Recipe::getName).orElse("");
        }
        QcRound latest = qcRoundRepository.findLatestByBatchId(b.getId()).orElse(null);
        long roundCount = qcRoundRepository.countByBatchId(b.getId());
        QcGate.QcState state = qcGate.evaluate(b, latest, roundCount);
        Views.BatchQcStateView qc =
                Views.BatchQcStateView.of(state.code, state.latestRound, state.reason, state.roundCount);
        return Views.BatchView.of(b, recipeName, qc);
    }

    /** 详情场景：附带创建当时的配方用量快照（历史副本，配方后续改动不影响它） */
    private Views.BatchView detailView(Batch b) {
        Views.BatchView v = view(b);
        List<BatchRecipeItem> rows = batchRecipeItemRepository.findAllByBatchIdOrderByIdAsc(b.getId());
        if (!rows.isEmpty()) {
            String recipeName = v.getRecipeName();
            Integer recipeVersion = null;
            if (b.getRecipeId() != null) {
                // 只取版本用于展示；快照内容绝不按现配方重算
                recipeVersion = recipeRepository.findById(b.getRecipeId()).map(Recipe::getVersion).orElse(null);
            }
            v.setRecipeSnapshot(snapshotAssembler.toView(
                    b.getId(), b.getBatchNo(), recipeName, recipeVersion, rows));
        }
        return v;
    }

    @Transactional
    public Views.BatchView create(BatchDtos.BatchSaveReq req) {
        if (batchRepository.existsByBatchNo(req.getBatchNo())) {
            throw new BizException("批号 " + req.getBatchNo() + " 已存在");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "PLAN" : req.getStatus();
        if (Views.batchStatusName(status).equals(status)) {
            throw new BizException("批次状态取值非法：" + status);
        }
        Recipe recipe = null;
        if (req.getRecipeId() != null) {
            recipe = recipeRepository.findById(req.getRecipeId())
                    .orElseThrow(() -> new BizException("配方不存在"));
        }
        Batch b = new Batch();
        b.setBatchNo(req.getBatchNo());
        b.setRecipeId(req.getRecipeId());
        b.setFermenterNo(req.getFermenterNo());
        b.setBrewer(req.getBrewer());
        b.setBrewDate(req.getBrewDate() == null ? LocalDate.now() : req.getBrewDate());
        b.setMashStart(req.getMashStart());
        b.setMashEnd(req.getMashEnd());
        b.setBoilEnd(req.getBoilEnd());
        b.setFermentStart(req.getFermentStart());
        b.setExpectedMature(req.getExpectedMature());
        b.setActualMature(req.getActualMature());
        b.setVolumeL(req.getVolumeL() == null ? BigDecimal.ZERO : req.getVolumeL());
        b.setAbv(req.getAbv());
        b.setIbu(req.getIbu());
        b.setEbc(req.getEbc());
        b.setStatus(status);
        b.setUpdatedAt(LocalDateTime.now());
        Batch saved = batchRepository.save(b);

        // 建档即固化配方用量快照：之后配方改单位/改用量/缩放都不改变本批次的投料记录
        if (recipe != null) {
            List<BatchRecipeItem> snapshot =
                    snapshotAssembler.snapshotFromRecipe(saved.getId(), recipe, "CREATED");
            batchRecipeItemRepository.saveAll(snapshot);
        }
        return detailView(saved);
    }

    /**
     * 状态推进：计划 → 糖化 → 煮沸 → 发酵 → 熟成 → 已灌装。
     * 走到哪一步就把这一步的时间戳钉上，并同步罐体状态。
     *
     * 终态规则：推进到「已灌装」必须最新一轮质检明确放行、放行轮带必要测量，
     * 且批次已有灌装记录 —— 放行是灌装的真实前提，不是页面标签。
     */
    @Transactional
    public Views.BatchView advance(Long id, String targetStatus) {
        Batch b = batchRepository.findByIdForUpdate(id).orElseThrow(() -> new BizException("批次不存在"));
        if ("SCRAPPED".equals(b.getStatus())) {
            throw new BizException("已报废批次不能再推进");
        }
        if ("PACKAGED".equals(b.getStatus())) {
            throw new BizException("批次已灌装，流程结束");
        }
        String next = Views.batchNextStatus(b.getStatus());
        if (targetStatus != null && !targetStatus.isBlank()) {
            if (!targetStatus.equals(next)) {
                throw new BizException("只能单向推进到" + Views.batchStatusName(next));
            }
        }
        if ("PACKAGED".equals(next)) {
            QcRound latest = qcRoundRepository.findLatestByBatchId(b.getId()).orElse(null);
            long roundCount = qcRoundRepository.countByBatchId(b.getId());
            qcGate.assertCanPackage(b, qcGate.evaluate(b, latest, roundCount));
        }
        LocalDateTime now = LocalDateTime.now();
        switch (next) {
            case "MASH" -> b.setMashStart(now);
            case "BOIL" -> b.setMashEnd(now);
            case "FERMENT" -> b.setBoilEnd(now);
            case "CONDITION" -> {
                if (b.getFermentStart() == null) {
                    b.setFermentStart(now);
                }
                if (b.getExpectedMature() == null) {
                    b.setExpectedMature(LocalDate.now().plusDays(14));
                }
            }
            case "PACKAGED" -> {
                if (b.getActualMature() == null) {
                    b.setActualMature(LocalDate.now());
                }
            }
            default -> throw new BizException("当前状态无法继续推进");
        }
        b.setStatus(next);
        b.setUpdatedAt(now);
        Batch saved = batchRepository.save(b);
        syncTank(saved);
        return view(saved);
    }

    /**
     * 报废：终态规则。
     *   - 已灌装批次不能报废；
     *   - 一旦报废，已存在但未发出的出货单（PENDING/FROZEN）全部冻结，已发/已结款历史单不动；
     *   - 报废后不能再发起质检、灌装、出货。
     */
    @Transactional
    public Views.BatchView scrap(Long id) {
        Batch b = batchRepository.findByIdForUpdate(id).orElseThrow(() -> new BizException("批次不存在"));
        if ("PACKAGED".equals(b.getStatus())) {
            throw new BizException("已灌装批次不能报废");
        }
        if ("SCRAPPED".equals(b.getStatus())) {
            throw new BizException("批次已报废，无需重复操作");
        }
        boolean activeShipment = shipmentRepository.findAllByBatchNoOrderByIdAsc(b.getBatchNo()).stream()
                .anyMatch(s -> "SHIPPED".equals(s.getStatus()) || "PAID".equals(s.getStatus()));
        if (activeShipment) {
            throw new BizException("该批次存在已发出或已结款的出货单，历史单据不能因报废被改写，报废被阻止");
        }
        b.setStatus("SCRAPPED");
        b.setUpdatedAt(LocalDateTime.now());
        Batch saved = batchRepository.save(b);
        // 未发出货单全部冻结，原因钉死，重新放行也不能自动恢复（批次已是终态报废）
        LocalDateTime now = LocalDateTime.now();
        for (Shipment s : shipmentRepository.findAllByBatchNoOrderByIdAsc(b.getBatchNo())) {
            if ("PENDING".equals(s.getStatus()) || "FROZEN".equals(s.getStatus())) {
                s.setStatus("FROZEN");
                s.setFrozenReason("批次 " + b.getBatchNo() + " 已报废，出货单永久冻结");
                s.setFrozenAt(now);
                s.setUpdatedAt(now);
                shipmentRepository.save(s);
            }
        }
        syncTank(saved);
        return view(saved);
    }

    private void syncTank(Batch batch) {
        if (batch.getFermenterNo() == null || batch.getFermenterNo().isBlank()) {
            return;
        }
        var tank = fermenterRepository.findByTankNo(batch.getFermenterNo());
        if (tank.isEmpty()) {
            return;
        }
        Fermenter t = tank.get();
        if ("PACKAGED".equals(batch.getStatus()) || "SCRAPPED".equals(batch.getStatus())) {
            if (batch.getBatchNo().equals(t.getCurrentBatchNo())) {
                t.setCurrentBatchNo(null);
                t.setStatus("CLEANING");
            }
        } else if (batch.getBatchNo().equals(t.getCurrentBatchNo()) || t.getCurrentBatchNo() == null) {
            t.setCurrentBatchNo(batch.getBatchNo());
            t.setStatus(Views.tankStatusOfBatch(batch.getStatus()));
        }
        t.setUpdatedAt(LocalDateTime.now());
        fermenterRepository.save(t);
    }

    /** 【@ElementCollection Map】写入或覆盖某一天的测温/测糖 */
    @Transactional
    public Views.BatchView putReading(Long id, BatchDtos.ReadingReq req) {
        Batch b = batchRepository.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        String dayKey = req.getDayKey() == null || req.getDayKey().isBlank() ? "D1" : req.getDayKey();
        if (req.getTemp() != null) {
            b.getTempReadings().put(dayKey, req.getTemp());
        }
        if (req.getGravity() != null) {
            b.getGravityReadings().put(dayKey, req.getGravity());
        }
        b.setUpdatedAt(LocalDateTime.now());
        return view(batchRepository.save(b));
    }

    @Transactional
    public Views.BatchView removeReading(Long id, String dayKey) {
        Batch b = batchRepository.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        if (!b.getTempReadings().containsKey(dayKey) && !b.getGravityReadings().containsKey(dayKey)) {
            throw new BizException("第 " + dayKey + " 天没有测量记录");
        }
        b.getTempReadings().remove(dayKey);
        b.getGravityReadings().remove(dayKey);
        b.setUpdatedAt(LocalDateTime.now());
        return view(batchRepository.save(b));
    }

    /** 按 key 查询某一天的温度/糖度 */
    public Map<String, BigDecimal> reading(Long id, String dayKey) {
        Batch b = batchRepository.findById(id).orElseThrow(() -> new BizException("批次不存在"));
        Map<String, BigDecimal> one = new java.util.LinkedHashMap<>();
        one.put("temp", b.getTempReadings().get(dayKey));
        one.put("gravity", b.getGravityReadings().get(dayKey));
        one.put("dayKey", BigDecimal.valueOf(Views.dayKeyOrder(dayKey) == Integer.MAX_VALUE ? 0 : Views.dayKeyOrder(dayKey)));
        return one;
    }

    /** 产量汇总：按状态聚合 */
    public Views.BatchSummary summary() {
        List<Batch> batches = batchRepository.findAllByOrderByBatchNoAsc();
        Map<String, List<Batch>> grouped = new java.util.LinkedHashMap<>();
        for (String s : List.of("PLAN", "MASH", "BOIL", "FERMENT", "CONDITION", "PACKAGED", "SCRAPPED")) {
            grouped.put(s, new ArrayList<>());
        }
        for (Batch b : batches) {
            grouped.computeIfAbsent(b.getStatus(), k -> new ArrayList<>()).add(b);
        }
        Views.BatchSummary summary = new Views.BatchSummary();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal packaged = BigDecimal.ZERO;
        for (Map.Entry<String, List<Batch>> e : grouped.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Batch b : e.getValue()) {
                sum = sum.add(b.getVolumeL() == null ? BigDecimal.ZERO : b.getVolumeL());
            }
            Views.BatchSummaryItem item = new Views.BatchSummaryItem();
            item.setStatus(e.getKey());
            item.setStatusName(Views.batchStatusName(e.getKey()));
            item.setCount(e.getValue().size());
            item.setTotalVolume(sum.setScale(0, RoundingMode.HALF_UP));
            summary.getItems().add(item);
            total = total.add(sum);
            if ("PACKAGED".equals(e.getKey())) {
                packaged = sum;
            }
        }
        summary.setBatchTotal(batches.size());
        summary.setVolumeTotal(total.setScale(0, RoundingMode.HALF_UP));
        summary.setPackagedVolume(packaged.setScale(0, RoundingMode.HALF_UP));
        return summary;
    }
}
