package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BorrowerRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.LoanService.BorrowerData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Account naming, parents, and the Receivables balance that is worked out from loans. */
@SpringBootTest
class AccountServiceValidationTest {

    @Autowired
    AccountService service;
    @Autowired
    LoanService loanService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    SystemCategories systemCategories;
    @Autowired
    AccountRepository accounts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    BorrowerRepository borrowers;
    @Autowired
    CategoryRepository categories;

    @BeforeEach
    @AfterEach
    void clean() {
        transactions.deleteAll();
        borrowers.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    private AccountData data(String name, AccountType type, Long parentId) {
        return new AccountData(name, type, null, parentId, new BigDecimal("100"), null, null);
    }

    @Test
    void aTypeIsRequiredAndOnlyReceivablesMayBeUnnamed() {
        assertThrows(ValidationException.class, () -> service.create(data("X", null, null)));
        assertThrows(ValidationException.class, () -> service.create(data("  ", AccountType.BANK, null)));
        assertThrows(ValidationException.class, () -> service.create(data(null, AccountType.BANK, null)));
        assertEquals("Receivables", service.create(data(null, AccountType.RECEIVABLE, null)).getName());
    }

    @Test
    void listReturnsEveryAccountByLevelThenName() {
        service.create(data("Zeta", AccountType.BANK, null));
        service.create(data("Alpha", AccountType.BANK, null));
        service.create(data("Gold", AccountType.GOLD, null));
        List<Account> all = service.list();
        assertEquals(List.of("Alpha", "Zeta", "Gold"), all.stream().map(Account::getName).toList());
    }

    @Test
    void parentsMustExistAndCannotLoopOrBeThemselves() {
        Account parent = service.create(data("Parent", AccountType.BANK, null));
        Account child = service.create(data("Child", AccountType.BANK, parent.getId()));
        Account grandChild = service.create(data("Grand", AccountType.BANK, child.getId()));

        assertThrows(ValidationException.class, () -> service.create(data("Orphan", AccountType.BANK, 987654L)));
        assertThrows(ValidationException.class, () -> service.update(parent.getId(), data("Parent", AccountType.BANK, parent.getId())));
        assertThrows(ValidationException.class, () -> service.update(parent.getId(), data("Parent", AccountType.BANK, grandChild.getId())));
    }

    @Test
    void namesAreUniqueUnderOneParentButAnAccountMayKeepItsOwn() {
        Account parent = service.create(data("Parent", AccountType.BANK, null));
        Account other = service.create(data("Other", AccountType.BANK, null));
        Account child = service.create(data("Child", AccountType.BANK, parent.getId()));

        assertThrows(ValidationException.class, () -> service.create(data("child", AccountType.BANK, parent.getId())));
        assertThrows(ValidationException.class, () -> service.create(data("OTHER", AccountType.BANK, null)));
        service.create(data("Child", AccountType.BANK, other.getId()));   // same name under another parent is fine
        service.update(child.getId(), data("CHILD", AccountType.BANK, parent.getId()));   // keeps its own name (case change)
    }

    @Test
    void aMissingAccountIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.get(987654L));
        assertThrows(NotFoundException.class, () -> service.delete(987654L));
    }

    @Test
    void receivablesBalanceFollowsLoansGivenAndCollected() {
        systemCategories.ensureAll();
        Account bank = service.create(new AccountData("Bank", AccountType.BANK, null, null, new BigDecimal("10000"), null, null));
        Account receivables = service.create(new AccountData(null, AccountType.RECEIVABLE, null, null, new BigDecimal("500"), null, null));
        Long person = loanService.create(new BorrowerData("Ravi", null)).getId();
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null, new BigDecimal("2000"),
                null, systemCategories.require(SystemCategories.LOANS_GIVEN).getId(), null, null, person));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, bank.getId(), new BigDecimal("700"),
                null, systemCategories.require(SystemCategories.LOANS_COLLECTED).getId(), null, null, person));

        AccountSummary summary = service.listSummaries().stream().filter(s -> s.id().equals(receivables.getId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1800").compareTo(summary.balance()));   // 500 + 2000 - 700
        assertTrue(service.listSummaries().stream().anyMatch(s -> s.name().equals("Bank")));
    }

    @Test
    void aJewelleryAccountKeepsItsTypeAndOpeningBalanceButCanBeRenamed() {
        Account jewellery = service.create(new AccountData("Jewellery", AccountType.JEWELLERY, null, null, new BigDecimal("500"), null, null));
        assertThrows(ValidationException.class, () ->
                service.update(jewellery.getId(), new AccountData("Jewellery", AccountType.JEWELLERY, null, null, new BigDecimal("900"), null, null)));
        assertThrows(ValidationException.class, () ->
                service.update(jewellery.getId(), new AccountData("Jewellery", AccountType.OTHER, null, null, new BigDecimal("500"), null, null)));
        Account renamed = service.update(jewellery.getId(), new AccountData("Gold and silver", AccountType.JEWELLERY, null, null, new BigDecimal("500"), null, "kept at home"));
        assertEquals("Gold and silver", renamed.getName());
        assertEquals(0, new BigDecimal("500").compareTo(renamed.getOpeningBalance()));
    }

    @Test
    void thereIsOnlyOneReceivablesAndOneJewelleryAccount() {
        service.create(data("Receivables", AccountType.RECEIVABLE, null));
        assertThrows(ValidationException.class, () -> service.create(data("Money lent", AccountType.RECEIVABLE, null)));
        service.create(data("Jewellery", AccountType.JEWELLERY, null));
        assertThrows(ValidationException.class, () -> service.create(data("More jewellery", AccountType.JEWELLERY, null)));
        // every other type can be added as often as you like
        service.create(data("Bank one", AccountType.BANK, null));
        service.create(data("Bank two", AccountType.BANK, null));
        assertEquals(4, accounts.count());
    }

    @Test
    void anOrdinaryAccountCannotBeTurnedIntoOneThatNeedsItsOwnDetails() {
        Account bank = service.create(data("Spare", AccountType.BANK, null));
        for (AccountType type : List.of(AccountType.CREDIT_CARD, AccountType.RECEIVABLE, AccountType.JEWELLERY)) {
            assertThrows(ValidationException.class, () -> service.update(bank.getId(), data("Spare", type, null)), type.name());
        }
        assertEquals(AccountType.BANK, service.get(bank.getId()).getType());
    }

    @Test
    void aReceivablesJewelleryOrCardAccountCannotBeChangedToAnotherType() {
        Account receivables = service.create(data("Receivables", AccountType.RECEIVABLE, null));
        Account card = service.create(data("Card", AccountType.CREDIT_CARD, null));
        assertThrows(ValidationException.class, () -> service.update(receivables.getId(), data("Receivables", AccountType.OTHER, null)));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), data("Card", AccountType.DEBT, null)));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), data("Card", AccountType.BANK, null)));
    }

    @Test
    void anAssetCannotBecomeADebtOrBack_butTypesOnTheSameSideCanChange() {
        Account bank = service.create(data("Spare", AccountType.BANK, null));
        Account loan = service.create(data("Friend", AccountType.DEBT, null));
        assertThrows(ValidationException.class, () -> service.update(bank.getId(), data("Spare", AccountType.DEBT, null)));
        assertThrows(ValidationException.class, () -> service.update(loan.getId(), data("Friend", AccountType.BANK, null)));

        assertEquals(AccountType.CASH, service.update(bank.getId(), data("Spare", AccountType.CASH, null)).getType());
        assertEquals(AccountType.OTHER, service.update(bank.getId(), data("Spare", AccountType.OTHER, null)).getType());
        // saving an account with its type unchanged is always fine
        service.update(bank.getId(), data("Renamed", AccountType.OTHER, null));
    }

    @Test
    void loansAreRefusedWithAClearMessageWhileTheReceivablesAccountIsSwitchedOff() {
        Account receivables = loanService.ensureReceivablesAccount();
        service.deactivate(receivables.getId());
        ValidationException e = assertThrows(ValidationException.class, () -> loanService.ensureReceivablesAccount());
        assertTrue(e.getMessage().contains("switched off"), e.getMessage());
        assertEquals(1, accounts.count(), "no second Receivables account was made");
    }
}
