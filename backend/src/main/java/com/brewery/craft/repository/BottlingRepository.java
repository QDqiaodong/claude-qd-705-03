package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.Bottling;

public interface BottlingRepository extends JpaRepository<Bottling, Long> {

    List<Bottling> findAllByOrderByBottleDateDescIdAsc();

    Optional<Bottling> findByBottlingNo(String bottlingNo);

    boolean existsByBottlingNo(String bottlingNo);

    List<Bottling> findAllByBatchNoOrderByIdAsc(String batchNo);

    List<Bottling> findAllByBatchNoAndPackageFormOrderByIdAsc(String batchNo, String packageForm);
}
