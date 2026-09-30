package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.BatchRecipeItem;

public interface BatchRecipeItemRepository extends JpaRepository<BatchRecipeItem, Long> {

    List<BatchRecipeItem> findAllByBatchIdOrderByIdAsc(Long batchId);

    long countByBatchId(Long batchId);
}
