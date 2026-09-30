package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.Batch;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BatchRepository extends JpaRepository<Batch, Long> {

    List<Batch> findAllByOrderByBatchNoAsc();

    Optional<Batch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    List<Batch> findAllByStatusOrderByBatchNoAsc(String status);

    List<Batch> findAllByRecipeIdOrderByBatchNoAsc(Long recipeId);

    List<Batch> findAllByFermenterNoOrderByBatchNoAsc(String fermenterNo);

    long countByStatus(String status);

    /**
     * 对批次行加悲观写锁。质检流转、灌装/出货新建与推进、报废都必须先拿到这把锁，
     * 把同一批次上的“放行 ↔ 下游推进”全部串行化，杜绝前后端短暂不一致。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from Batch b where b.id = :id")
    Optional<Batch> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from Batch b where b.batchNo = :batchNo")
    Optional<Batch> findByBatchNoForUpdate(String batchNo);
}
