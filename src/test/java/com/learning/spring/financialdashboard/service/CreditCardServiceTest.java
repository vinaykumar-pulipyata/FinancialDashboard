package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class CreditCardServiceTest {

    @Autowired
    CreditCardService service;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CreditCardDetailRepository details;

    @BeforeEach
    void clean() {
        details.deleteAll();
        accountRepository.deleteAll();
    }

    private Account account(String name, AccountType type) {
        return accountService.create(new AccountData(name, type, null, null, null, null, null));
    }

    @Test
    void updatesAnExistingLimit() {
        Account card = account("Card", AccountType.CREDIT_CARD);
        service.updateLimit(card.getId(), new BigDecimal("50000"));

        service.updateLimit(card.getId(), new BigDecimal("75000"));

        assertEquals(0, new BigDecimal("75000").compareTo(details.findById(card.getId()).orElseThrow().getCreditLimit()));
        assertEquals(1, details.count());
    }

    @Test
    void setsALimitForACardThatHasNone() {
        Account card = account("Card", AccountType.CREDIT_CARD);

        service.updateLimit(card.getId(), new BigDecimal("10000"));

        assertEquals(0, new BigDecimal("10000").compareTo(details.findById(card.getId()).orElseThrow().getCreditLimit()));
    }

    @Test
    void rejectsBadLimitsNonCardsAndUnknownAccounts() {
        Account card = account("Card", AccountType.CREDIT_CARD);
        Account bank = account("Bank", AccountType.BANK);

        assertThrows(ValidationException.class, () -> service.updateLimit(card.getId(), BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> service.updateLimit(card.getId(), new BigDecimal("-1")));
        assertThrows(ValidationException.class, () -> service.updateLimit(card.getId(), null));
        assertThrows(ValidationException.class, () -> service.updateLimit(bank.getId(), BigDecimal.TEN));
        assertThrows(NotFoundException.class, () -> service.updateLimit(9999L, BigDecimal.TEN));
    }

    @Test
    void aDueDayIsKeptWithTheLimitAndCanBeChangedOrCleared() {
        Account card = account("Card", AccountType.CREDIT_CARD);

        service.update(card.getId(), new BigDecimal("50000"), 21);
        assertEquals(21, details.findById(card.getId()).orElseThrow().getDueDay());

        service.update(card.getId(), new BigDecimal("60000"), 5);
        assertEquals(5, details.findById(card.getId()).orElseThrow().getDueDay());
        assertEquals(1, details.count());

        service.update(card.getId(), new BigDecimal("60000"), null);                      // none
        assertNull(details.findById(card.getId()).orElseThrow().getDueDay());
    }

    @Test
    void changingOnlyTheLimitLeavesTheDueDayAlone() {
        Account card = account("Card", AccountType.CREDIT_CARD);
        service.update(card.getId(), new BigDecimal("50000"), 28);

        service.updateLimit(card.getId(), new BigDecimal("90000"));

        assertEquals(28, details.findById(card.getId()).orElseThrow().getDueDay());
        assertEquals(0, new BigDecimal("90000").compareTo(details.findById(card.getId()).orElseThrow().getCreditLimit()));
    }

    @Test
    void aDueDayMustBeADayOfTheMonth() {
        Account card = account("Card", AccountType.CREDIT_CARD);

        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, 0));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, 32));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, -4));
        service.update(card.getId(), BigDecimal.TEN, 1);
        service.update(card.getId(), BigDecimal.TEN, 31);                                 // the longest month; shorter ones use their last day
        assertEquals(1, details.count());
    }

    @Test
    void aStatementDayIsSeparateFromTheDueDayAndCanBeSetChangedOrCleared() {
        Account card = account("Card", AccountType.CREDIT_CARD);

        service.update(card.getId(), new BigDecimal("50000"), 25, 5);
        assertEquals(25, details.findById(card.getId()).orElseThrow().getDueDay());
        assertEquals(5, details.findById(card.getId()).orElseThrow().getStatementDay());

        service.update(card.getId(), new BigDecimal("50000"), 25, 31);                    // a short month uses its last day, so 31 is allowed
        assertEquals(31, details.findById(card.getId()).orElseThrow().getStatementDay());

        service.update(card.getId(), new BigDecimal("50000"), 25, null);                  // none
        assertNull(details.findById(card.getId()).orElseThrow().getStatementDay());
        assertEquals(25, details.findById(card.getId()).orElseThrow().getDueDay());
        assertEquals(1, details.count());
    }

    @Test
    void changingOnlyTheLimitOrTheDueDayLeavesTheStatementDayAlone() {
        Account card = account("Card", AccountType.CREDIT_CARD);
        service.update(card.getId(), new BigDecimal("50000"), 25, 5);

        service.updateLimit(card.getId(), new BigDecimal("90000"));
        assertEquals(5, details.findById(card.getId()).orElseThrow().getStatementDay());

        service.update(card.getId(), new BigDecimal("90000"), 28);                        // the three-argument form: limit and due day only
        assertEquals(28, details.findById(card.getId()).orElseThrow().getDueDay());
        assertEquals(5, details.findById(card.getId()).orElseThrow().getStatementDay());
    }

    @Test
    void aStatementDayMustBeADayOfTheMonth() {
        Account card = account("Card", AccountType.CREDIT_CARD);

        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, 25, 0));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, 25, 32));
        assertThrows(ValidationException.class, () -> service.update(card.getId(), BigDecimal.TEN, 25, -1));
        assertEquals(0, details.count(), "nothing is saved when a day is refused");
    }
}
