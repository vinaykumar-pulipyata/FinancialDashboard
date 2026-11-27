package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.LedgerDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerDetailRepository extends JpaRepository<LedgerDetail, Long> {
}
