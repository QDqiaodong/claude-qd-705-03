package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.BatchTankHistory;

public interface BatchTankHistoryRepository extends JpaRepository<BatchTankHistory, Long> {

    List<BatchTankHistory> findAllByBatchIdOrderByEnteredAtAscIdAsc(Long batchId);

    Optional<BatchTankHistory> findFirstByBatchIdAndLeftAtIsNullOrderByEnteredAtDescIdDesc(Long batchId);
}
