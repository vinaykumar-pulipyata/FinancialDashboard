package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.RecurringDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringDepositRepository extends JpaRepository<RecurringDeposit, Long> {

    List<RecurringDeposit> findAllByAccountIdOrderByIdAsc(Long accountId);

    void deleteByAccountId(Long accountId);
}
