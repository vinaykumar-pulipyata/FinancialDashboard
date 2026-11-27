package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.VehicleDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleDetailRepository extends JpaRepository<VehicleDetail, Long> {
}
