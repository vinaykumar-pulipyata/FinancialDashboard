package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Currency;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByNameIgnoreCaseAndParentId(String name, Long parentId);

    boolean existsByNameIgnoreCaseAndParentIdIsNull(String name);

    boolean existsByParentId(Long parentId);

    boolean existsByType(AccountType type);

    boolean existsByCurrency(Currency currency);

    List<Account> findAllByOrderByLevelAscNameAsc();
}
