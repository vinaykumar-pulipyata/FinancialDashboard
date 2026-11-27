package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.EmergencyFundDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyFundDetailRepository extends JpaRepository<EmergencyFundDetail, Long> {
}
