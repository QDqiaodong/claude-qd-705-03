package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Fermenter;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.dto.FermenterDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.FermenterRepository;
import com.brewery.craft.repository.IngredientRepository;
import com.brewery.craft.repository.RecipeRepository;
import com.brewery.craft.repository.ShipmentRepository;

/** 模块一：发酵罐。 */
@Service
public class FermenterService {

    private static final List<String> TANK_STATUS_LIST =
            List.of("IDLE", "MASHING", "BOILING", "FERMENTING", "CONDITIONING", "CLEANING", "DISABLED");

    private final FermenterRepository fermenterRepository;
    private final BatchRepository batchRepository;
    private final IngredientRepository ingredientRepository;
    private final ShipmentRepository shipmentRepository;
    private final RecipeRepository recipeRepository;

    public FermenterService(FermenterRepository fermenterRepository,
                            BatchRepository batchRepository,
                            IngredientRepository ingredientRepository,
                            ShipmentRepository shipmentRepository,
                            RecipeRepository recipeRepository) {
        this.fermenterRepository = fermenterRepository;
        this.batchRepository = batchRepository;
        this.ingredientRepository = ingredientRepository;
        this.shipmentRepository = shipmentRepository;
        this.recipeRepository = recipeRepository;
    }

    /** 罐列表：液位 = 罐上当前批次的产量 / 罐容 */
    public List<Views.FermenterView> list() {
        List<Fermenter> tanks = fermenterRepository.findAllByOrderByTankNoAsc();
        List<Views.FermenterView> list = new ArrayList<>();
        for (Fermenter tank : tanks) {
            list.add(Views.FermenterView.of(tank, fillPercent(tank)));
        }
        return list;
    }

    public List<Views.FermenterView> listByStatus(String status) {
        List<Views.FermenterView> list = new ArrayList<>();
        for (Fermenter tank : fermenterRepository.findAllByStatusOrderByTankNoAsc(status)) {
            list.add(Views.FermenterView.of(tank, fillPercent(tank)));
        }
        return list;
    }

