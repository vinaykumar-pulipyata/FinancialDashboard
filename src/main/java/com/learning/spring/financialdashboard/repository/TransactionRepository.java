package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    boolean existsByCategoryId(Long categoryId);

    /** The deposits into, and the payout of, one recurring deposit. */
    List<Transaction> findAllByRecurringDepositId(Long recurringDepositId);

    boolean existsByBorrowerId(Long borrowerId);

    /** Total of every transaction in one category. Null when there is none. */
    @Query("select sum(t.amount) from Transaction t where t.categoryId = :categoryId")
    BigDecimal sumForCategory(@Param("categoryId") Long categoryId);

    /** Rows of [borrowerId, total] for one category. */
    @Query("select t.borrowerId, sum(t.amount) from Transaction t where t.categoryId = :categoryId and t.borrowerId is not null group by t.borrowerId")
    List<Object[]> sumByBorrower(@Param("categoryId") Long categoryId);

    boolean existsByFromAccountIdOrToAccountId(Long fromAccountId, Long toAccountId);

    List<Transaction> findAllByOrderByDateDescIdDesc();

    /** Rows of [tag, number of transactions that carry it]: what the tag suggestions learn from. */
    @Query("select tag, count(t) from Transaction t join t.tags tag group by tag")
    List<Object[]> tagCounts();

    /** Rows of [accountId, total] for money coming into each account. */
    @Query("select t.toAccountId, sum(coalesce(t.toAmount, t.amount)) from Transaction t where t.toAccountId is not null group by t.toAccountId")
    List<Object[]> sumIncomingByAccount();

    /** Money that came into one account in all; null when none. */
    @Query("select sum(coalesce(t.toAmount, t.amount)) from Transaction t where t.toAccountId = :accountId")
    BigDecimal sumIncomingTo(@Param("accountId") Long accountId);

    /** Money that went out of one account in all, fees included; null when none. */
    @Query("select sum(t.amount + coalesce(t.fee, 0)) from Transaction t where t.fromAccountId = :accountId")
    BigDecimal sumOutgoingFrom(@Param("accountId") Long accountId);

    /** Rows of [accountId, total] for money going out of each account, fees included. */
    @Query("select t.fromAccountId, sum(t.amount + coalesce(t.fee, 0)) from Transaction t where t.fromAccountId is not null group by t.fromAccountId")
    List<Object[]> sumOutgoingByAccount();

    /**
     * Total money that came into one account on or after a date (opening balances are not transactions).
     * Null when there is none.
     */
    @Query("select sum(coalesce(t.toAmount, t.amount)) from Transaction t where t.toAccountId = :accountId and t.date >= :from")
    BigDecimal sumIncomingSince(@Param("accountId") Long accountId, @Param("from") LocalDate from);
}
