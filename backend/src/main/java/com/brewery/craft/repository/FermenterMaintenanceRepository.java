package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.FermenterMaintenance;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface FermenterMaintenanceRepository extends JpaRepository<FermenterMaintenance, Long> {

    List<FermenterMaintenance> findAllByOrderByStartAtAscIdAsc();

    List<FermenterMaintenance> findAllByTankNoOrderByStartAtAscIdAsc(String tankNo);

    /** 某罐当前生效（未取消、未结束）的窗口，按开始时间排序 */
    @Query("select m from FermenterMaintenance m where m.tankNo = :tankNo and m.status <> 'CANCELLED' order by m.startAt asc, m.id asc")
    List<FermenterMaintenance> findActiveByTankNo(String tankNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select m from FermenterMaintenance m where m.id = :id")
    Optional<FermenterMaintenance> findByIdForUpdate(Long id);
}
