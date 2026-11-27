package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.RecurringDepositDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringDepositDetailRepository extends JpaRepository<RecurringDepositDetail, Long> {
}
