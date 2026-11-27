package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findAllByAccountIdOrderByIdAsc(Long accountId);

    void deleteByAccountId(Long accountId);
}
