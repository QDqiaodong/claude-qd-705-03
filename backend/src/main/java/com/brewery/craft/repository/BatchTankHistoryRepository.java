package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.BatchTankHistory;

public interface BatchTankHistoryRepository extends JpaRepository<BatchTankHistory, Long> {

    List<BatchTankHistory> findAllByBatchIdOrderByIdAsc(Long batchId);

    List<BatchTankHistory> findAllByOrderByIdAsc();
}
