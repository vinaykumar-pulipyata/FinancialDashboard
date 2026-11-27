package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AccountServiceTest {

    @Autowired
    AccountService service;

    @Autowired
    AccountRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private AccountData data(String name, AccountType type, Long parentId) {
        return new AccountData(name, type, null, parentId, null, null, null);
    }

    @Test
    void createSetsDefaults() {
        Account a = service.create(data("Savings", AccountType.BANK, null));

        assertTrue(a.isActive());
        assertEquals(0, BigDecimal.ZERO.compareTo(a.getOpeningBalance()));
        assertNull(a.getTargetAmount());
        assertEquals(1, a.getLevel());
    }

    @Test
    void levelDefaultsFromTypeAndCanBeOverridden() {
        Account gold = service.create(data("Gold", AccountType.GOLD, null));
        Account fund = service.create(
                new AccountData("Emergency", AccountType.GOAL_FUND, 1, null, null, null, null));

        assertEquals(3, gold.getLevel());
        assertEquals(1, fund.getLevel());
    }

    @Test
    void aWalletIsALevelOneAssetWithItsOwnBalance() {
        Account wallet = service.create(new AccountData("PhonePe wallet", AccountType.WALLET, null, null, new BigDecimal("500"), null, null));
        assertEquals(1, wallet.getLevel());
        var summary = service.listSummaries().stream().filter(s -> s.id().equals(wallet.getId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("500").compareTo(summary.balance()));
        assertFalse(AccountType.WALLET.isLiability());
    }

    @Test
    void rejectsMissingNameOrType() {
        assertThrows(ValidationException.class, () -> service.create(data(" ", AccountType.BANK, null)));
        assertThrows(ValidationException.class, () -> service.create(data("X", null, null)));
    }

    @Test
    void rejectsDuplicateNameUnderSameParent() {
        Account parent = service.create(data("Savings", AccountType.BANK, null));
        service.create(data("Emergency", AccountType.GOAL_FUND, parent.getId()));

        assertThrows(ValidationException.class,
                () -> service.create(data("emergency", AccountType.GOAL_FUND, parent.getId())));
        assertThrows(ValidationException.class,
                () -> service.create(data("savings", AccountType.BANK, null)));
    }

    @Test
    void sameNameAllowedUnderDifferentParents() {
        Account a = service.create(data("A", AccountType.BANK, null));
        Account b = service.create(data("B", AccountType.BANK, null));
        service.create(data("Fund", AccountType.GOAL_FUND, a.getId()));
        service.create(data("Fund", AccountType.GOAL_FUND, b.getId()));
    }

    @Test
    void rejectsMissingParent() {
        assertThrows(ValidationException.class,
                () -> service.create(data("Fund", AccountType.GOAL_FUND, 999L)));
    }

    @Test
    void rejectsSelfParentAndLoops() {
        Account a = service.create(data("A", AccountType.BANK, null));
        Account b = service.create(data("B", AccountType.GOAL_FUND, a.getId()));

        assertThrows(ValidationException.class,
                () -> service.update(a.getId(), data("A", AccountType.BANK, a.getId())));
        assertThrows(ValidationException.class,
                () -> service.update(a.getId(), data("A", AccountType.BANK, b.getId())));
    }

    @Test
    void updateKeepingOwnNameIsAllowed() {
        Account a = service.create(data("A", AccountType.BANK, null));
        Account updated = service.update(a.getId(),
                new AccountData("A", AccountType.BANK, null, null, new BigDecimal("500"), null, "note"));

        assertEquals(0, new BigDecimal("500").compareTo(updated.getOpeningBalance()));
        assertEquals("note", updated.getNotes());
    }

    @Test
    void deactivateKeepsAccount() {
        Account a = service.create(data("A", AccountType.BANK, null));
        assertFalse(service.deactivate(a.getId()).isActive());
        assertTrue(service.activate(a.getId()).isActive());
    }

    @Test
    void deleteBlockedWhenChildrenExist() {
        Account a = service.create(data("A", AccountType.BANK, null));
        Account child = service.create(data("B", AccountType.GOAL_FUND, a.getId()));

        assertThrows(ValidationException.class, () -> service.delete(a.getId()));

        service.delete(child.getId());
        service.delete(a.getId());
        assertThrows(NotFoundException.class, () -> service.get(a.getId()));
    }

    @Test
    void updatingOpeningBalanceChangesTheCalculatedBalance() {
        Account a = service.create(new AccountData("A", AccountType.BANK, null, null, new BigDecimal("100"), null, null));

        service.update(a.getId(), new AccountData("A", AccountType.BANK, a.getLevel(), null, new BigDecimal("750"), null, null));

        BigDecimal balance = service.listSummaries().stream()
                .filter(s -> s.id().equals(a.getId())).findFirst().orElseThrow().balance();
        assertEquals(0, new BigDecimal("750").compareTo(balance));
    }
}
