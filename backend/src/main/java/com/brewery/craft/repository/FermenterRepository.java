package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.brewery.craft.domain.Fermenter;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface FermenterRepository extends JpaRepository<Fermenter, Long> {

    List<Fermenter> findAllByOrderByTankNoAsc();

    Optional<Fermenter> findByTankNo(String tankNo);

    boolean existsByTankNo(String tankNo);

    List<Fermenter> findAllByStatusOrderByTankNoAsc(String status);

    long countByStatus(String status);

    Optional<Fermenter> findByCurrentBatchNo(String batchNo);

    /** 迁罐确认时对罐行加悲观写锁：并发迁入同一目标罐时串行化，只允许一条成功 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select f from Fermenter f where f.id = :id")
    Optional<Fermenter> findByIdForUpdate(Long id);
}
