package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.SplitSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** Settlements of split expenses, and (through native queries on transaction_split) the shares people owe. */
public interface SplitSettlementRepository extends JpaRepository<SplitSettlement, Long> {

    List<SplitSettlement> findAllByBorrowerId(Long borrowerId);

    List<SplitSettlement> findAllByExpenseTransactionId(Long expenseTransactionId);

    boolean existsByExpenseTransactionId(Long expenseTransactionId);

    boolean existsByIncomeTransactionId(Long incomeTransactionId);

    void deleteByIncomeTransactionId(Long incomeTransactionId);

    /** What each person owes in total from split expenses, as (borrower id, total) rows. */
    @Query(value = "select borrower_id, sum(share) from transaction_split group by borrower_id", nativeQuery = true)
    List<Object[]> sumSharesByBorrower();

    /** Every share of every split expense, or null when nothing is split. */
    @Query(value = "select sum(share) from transaction_split", nativeQuery = true)
    BigDecimal sumSharesAll();

    @Query(value = "select count(*) from transaction_split where borrower_id = :borrowerId", nativeQuery = true)
    long countSharesOf(@Param("borrowerId") Long borrowerId);

    /** What each person paid for you that you owe them, as (borrower id, total of your shares) rows. */
    @Query(value = "select paid_by_borrower_id, sum(own_share) from financial_transaction where paid_by_borrower_id is not null group by paid_by_borrower_id",
            nativeQuery = true)
    List<Object[]> sumPaidForYouByBorrower();

    /** What you have paid back to each person for expenses they paid, as (borrower id, total) rows. */
    @Query(value = "select s.borrower_id, sum(s.amount) from split_settlement s join financial_transaction e on e.id = s.expense_transaction_id "
            + "where e.paid_by_borrower_id is not null and s.income_transaction_id <> 0 group by s.borrower_id", nativeQuery = true)
    List<Object[]> sumRepaidByBorrower();

    /**
     * What has been set off between a person's bills (what you owe them) and their shares (what they owe you) without any money moving, as
     * (borrower id, total) rows. An offset is saved as two rows with income_transaction_id 0 (no payment), one on a share and one on a bill, so the
     * total is counted on the bills side only.
     */
    @Query(value = "select s.borrower_id, sum(s.amount) from split_settlement s join financial_transaction e on e.id = s.expense_transaction_id "
            + "where e.paid_by_borrower_id is not null and s.income_transaction_id = 0 group by s.borrower_id", nativeQuery = true)
    List<Object[]> sumOffsetByBorrower();

    /** Everything set off, or null when nothing is. */
    @Query(value = "select sum(s.amount) from split_settlement s join financial_transaction e on e.id = s.expense_transaction_id "
            + "where e.paid_by_borrower_id is not null and s.income_transaction_id = 0", nativeQuery = true)
    BigDecimal sumOffsetAll();

    /** What you owe all people for bills they paid for you in all (your shares), or null when none. */
    @Query(value = "select sum(own_share) from financial_transaction where paid_by_borrower_id is not null", nativeQuery = true)
    BigDecimal sumPaidForYouAll();

    /** Everything paid back for expenses others paid, or null when nothing is. */
    @Query(value = "select sum(s.amount) from split_settlement s join financial_transaction e on e.id = s.expense_transaction_id "
            + "where e.paid_by_borrower_id is not null and s.income_transaction_id <> 0", nativeQuery = true)
    BigDecimal sumRepaidAll();

    @Query(value = "select count(*) from financial_transaction where paid_by_borrower_id = :borrowerId", nativeQuery = true)
    long countPaidBy(@Param("borrowerId") Long borrowerId);

    /** The ids of the expenses a person paid for you. */
    @Query(value = "select id from financial_transaction where paid_by_borrower_id = :borrowerId", nativeQuery = true)
    List<Long> paidForYouExpenseIds(@Param("borrowerId") Long borrowerId);

    /** The ids of the expenses a person has a share in. */
    @Query(value = "select transaction_id from transaction_split where borrower_id = :borrowerId", nativeQuery = true)
    List<Long> splitExpenseIds(@Param("borrowerId") Long borrowerId);
}
