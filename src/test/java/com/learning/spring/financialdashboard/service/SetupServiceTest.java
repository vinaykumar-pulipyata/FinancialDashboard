package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SetupServiceTest {

    @Autowired
    SetupService service;

    @Autowired
    AccountRepository repository;

    @Autowired
    CreditCardDetailRepository cardDetails;

    @BeforeEach
    void clean() {
        cardDetails.deleteAll();
        repository.deleteAll();
    }

    private SetupAccount item(String name, AccountType type, String parent, String balance) {
        return new SetupAccount(name, type, null, parent,
                balance == null ? null : new BigDecimal(balance), null, null);
    }

    @Test
    void createsAccountsWithBalancesAndDefaultLevels() {
        service.createAll(List.of(
                item("Bank Account", AccountType.BANK, null, "1000"),
                item("Home Loan", AccountType.DEBT, null, "500000")));

        List<Account> all = repository.findAllByOrderByLevelAscNameAsc();
        assertEquals(2, all.size());
        assertEquals("Bank Account", all.get(0).getName());
        assertEquals(1, all.get(0).getLevel());
        assertEquals(6, all.get(1).getLevel());
    }

    @Test
    void createsParentBeforeChildEvenWhenListedAfter() {
        service.createAll(List.of(
                item("Emergency Fund", AccountType.GOAL_FUND, "Bank Account", null),
                item("Bank Account", AccountType.BANK, null, null)));

        Account bank = repository.findAll().stream()
                .filter(a -> a.getName().equals("Bank Account")).findFirst().orElseThrow();
        Account fund = repository.findAll().stream()
                .filter(a -> a.getName().equals("Emergency Fund")).findFirst().orElseThrow();
        assertEquals(bank.getId(), fund.getParentId());
        assertNull(bank.getParentId());
    }

    @Test
    void rollsBackEverythingOnFailure() {
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("Bank Account", AccountType.BANK, null, null),
                item("Fund", AccountType.GOAL_FUND, "Nowhere", null))));

        assertEquals(0, repository.count());
    }

    @Test
    void rejectsEmptyDuplicateAndLoopingInput() {
        assertThrows(ValidationException.class, () -> service.createAll(List.of()));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("A", AccountType.BANK, null, null),
                item("a", AccountType.CASH, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("A", AccountType.BANK, "B", null),
                item("B", AccountType.BANK, "A", null))));
    }

    @Test
    void sameNameUnderDifferentParentsIsAllowedButNotUnderTheSameOne() {
        List<Account> made = service.createAll(List.of(
                item("Bank A", AccountType.BANK, null, null), item("Bank B", AccountType.BANK, null, null),
                item("Savings", AccountType.GOAL_FUND, "Bank A", null), item("Savings", AccountType.GOAL_FUND, "Bank B", null)));
        assertEquals(4, made.size());
        assertEquals(2, repository.findAll().stream().filter(a -> a.getName().equals("Savings")).count());
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("Bank C", AccountType.BANK, null, null),
                item("Pot", AccountType.GOAL_FUND, "Bank C", null), item("pot", AccountType.GOAL_FUND, "Bank C", null))));
        // a parent whose name two accounts share is ambiguous
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("Bank D", AccountType.BANK, null, null), item("Bank E", AccountType.BANK, null, null),
                item("Pot", AccountType.GOAL_FUND, "Bank D", null), item("Pot", AccountType.GOAL_FUND, "Bank E", null),
                item("Inner", AccountType.GOAL_FUND, "Pot", null))));
    }

    @Test
    void creditCardStoresLimitAndAmountOwed() {
        Account card = service.createAll(List.of(new SetupAccount("HDFC Card", AccountType.CREDIT_CARD,
                null, null, new BigDecimal("12000"), null, new BigDecimal("100000")))).get(0);

        assertEquals(0, new BigDecimal("12000").compareTo(card.getOpeningBalance()));
        assertEquals(0, new BigDecimal("100000")
                .compareTo(cardDetails.findById(card.getId()).orElseThrow().getCreditLimit()));
    }

    @Test
    void creditCardRequiresALimitGreaterThanZero() {
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                new SetupAccount("Card", AccountType.CREDIT_CARD, null, null, null, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                new SetupAccount("Card", AccountType.CREDIT_CARD, null, null, null, null, BigDecimal.ZERO))));
        assertEquals(0, repository.count());
    }

    @Test
    void receivablesNeedsNoNameAndIsNamedAutomatically() {
        Account receivables = service.createAll(List.of(
                new SetupAccount(null, AccountType.RECEIVABLE, null, null, new BigDecimal("25000"), null, null))).get(0);

        assertEquals("Receivables", receivables.getName());
        assertEquals(5, receivables.getLevel());
    }

    @Test
    void rejectsNegativeCreditLimit() {
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                new SetupAccount("Card", AccountType.CREDIT_CARD, null, null, null, null, new BigDecimal("-1")))));
    }
}
