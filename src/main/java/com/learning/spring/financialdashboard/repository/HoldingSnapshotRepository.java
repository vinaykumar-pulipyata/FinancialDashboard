package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.HoldingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HoldingSnapshotRepository extends JpaRepository<HoldingSnapshot, Long> {

    List<HoldingSnapshot> findAllByAccountIdOrderByDateAscIdAsc(Long accountId);

    Optional<HoldingSnapshot> findByHoldingIdAndDate(Long holdingId, LocalDate date);

    boolean existsByHoldingId(Long holdingId);

    void deleteByAccountId(Long accountId);
}
