package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.QcAction;

public interface QcActionRepository extends JpaRepository<QcAction, Long> {

    List<QcAction> findAllByBatchIdOrderByIdAsc(Long batchId);

    List<QcAction> findAllByRoundIdOrderByIdAsc(Long roundId);
}
