package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.LoanWriteOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface LoanWriteOffRepository extends JpaRepository<LoanWriteOff, Long> {

    boolean existsByBorrowerId(Long borrowerId);

    /** What each person has had written off, as (borrower id, total) rows. */
    @Query("select w.borrowerId, sum(w.amount) from LoanWriteOff w group by w.borrowerId")
    List<Object[]> sumByBorrower();

    /** Everything written off, or null when nothing is. */
    @Query("select sum(w.amount) from LoanWriteOff w")
    BigDecimal sumAll();
}
