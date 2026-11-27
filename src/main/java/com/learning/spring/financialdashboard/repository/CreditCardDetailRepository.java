package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.CreditCardDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditCardDetailRepository extends JpaRepository<CreditCardDetail, Long> {
}