    private BigDecimal fillPercent(Fermenter tank) {
        BigDecimal fill = BigDecimal.ZERO;
        if (tank.getCurrentBatchNo() != null && !tank.getCurrentBatchNo().isBlank()) {
            Optional<Batch> batch = batchRepository.findByBatchNo(tank.getCurrentBatchNo());
            if (batch.isPresent() && batch.get().getVolumeL() != null) {
                fill = batch.get().getVolumeL();
            }
        }
        if (tank.getCapacityL() == null || tank.getCapacityL().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal percent = fill.multiply(BigDecimal.valueOf(100)).divide(tank.getCapacityL(), 1, RoundingMode.HALF_UP);
        return percent.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : percent;
    }

    @Transactional
    public Views.FermenterView create(FermenterDtos.FermenterSaveReq req) {
        if (fermenterRepository.existsByTankNo(req.getTankNo())) {
            throw new BizException("罐号 " + req.getTankNo() + " 已存在");
        }
        if (req.getCapacityL() == null || req.getCapacityL().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("容量必须大于 0");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "IDLE" : req.getStatus();
        if (!TANK_STATUS_LIST.contains(status)) {
            throw new BizException("罐体状态取值非法");
        }
        if (req.getTempMin() != null && req.getTempMax() != null
                && req.getTempMin().compareTo(req.getTempMax()) > 0) {
            throw new BizException("温控区间下限不能高于上限");
        }
        Fermenter tank = new Fermenter();
        tank.setTankNo(req.getTankNo());
        tank.setCapacityL(req.getCapacityL());
        tank.setMaterial(req.getMaterial() == null ? "STAINLESS" : req.getMaterial());
        tank.setTempMin(req.getTempMin());
        tank.setTempMax(req.getTempMax());
        tank.setCooling(req.getCooling() == null ? "GLYCOL" : req.getCooling());
        tank.setStatus(status);
        tank.setCurrentBatchNo(req.getCurrentBatchNo());
        tank.setUpdatedAt(LocalDateTime.now());
        return Views.FermenterView.of(fermenterRepository.save(tank), BigDecimal.ZERO);
    }

    @Transactional
    public Views.FermenterView changeStatus(Long id, FermenterDtos.StatusReq req) {
        Fermenter tank = fermenterRepository.findById(id)
                .orElseThrow(() -> new BizException("发酵罐不存在"));
        if (!TANK_STATUS_LIST.contains(req.getStatus())) {
            throw new BizException("罐体状态取值非法");
        }
        if ("DISABLED".equals(req.getStatus()) && tank.getCurrentBatchNo() != null
                && !tank.getCurrentBatchNo().isBlank()) {
            throw new BizException("罐内还有批次 " + tank.getCurrentBatchNo() + "，不能停用");
        }
        tank.setStatus(req.getStatus());
        if (req.getBatchNo() != null) {
            tank.setCurrentBatchNo(req.getBatchNo().isBlank() ? null : req.getBatchNo());
        }
        if ("IDLE".equals(req.getStatus())) {
            tank.setCurrentBatchNo(null);
        }
        tank.setUpdatedAt(LocalDateTime.now());
        Fermenter saved = fermenterRepository.save(tank);
        return Views.FermenterView.of(saved, fillPercent(saved));
    }

    /** 把某个批次放进罐里（罐同时转到该批次所处的阶段） */
    @Transactional
    public Views.FermenterView assign(Long id, FermenterDtos.AssignReq req) {
        Fermenter tank = fermenterRepository.findById(id)
                .orElseThrow(() -> new BizException("发酵罐不存在"));
        if ("DISABLED".equals(tank.getStatus())) {
            throw new BizException("停用罐不能承接批次");
        }
        Batch batch = batchRepository.findByBatchNo(req.getBatchNo())
                .orElseThrow(() -> new BizException("批次 " + req.getBatchNo() + " 不存在"));
        if ("SCRAPPED".equals(batch.getStatus())) {
            throw new BizException("报废批次不能入罐");
        }
        Optional<Fermenter> holder = fermenterRepository.findByCurrentBatchNo(req.getBatchNo());
        if (holder.isPresent() && !holder.get().getId().equals(id)) {
            throw new BizException("批次 " + req.getBatchNo() + " 已在罐 " + holder.get().getTankNo() + " 中");
        }
        if (batch.getVolumeL() != null && tank.getCapacityL() != null
                && batch.getVolumeL().compareTo(tank.getCapacityL()) > 0) {
            throw new BizException("批次产量 " + batch.getVolumeL() + "L 超出罐容 " + tank.getCapacityL() + "L");
        }
        batch.setFermenterNo(tank.getTankNo());
        batch.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(batch);

        tank.setCurrentBatchNo(batch.getBatchNo());
        tank.setStatus(Views.tankStatusOfBatch(batch.getStatus()));
        tank.setUpdatedAt(LocalDateTime.now());
        Fermenter saved = fermenterRepository.save(tank);
        return Views.FermenterView.of(saved, fillPercent(saved));
    }

    /** 罐释放（转到待清洗，批次出罐） */
    @Transactional
    public Views.FermenterView release(Long id) {
        Fermenter tank = fermenterRepository.findById(id)
                .orElseThrow(() -> new BizException("发酵罐不存在"));
        if (tank.getCurrentBatchNo() != null) {
            batchRepository.findByBatchNo(tank.getCurrentBatchNo()).ifPresent(batch -> {
                batch.setFermenterNo(null);
                batch.setUpdatedAt(LocalDateTime.now());
                batchRepository.save(batch);
            });
        }
        tank.setCurrentBatchNo(null);
        tank.setStatus("CLEANING");
        tank.setUpdatedAt(LocalDateTime.now());
        Fermenter saved = fermenterRepository.save(tank);
        return Views.FermenterView.of(saved, fillPercent(saved));
    }

    /** 前端阶段标签页用的罐体状态字典 */
    public List<Map<String, String>> statusDict() {
        List<Map<String, String>> list = new ArrayList<>();
        for (String s : TANK_STATUS_LIST) {
            list.add(Map.of("key", s, "name", Views.tankStatusName(s), "color", Views.tankStatusColor(s)));
        }
        return list;
    }

    /** 顶部计数器：罐 / 批次 / 低库存原料 / 出货金额 */
    public Views.Overview overview() {
        Views.Overview v = new Views.Overview();
        List<Fermenter> tanks = fermenterRepository.findAllByOrderByTankNoAsc();
        v.setTankTotal(tanks.size());
        BigDecimal capacity = BigDecimal.ZERO;
        long busy = 0;
        long idle = 0;
        Map<String, Long> statusCount = new LinkedHashMap<>();
        for (String s : TANK_STATUS_LIST) {
            statusCount.put(s, 0L);
        }
        for (Fermenter t : tanks) {
            capacity = capacity.add(t.getCapacityL() == null ? BigDecimal.ZERO : t.getCapacityL());
            statusCount.merge(t.getStatus(), 1L, Long::sum);
            if ("IDLE".equals(t.getStatus())) {
                idle++;
            } else if (!"DISABLED".equals(t.getStatus())) {
                busy++;
            }
        }
        v.setCapacityTotal(capacity.setScale(0, RoundingMode.HALF_UP));
        v.setTankBusy(busy);
        v.setTankIdle(idle);
        v.setTankStatusCount(statusCount);

        List<Batch> batches = batchRepository.findAllByOrderByBatchNoAsc();
        v.setBatchTotal(batches.size());
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal packaged = BigDecimal.ZERO;
        long fermenting = 0;
        long packagedCount = 0;
        for (Batch b : batches) {
            BigDecimal v0 = b.getVolumeL() == null ? BigDecimal.ZERO : b.getVolumeL();
            volume = volume.add(v0);
            if ("FERMENT".equals(b.getStatus()) || "CONDITION".equals(b.getStatus())) {
                fermenting++;
            }
            if ("PACKAGED".equals(b.getStatus())) {
                packagedCount++;
                packaged = packaged.add(v0);
            }
        }
        v.setBatchFermenting(fermenting);
        v.setBatchPackaged(packagedCount);
        v.setBatchVolumeTotal(volume.setScale(0, RoundingMode.HALF_UP));
        v.setPackagedVolume(packaged.setScale(0, RoundingMode.HALF_UP));
        v.setRecipeTotal(recipeRepository.count());

        long low = 0;
        for (Ingredient ing : ingredientRepository.findAllByOrderByCodeAsc()) {
            if (ing.getStock() != null && ing.getSafetyStock() != null
                    && ing.getStock().compareTo(ing.getSafetyStock()) < 0) {
                low++;
            }
        }
        v.setIngredientLow(low);

        v.setShipmentPending(shipmentRepository.countByStatus("PENDING"));
        BigDecimal amount = BigDecimal.ZERO;
        for (Shipment s : shipmentRepository.findAllByOrderByShipDateDescIdAsc()) {
            amount = amount.add(s.getAmount() == null ? BigDecimal.ZERO : s.getAmount());
        }
        v.setShipmentAmount(amount.setScale(2, RoundingMode.HALF_UP));
        return v;
    }
}
