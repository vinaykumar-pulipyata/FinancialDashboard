package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.DebtDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DebtDetailRepository extends JpaRepository<DebtDetail, Long> {
}
