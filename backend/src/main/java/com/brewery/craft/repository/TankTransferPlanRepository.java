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

    List<TankTransferPlan> findAllByOrderByIdAsc();

    List<TankTransferPlan> findAllByWindowIdOrderByIdAsc(Long windowId);

    List<TankTransferPlan> findAllByBatchIdOrderByIdAsc(Long batchId);

    Optional<TankTransferPlan> findByPlanNo(String planNo);

    /** 该批次当前生效（待确认/执行中）的方案：同一批次只能有一个 */
    Optional<TankTransferPlan> findFirstByBatchIdAndActiveSlotIsNotNullOrderByIdDesc(Long batchId);

    /**
     * 目标罐在占用时段上的其他生效方案（并发抢占校验）。
     * 入参的时段由服务层按 [moveAt, estimatedOccupyUntil] 给出。
     */
    @Query("""
            select p from TankTransferPlan p
            where p.id <> :excludePlanId
              and p.targetTankId = :targetTankId
              and p.activeSlot is not null
            order by p.id asc
            """)
    List<TankTransferPlan> findActiveTowardsTank(Long targetTankId, Long excludePlanId);

    /** 已经占到目标罐（执行中/已完成）的方案，候选罐评估时直接排除该罐 */
    @Query("""
            select p from TankTransferPlan p
            where p.targetTankId = :targetTankId
              and p.status in ('EXECUTING', 'EXECUTED')
            order by p.id asc
            """)
    List<TankTransferPlan> findLandedTowardsTank(Long targetTankId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from TankTransferPlan p where p.id = :id")
    Optional<TankTransferPlan> findByIdForUpdate(Long id);
}
