package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.TargetMode;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.EmergencyFundService.Settings;
import com.learning.spring.financialdashboard.service.EmergencyFundService.View;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class EmergencyFundServiceTest {

    @Autowired
    EmergencyFundService service;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    EmergencyFundDetailRepository detailRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        detailRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private Settings months(Long accountId, String opening, int months, String burn, String planned) {
        return new Settings(accountId, n(opening), TargetMode.MONTHS, months, null, n(burn),
                planned == null ? null : n(planned));
    }

    private Settings amount(Long accountId, String opening, String target, String burn, String planned) {
        return new Settings(accountId, n(opening), TargetMode.AMOUNT, null, n(target),
                burn == null ? null : n(burn), planned == null ? null : n(planned));
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private void moneyIn(Long accountId, String amount, int daysAgo) {
        transactionService.create(new TransactionData(LocalDate.now().minusDays(daysAgo), TransactionKind.INCOME,
                null, accountId, n(amount), null, null, null, null, null));
    }

    @Test
    void notConfiguredAtFirst() {
        assertFalse(service.get().configured());
    }

    @Test
    void setupCreatesTheAccountAndCalculatesFromMonths() {
        View v = service.save(months(null, "50000", 6, "20000", null));

        assertTrue(v.configured());
        assertEquals("Emergency Fund", v.name());
        assertAmount("120000", v.target());          // 6 months x 20,000
        assertAmount("70000", v.remaining());
        assertAmount("41.7", v.progressPercent());
        assertAmount("2.5", v.runwayMonths());       // 50,000 / 20,000
        assertFalse(v.targetReached());
        assertEquals(1, accountRepository.count());
    }

    @Test
    void anExistingGoalFundCanBeMarkedAsTheEmergencyFund() {
        Account goal = accountService.create(
                new AccountData("My cushion", AccountType.GOAL_FUND, null, null, n("10000"), null, null));

        View v = service.save(months(goal.getId(), "999", 3, "5000", null));

        assertEquals(goal.getId(), v.accountId());
        assertEquals("My cushion", v.name());
        assertAmount("10000", v.balance());          // the opening balance given here is ignored
        assertEquals(1, accountRepository.count());
    }

    @Test
    void onlyAGoalFundCanBeChosen() {
        Account bank = accountService.create(
                new AccountData("Bank", AccountType.BANK, null, null, n("100"), null, null));

        assertThrows(ValidationException.class, () -> service.save(months(bank.getId(), "0", 6, "1000", null)));
        assertFalse(service.get().configured());
    }

    @Test
    void savingAgainChangesTheSameFundInsteadOfCreatingAnother() {
        service.save(months(null, "50000", 6, "20000", null));

        View v = service.save(amount(null, "0", "200000", "20000", "5000"));

        assertEquals(TargetMode.AMOUNT, v.targetMode());
        assertAmount("200000", v.target());
        assertNull(v.targetMonths());
        assertEquals(1, accountRepository.count());
        assertEquals(1, detailRepository.count());
    }

    @Test
    void validatesTheSettings() {
        assertThrows(ValidationException.class,
                () -> service.save(new Settings(null, null, null, 6, null, n("1000"), null)));
        assertThrows(ValidationException.class, () -> service.save(months(null, "0", 0, "1000", null)));
        assertThrows(ValidationException.class, () -> service.save(months(null, "0", 6, "0", null)));
        assertThrows(ValidationException.class, () -> service.save(amount(null, "0", "0", null, null)));
        assertThrows(ValidationException.class, () -> service.save(amount(null, "0", "1000", "-1", null)));
        assertThrows(ValidationException.class, () -> service.save(amount(null, "0", "1000", null, "-5")));
        assertEquals(0, accountRepository.count());
    }

    @Test
    void estimateUsesThePlannedContributionWhenThereIsOne() {
        View v = service.save(months(null, "50000", 6, "20000", "10000"));

        assertEquals("PLANNED", v.contributionSource());
        assertEquals(LocalDate.now().plusMonths(7), v.estimatedDate());   // 70,000 remaining / 10,000
    }

    @Test
    void estimateFallsBackToTheAverageOfTheLastThreeMonths() {
        View first = service.save(amount(null, "0", "12000", null, null));
        moneyIn(first.accountId(), "3000", 10);
        moneyIn(first.accountId(), "600", 400);      // too old, not counted

        View v = service.get();

        assertEquals("AVERAGE", v.contributionSource());
        assertAmount("1000", v.averageMonthlyContribution());              // 3,000 / 3 months
        assertAmount("8400", v.remaining());        // the old 600 is in the balance (3,600) but not in the average
        assertEquals(LocalDate.now().plusMonths(9), v.estimatedDate());   // 8,400 / 1,000 = 8.4, rounded up
    }

    @Test
    void noEstimateWithoutAnyContribution() {
        View v = service.save(amount(null, "100", "1000", null, null));

        assertEquals("NONE", v.contributionSource());
        assertNull(v.estimatedDate());
        assertNull(v.runwayMonths());                // no burn rate entered
    }

    @Test
    void targetReachedHasNothingRemainingAndNoEstimate() {
        View v = service.save(amount(null, "5000", "5000", "1000", "100"));

        assertTrue(v.targetReached());
        assertAmount("0", v.remaining());
        assertAmount("100", v.progressPercent());
        assertNull(v.estimatedDate());
    }

    @Test
    void deletingTheAccountRemovesTheEmergencyFundSettings() {
        View v = service.save(months(null, "0", 6, "1000", null));

        accountService.delete(v.accountId());

        assertFalse(service.get().configured());
        assertEquals(0, detailRepository.count());
    }

    @Test
    void settingsWhoseAccountWasRemovedAreIgnored() {
        View v = service.save(months(null, "0", 6, "1000", null));
        accountRepository.deleteById(v.accountId());          // bypasses the service on purpose

        assertFalse(service.get().configured());
        assertEquals("Emergency Fund", service.save(months(null, "0", 6, "1000", null)).name());
    }
}
