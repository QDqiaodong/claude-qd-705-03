package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.Shipment;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findAllByOrderByShipDateDescIdAsc();

    Optional<Shipment> findByShipmentNo(String shipmentNo);

    boolean existsByShipmentNo(String shipmentNo);

    List<Shipment> findAllByStatusOrderByShipDateDescIdAsc(String status);

    List<Shipment> findAllByBatchNoOrderByIdAsc(String batchNo);

    List<Shipment> findAllByBatchNoAndStatusOrderByIdAsc(String batchNo, String status);

    /** 推进状态时对出货单行加写锁，重复点击/并发推进严格串行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(Long id);

    long countByStatus(String status);
}
