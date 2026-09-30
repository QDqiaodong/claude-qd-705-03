package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.QcRound;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface QcRoundRepository extends JpaRepository<QcRound, Long> {

    /** 一个批次的全部轮次，按轮次升序（历史） */
    List<QcRound> findAllByBatchIdOrderByRoundSeqAsc(Long batchId);

    Optional<QcRound> findByClientToken(String clientToken);

    /** 取最新一轮（判断当前有效结论） */
    List<QcRound> findByBatchIdOrderByRoundSeqDesc(Long batchId, Pageable pageable);

    default Optional<QcRound> findLatestByBatchId(Long batchId) {
        List<QcRound> one = findByBatchIdOrderByRoundSeqDesc(batchId, Pageable.ofSize(1));
        return one.stream().findFirst();
    }

    /**
     * 悲观写锁取单轮（下结论/撤销时配合批次行锁一起使用）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from QcRound r where r.id = :id")
    Optional<QcRound> findByIdForUpdate(Long id);

    long countByBatchId(Long batchId);
}
