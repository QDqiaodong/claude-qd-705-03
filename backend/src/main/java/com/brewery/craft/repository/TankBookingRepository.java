package com.brewery.craft.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.brewery.craft.domain.TankBooking;

public interface TankBookingRepository extends JpaRepository<TankBooking, Long> {

    List<TankBooking> findAllByOrderByPlannedEnterAtAscIdAsc();

    List<TankBooking> findAllByTankNoOrderByPlannedEnterAtAscIdAsc(String tankNo);

    List<TankBooking> findAllByBatchNoOrderByPlannedEnterAtAscIdAsc(String batchNo);

    Optional<TankBooking> findByBookingNo(String bookingNo);

    boolean existsByBookingNo(String bookingNo);

    /** 与时间段重叠、仍待入罐/已延期的预约（时间线冲突用） */
    @Query("""
            select b from TankBooking b
            where b.status in ('PLANNED', 'DELAYED')
              and b.plannedEnterAt < :end
              and (b.plannedExitAt is null or b.plannedExitAt > :start)
            order by b.plannedEnterAt asc, b.id asc
            """)
    List<TankBooking> findPendingOverlapping(LocalDateTime start, LocalDateTime end);
}
