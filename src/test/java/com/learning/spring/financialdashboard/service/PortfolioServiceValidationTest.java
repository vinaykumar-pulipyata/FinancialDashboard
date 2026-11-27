package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.RingFence;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.PortfolioService.DividendRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingData;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingView;
import com.learning.spring.financialdashboard.service.PortfolioService.InvestRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.ValueRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.View;
import com.learning.spring.financialdashboard.service.PortfolioService.WithdrawRequest;
import org.junit.jupiter.api.AfterEach;
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

/** The Mutual Funds and Shares cards' refusals and defaults. */
@SpringBootTest
class PortfolioServiceValidationTest {

    private static final PortfolioKind MF = PortfolioKind.MUTUAL_FUNDS;
    private static final PortfolioKind SH = PortfolioKind.SHARES;

    @Autowired
    PortfolioService service;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    AccountRepository accounts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    HoldingRepository holdings;
    @Autowired
    HoldingSnapshotRepository snapshots;
    @Autowired
    PortfolioDetailRepository portfolios;
    @Autowired
    RingFenceRepository ringFences;
    @Autowired
    CategoryRepository categories;

    Account bank;

    private void wipe() {
        transactions.deleteAll();
        snapshots.deleteAll();
        holdings.deleteAll();
        portfolios.deleteAll();
        ringFences.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    @BeforeEach
    void setUp() {
        wipe();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
        service.setUp(MF);
        service.setUp(SH);
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private Long addOne(PortfolioKind kind, String name, String invested, String value) {
        View v = service.addHolding(kind, new HoldingData(name, invested == null ? null : n(invested), value == null ? null : n(value)));
        return v.holdings().stream().filter(h -> h.name().equals(name)).findFirst().map(HoldingView::id).orElseThrow();
    }

    @Test
    void aKindThatIsNotSetUpIsEmptyAndRefusesChanges() {
        portfolios.deleteAll();
        assertFalse(service.get(MF).configured());
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("A", n("1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), n("1"))));
        assertEquals(0, service.stats(MF).overall().size());
    }

    @Test
    void anAccountWithTheCardsNameBlocksSettingItUp() {
        portfolios.deleteAll();
        // The accounts called "Mutual Funds" and "Shares" are still there, only no longer marked as portfolios.
        assertThrows(ValidationException.class, () -> service.setUp(MF));
    }

    @Test
    void missingAmountsDefaultAndNegativeOnesAreRefused() {
        Long bare = addOne(MF, "Bare", null, null);                     // invested 0, value = invested
        Long valued = addOne(MF, "Valued", "100", null);               // value defaults to the invested amount
        View v = service.get(MF);
        assertEquals(0, BigDecimal.ZERO.compareTo(v.holdings().stream().filter(h -> h.id().equals(bare)).findFirst().orElseThrow().currentValue()));
        assertEquals(0, n("100").compareTo(v.holdings().stream().filter(h -> h.id().equals(valued)).findFirst().orElseThrow().currentValue()));

        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("Neg", n("-1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("Neg", n("1"), n("-1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("  ", n("1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("bare", n("1"), n("1"))));   // same name
    }

    @Test
    void updatingAValueNeedsAValueAndNotAFutureDate() {
        Long fund = addOne(MF, "Index", "100", "100");
        assertThrows(ValidationException.class, () -> service.updateValue(MF, fund, new ValueRequest(null, null, null)));
        assertThrows(ValidationException.class, () -> service.updateValue(MF, fund, new ValueRequest(n("-1"), null, null)));
        assertThrows(ValidationException.class, () -> service.updateValue(MF, fund, new ValueRequest(n("50"), null, LocalDate.now().plusDays(1))));
        service.updateValue(MF, fund, new ValueRequest(n("120"), null, null));   // today, invested unchanged
    }

    @Test
    void investingAndWithdrawingNeedAnAmountAndABankOrCashAccount() {
        Long fund = addOne(MF, "Index", "100", "100");
        Account gold = accountService.create(new AccountData("Gold", AccountType.GOLD, null, null, n("10"), null, null));
        Account dormant = accountService.create(new AccountData("Dormant", AccountType.BANK, null, null, n("10"), null, null));
        accountService.deactivate(dormant.getId());

        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), null)));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), BigDecimal.ZERO)));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(null, n("5"))));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(gold.getId(), n("5"))));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(dormant.getId(), n("5"))));
        assertThrows(ValidationException.class, () -> service.withdraw(MF, fund, new WithdrawRequest(bank.getId(), null)));
        assertThrows(ValidationException.class, () -> service.withdraw(MF, fund, new WithdrawRequest(bank.getId(), n("101"))));
        assertThrows(ValidationException.class, () -> service.withdraw(MF, fund, new WithdrawRequest(null, n("5"))));
    }

    @Test
    void aRingFencedAccountCannotBeInvestedFrom() {
        ringFences.save(new RingFence(bank.getId()));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), n("5"))));
    }

    @Test
    void dividendsNeedAnAmountAHonestDateAndAnAccountAndAreFiledWhenTheCategoriesExist() {
        Long share = addOne(SH, "Acme", "100", "100");
        assertThrows(ValidationException.class, () -> service.dividend(SH, share, new DividendRequest(bank.getId(), null, null)));
        assertThrows(ValidationException.class, () -> service.dividend(SH, share, new DividendRequest(bank.getId(), n("5"), LocalDate.now().plusDays(1))));
        assertThrows(ValidationException.class, () -> service.dividend(SH, share, new DividendRequest(null, n("5"), null)));

        service.dividend(SH, share, new DividendRequest(bank.getId(), n("5"), null));   // today, uncategorised
        assertTrue(transactions.findAll().stream().allMatch(t -> t.getCategoryId() == null));

        Category parent = categoryService.create(new CategoryData("Investment Income", CategoryKind.INCOME, null));
        Category dividends = categoryService.create(new CategoryData("Dividends", CategoryKind.INCOME, parent.getId()));
        service.dividend(SH, share, new DividendRequest(bank.getId(), n("7"), LocalDate.now().minusDays(1)));
        assertTrue(transactions.findAll().stream().anyMatch(t -> dividends.getId().equals(t.getCategoryId()) && share.equals(t.getHoldingId())));
    }

    @Test
    void aHoldingBelongsToOneCardAndOnlyAWorthlessOneCanBeRemoved() {
        Long fund = addOne(MF, "Index", "100", "100");
        assertThrows(NotFoundException.class, () -> service.rename(SH, fund, new PortfolioService.RenameRequest("Other")));   // wrong card
        assertThrows(NotFoundException.class, () -> service.deleteHolding(MF, null));
        assertThrows(NotFoundException.class, () -> service.deleteHolding(MF, 987654L));
        assertThrows(ValidationException.class, () -> service.deleteHolding(MF, fund));

        service.withdraw(MF, fund, new WithdrawRequest(bank.getId(), n("100")));
        service.deleteHolding(MF, fund);
        assertTrue(service.get(MF).holdings().isEmpty());
    }

    @Test
    void startUpGivesEveryHoldingWithoutARecordAFirstOne() {
        Long fund = addOne(MF, "Index", "100", "100");
        snapshots.deleteAll();
        assertFalse(snapshots.existsByHoldingId(fund));

        service.recordMissingSnapshots();

        assertTrue(snapshots.existsByHoldingId(fund));
        service.recordMissingSnapshots();   // nothing missing now
        assertEquals(1, snapshots.count());
    }
}
