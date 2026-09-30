package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.TransferReservation;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface TransferReservationRepository extends JpaRepository<TransferReservation, Long> {

    List<TransferReservation> findAllByTankNoAndStatusOrderByIdAsc(String tankNo, String status);

    /** 某目标罐与给定时段重叠的生效预留（半开区间重叠） */
    @Query("select r from TransferReservation r where r.tankNo = :tankNo and r.status = 'ACTIVE' "
            + "and r.reserveFrom < :to and r.reserveTo > :from order by r.id asc")
    List<TransferReservation> findActiveOverlap(String tankNo,
                                                java.time.LocalDateTime from,
                                                java.time.LocalDateTime to);

    List<TransferReservation> findAllByPlanIdOrderByIdAsc(Long planId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from TransferReservation r where r.id = :id")
    List<TransferReservation> findByIdForUpdate(Long id);
}
