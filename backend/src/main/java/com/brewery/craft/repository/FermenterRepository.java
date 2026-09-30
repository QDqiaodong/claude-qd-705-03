package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.Fermenter;

public interface FermenterRepository extends JpaRepository<Fermenter, Long> {

    List<Fermenter> findAllByOrderByTankNoAsc();

    Optional<Fermenter> findByTankNo(String tankNo);

    boolean existsByTankNo(String tankNo);

    List<Fermenter> findAllByStatusOrderByTankNoAsc(String status);

    long countByStatus(String status);

    Optional<Fermenter> findByCurrentBatchNo(String batchNo);
}
