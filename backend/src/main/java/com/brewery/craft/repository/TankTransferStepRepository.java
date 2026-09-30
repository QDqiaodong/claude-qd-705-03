package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.TankTransferStep;

public interface TankTransferStepRepository extends JpaRepository<TankTransferStep, Long> {

    List<TankTransferStep> findAllByPlanIdOrderByAttemptSeqAscStepSeqAsc(Long planId);
}
