package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.TankTransferPlan;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface TankTransferPlanRepository extends JpaRepository<TankTransferPlan, Long> {

    List<TankTransferPlan> findAllByOrderByIdDesc();

    List<TankTransferPlan> findAllByMaintenanceIdOrderByIdAsc(Long maintenanceId);

    List<TankTransferPlan> findAllByBatchIdOrderByIdAsc(Long batchId);

    /** 某批次当前生效（未过期/未取消/未执行完的都算占用唯一方案名额）方案 */
    @Query("select p from TankTransferPlan p where p.batchId = :batchId and p.status in ('PENDING','EXECUTING')")
    List<TankTransferPlan> findOpenByBatchId(Long batchId);

    /**
     * 与给定时段重叠、且处于生效状态（PENDING/EXECUTING）的其他方案 —— 目标罐抢占校验。
     * 半开区间：from < to 且 to > from 即重叠。
     */
    @Query("select p from TankTransferPlan p where p.targetTankNo = :tankNo "
            + "and p.status in ('PENDING','EXECUTING') "
            + "and p.reserveFrom < :to and p.reserveTo > :from "
            + "order by p.id asc")
    List<TankTransferPlan> findOpenByTargetOverlap(String tankNo,
                                                   java.time.LocalDateTime from,
                                                   java.time.LocalDateTime to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from TankTransferPlan p where p.id = :id")
    Optional<TankTransferPlan> findByIdForUpdate(Long id);
}
