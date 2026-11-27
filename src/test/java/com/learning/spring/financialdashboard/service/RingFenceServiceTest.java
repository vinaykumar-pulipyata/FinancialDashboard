package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.TargetMode;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.EmergencyFundService.Settings;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RingFenceServiceTest {

    @Autowired
    RingFenceService service;

    @Autowired
    EmergencyFundService emergencyFund;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    EmergencyFundDetailRepository emergencyDetails;

    @Autowired
    RingFenceRepository ringFenceRepository;

    Account bank;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        ringFenceRepository.deleteAll();
        emergencyDetails.deleteAll();
        accountRepository.deleteAll();
        bank = account("Bank", AccountType.BANK, "10000", null);
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private Account account(String name, AccountType type, String opening, String target) {
        return accountService.create(new AccountData(name, type, null, null, n(opening), target == null ? null : n(target), null));
    }

    /** An Emergency Fund that has exactly hit its target of 5,000. */
    private Long fullEmergencyFund() {
        return emergencyFund.save(new Settings(null, n("5000"), TargetMode.AMOUNT, null, n("5000"), n("1000"), null)).accountId();
    }

    private Transaction record(TransactionKind kind, Long from, Long to, String amount, String note) {
        return transactionService.create(new TransactionData(LocalDate.now(), kind, from, to, n(amount), null, null, note, null, null));
    }

    private boolean fenced(Long accountId) {
        return accountService.listSummaries().stream().filter(s -> s.id().equals(accountId))
                .map(AccountSummary::ringFenced).findFirst().orElseThrow();
    }

    @Test
    void theEmergencyFundCanBeRingFencedOnceItsTargetIsReached() {
        Long fund = fullEmergencyFund();

        service.fence(fund);

        assertTrue(emergencyFund.get().ringFenced());
        assertTrue(fenced(fund));
    }

    @Test
    void notBeforeTheTargetIsReached() {
        Long fund = emergencyFund.save(new Settings(null, n("1000"), TargetMode.AMOUNT, null, n("5000"), null, null)).accountId();

        assertThrows(ValidationException.class, () -> service.fence(fund));
        assertEquals(0, ringFenceRepository.count());
        assertFalse(emergencyFund.get().ringFenced());
    }

    @Test
    void anOwnGoalNeedsATargetAndMustHaveReachedIt() {
        Account noTarget = account("Gadget", AccountType.GOAL_FUND, "9000", null);
        Account holiday = account("Holiday", AccountType.GOAL_FUND, "1000", "3000");

        assertThrows(ValidationException.class, () -> service.fence(noTarget.getId()));
        assertThrows(ValidationException.class, () -> service.fence(holiday.getId()));

        record(TransactionKind.INCOME, null, holiday.getId(), "2000", null);   // now exactly 3,000
        service.fence(holiday.getId());

        assertTrue(fenced(holiday.getId()));
    }

    @Test
    void onlyGoalFundsCanBeRingFenced() {
        assertThrows(ValidationException.class, () -> service.fence(bank.getId()));
        assertThrows(NotFoundException.class, () -> service.fence(9999L));
    }

    @Test
    void moneyCanGoInButNotOut() {
        Long fund = fullEmergencyFund();
        service.fence(fund);

        record(TransactionKind.INCOME, null, fund, "100", null);
        record(TransactionKind.TRANSFER, bank.getId(), fund, "200", null);

        ValidationException spend = assertThrows(ValidationException.class,
                () -> record(TransactionKind.EXPENSE, fund, null, "50", null));
        assertTrue(spend.getMessage().contains("ring-fenced"));
        assertThrows(ValidationException.class, () -> record(TransactionKind.TRANSFER, fund, bank.getId(), "50", null));
    }

    @Test
    void releasingLetsYouSpendAgain() {
        Long fund = fullEmergencyFund();
        service.fence(fund);
        assertThrows(ValidationException.class, () -> service.fence(fund));      // already ring-fenced

        service.release(fund);
        record(TransactionKind.EXPENSE, fund, null, "50", null);

        assertFalse(fenced(fund));
        assertThrows(ValidationException.class, () -> service.release(fund));    // no longer ring-fenced
    }

    @Test
    void anOldOutflowCanBeEditedUnlessItGetsLarger() {
        Account holiday = account("Holiday", AccountType.GOAL_FUND, "3500", "3000");
        Transaction spent = record(TransactionKind.EXPENSE, holiday.getId(), null, "500", "old");   // balance 3,000
        service.fence(holiday.getId());

        Transaction edited = transactionService.update(spent.getId(), new TransactionData(spent.getDate(),
                TransactionKind.EXPENSE, holiday.getId(), null, n("500"), null, null, "note fixed", null, null));
        assertEquals("note fixed", edited.getNote());

        assertThrows(ValidationException.class, () -> transactionService.update(spent.getId(), new TransactionData(
                spent.getDate(), TransactionKind.EXPENSE, holiday.getId(), null, n("600"), null, null, "old", null, null)));
    }

    @Test
    void deletingTheAccountRemovesItsRingFence() {
        Long fund = fullEmergencyFund();
        service.fence(fund);

        accountService.delete(fund);

        assertEquals(0, ringFenceRepository.count());
    }
}
