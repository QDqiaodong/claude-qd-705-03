package com.brewery.craft;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Bottling;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.BottlingRepository;
import com.brewery.craft.repository.ShipmentRepository;

/** 测试夹具：构造处于不同质检/灌装阶段的批次。 */
@Component
public class TestFixtures {

    private final BatchRepository batchRepository;
    private final BottlingRepository bottlingRepository;
    private final ShipmentRepository shipmentRepository;

    public TestFixtures(BatchRepository batchRepository,
                        BottlingRepository bottlingRepository,
                        ShipmentRepository shipmentRepository) {
        this.batchRepository = batchRepository;
        this.bottlingRepository = bottlingRepository;
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public Batch batch(String no, String status, boolean withCurve) {
        Batch b = new Batch();
        b.setBatchNo(no);
        b.setBrewer("测试酿酒师");
        b.setBrewDate(LocalDate.now().minusDays(20));
        b.setFermentStart(LocalDateTime.now().minusDays(15));
        b.setExpectedMature(LocalDate.now().minusDays(1));
        if (withCurve) {
            b.getTempReadings().put("D1", new BigDecimal("18.50"));
            b.getTempReadings().put("D2", new BigDecimal("19.00"));
            b.getGravityReadings().put("D1", new BigDecimal("12.00"));
            b.getGravityReadings().put("D2", new BigDecimal("4.50"));
        }
        b.setVolumeL(new BigDecimal("1000"));
        b.setStatus(status);
        b.setUpdatedAt(LocalDateTime.now());
        return batchRepository.save(b);
    }

    @Transactional
    public void addReading(Long id, String dayKey, BigDecimal temp, BigDecimal gravity) {
        Batch b = batchRepository.findById(id).orElseThrow();
        if (temp != null) {
            b.getTempReadings().put(dayKey, temp);
        }
        if (gravity != null) {
            b.getGravityReadings().put(dayKey, gravity);
        }
        b.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(b);
    }

    @Transactional
    public Bottling bottling(String no, String batchNo, String pkg, int qty) {
        Bottling bo = new Bottling();
        bo.setBottlingNo(no);
        bo.setBatchNo(batchNo);
        bo.setPackageForm(pkg);
        bo.setQuantity(qty);
        bo.setBottleDate(LocalDate.now());
        bo.setLossL(BigDecimal.ZERO);
        bo.setUpdatedAt(LocalDateTime.now());
        return bottlingRepository.save(bo);
    }

    @Transactional
    public Shipment shipment(String no, String batchNo, String pkg, int qty, String status) {
        Shipment s = new Shipment();
        s.setShipmentNo(no);
        s.setCustomer("测试客户");
        s.setBatchNo(batchNo);
        s.setPackageForm(pkg);
        s.setQuantity(qty);
        s.setUnitPrice(new BigDecimal("20.00"));
        s.setAmount(new BigDecimal(qty * 20 + ".00"));
        s.setShipDate(LocalDate.now());
        s.setStatus(status);
        s.setUpdatedAt(LocalDateTime.now());
        return shipmentRepository.save(s);
    }
}
