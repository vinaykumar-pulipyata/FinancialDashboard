package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.RingFence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RingFenceRepository extends JpaRepository<RingFence, Long> {
}
