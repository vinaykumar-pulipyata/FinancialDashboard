package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findAllByOrderByIdAsc();

    Optional<Budget> findByCategoryId(Long categoryId);

    /** The limit on all spending (the row with no category). */
    Optional<Budget> findByCategoryIdIsNull();

    void deleteByCategoryId(Long categoryId);
}
