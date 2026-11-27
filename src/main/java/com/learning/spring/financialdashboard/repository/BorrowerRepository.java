package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Borrower;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BorrowerRepository extends JpaRepository<Borrower, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<Borrower> findAllByOrderByNameAsc();
}
