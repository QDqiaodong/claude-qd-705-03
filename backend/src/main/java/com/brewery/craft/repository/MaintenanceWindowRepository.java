package com.brewery.craft.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.MaintenanceWindow;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {

    List<MaintenanceWindow> findAllByOrderByStartAtAscIdAsc();

    List<MaintenanceWindow> findAllByFermenterNoOrderByStartAtAscIdAsc(String fermenterNo);

    boolean existsByWindowNo(String windowNo);

    Optional<MaintenanceWindow> findTopByOrderByIdDesc();

    /**
     * 与给定时间区间重叠、且仍会占罐（计划中/进行中）的窗口。
     * 重叠口径：start < :end and expectedRecover > :start。
     */
    @Query("""
            select w from MaintenanceWindow w
            where w.status in ('SCHEDULED', 'IN_PROGRESS')
              and w.startAt < :end and w.expectedRecoverAt > :start
            order by w.startAt asc, w.id asc
            """)
    List<MaintenanceWindow> findActiveOverlapping(LocalDateTime start, LocalDateTime end);

    @Query("""
            select w from MaintenanceWindow w
            where w.fermenterNo = :tankNo
              and w.status in ('SCHEDULED', 'IN_PROGRESS')
              and w.startAt < :end and w.expectedRecoverAt > :start
            order by w.startAt asc, w.id asc
            """)
    List<MaintenanceWindow> findActiveOverlappingOnTank(String tankNo, LocalDateTime start, LocalDateTime end);

    /** 确认迁罐时对窗口行加悲观锁，串行化窗口延期与方案确认 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select w from MaintenanceWindow w where w.id = :id")
    Optional<MaintenanceWindow> findByIdForUpdate(Long id);
}
