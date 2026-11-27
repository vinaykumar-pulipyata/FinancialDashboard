package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.PortfolioDetail;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PortfolioDetailRepository extends JpaRepository<PortfolioDetail, Long> {

    Optional<PortfolioDetail> findByKind(PortfolioKind kind);
}
