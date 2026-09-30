package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.TankTransferEvent;

public interface TankTransferEventRepository extends JpaRepository<TankTransferEvent, Long> {

    List<TankTransferEvent> findAllByPlanIdOrderByIdAsc(Long planId);

    List<TankTransferEvent> findAllByBatchIdOrderByIdAsc(Long batchId);
}
