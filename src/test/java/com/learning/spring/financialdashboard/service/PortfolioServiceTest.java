package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.PortfolioKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.NetWorthService.Point;
import com.learning.spring.financialdashboard.service.PortfolioService.DividendRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingData;
import com.learning.spring.financialdashboard.service.PortfolioService.HoldingView;
import com.learning.spring.financialdashboard.service.PortfolioService.InvestRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.RenameRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.StatPoint;
import com.learning.spring.financialdashboard.service.PortfolioService.Stats;
import com.learning.spring.financialdashboard.service.PortfolioService.ValueRequest;
import com.learning.spring.financialdashboard.service.PortfolioService.View;
import com.learning.spring.financialdashboard.service.PortfolioService.WithdrawRequest;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import java.util.Comparator;
import com.learning.spring.financialdashboard.model.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PortfolioServiceTest {

    static final PortfolioKind MF = PortfolioKind.MUTUAL_FUNDS;

    @Autowired
    PortfolioService service;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    NetWorthService netWorthService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    PortfolioDetailRepository portfolioRepository;

    @Autowired
    HoldingRepository holdingRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    HoldingSnapshotRepository snapshotRepository;

    Account bank;

    @BeforeEach
    void clean() {
        wipe();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        transactionRepository.deleteAll();
        snapshotRepository.deleteAll();
        holdingRepository.deleteAll();
        portfolioRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private HoldingView only(View v) {
        assertEquals(1, v.holdings().size());
        return v.holdings().get(0);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountService.listSummaries().stream().filter(a -> a.id().equals(accountId))
                .map(AccountSummary::balance).findFirst().orElseThrow();
    }

    @Test
    void notSetUpAtFirst() {
        assertFalse(service.get(MF).configured());
    }

    @Test
    void setUpCreatesTheLevelThreeInvestmentAccountOnce() {
        View v = service.setUp(MF);
        service.setUp(MF);

        assertTrue(v.configured());
        assertEquals("Mutual Funds", v.name());
        Account account = accountRepository.findById(v.accountId()).orElseThrow();
        assertEquals(AccountType.INVESTMENT, account.getType());
        assertEquals(3, account.getLevel());
        assertEquals(2, accountRepository.count());          // the bank and this one
    }

    @Test
    void mutualFundsAndSharesAreSeparateAccounts() {
        View funds = service.setUp(MF);
        View shares = service.setUp(PortfolioKind.SHARES);

        assertEquals("Shares", shares.name());
        assertFalse(funds.accountId().equals(shares.accountId()));
    }

    @Test
    void anAccountWithTheSameNameBlocksSetUp() {
        accountService.create(new AccountData("Mutual Funds", AccountType.BANK, null, null, n("0"), null, null));

        assertThrows(ValidationException.class, () -> service.setUp(MF));
    }

    @Test
    void addingAFundCalculatesGainAndPercent() {
        service.setUp(MF);

        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12500"))));

        assertAmount("10000", h.investedAmount());
        assertAmount("12500", h.currentValue());
        assertAmount("2500", h.gain());
        assertAmount("25", h.gainPercent());
    }

    @Test
    void aLossIsNegative() {
        service.setUp(MF);

        HoldingView h = only(service.addHolding(MF, new HoldingData("Small cap", n("8000"), n("6000"))));

        assertAmount("-2000", h.gain());
        assertAmount("-25", h.gainPercent());
    }

    @Test
    void noPercentWhenNothingWasInvested() {
        service.setUp(MF);

        HoldingView h = only(service.addHolding(MF, new HoldingData("Gift units", n("0"), n("500"))));

        assertAmount("500", h.gain());
        assertNull(h.gainPercent());
    }

    @Test
    void theCardTotalsAddUpTheFunds() {
        service.setUp(MF);
        service.addHolding(MF, new HoldingData("A", n("10000"), n("12000")));
        View v = service.addHolding(MF, new HoldingData("B", n("5000"), n("4000")));

        assertAmount("15000", v.investedAmount());
        assertAmount("16000", v.currentValue());
        assertAmount("1000", v.gain());
        assertAmount("6.7", v.gainPercent());
    }

    @Test
    void validatesNamesAndAmounts() {
        service.setUp(MF);
        service.addHolding(MF, new HoldingData("Index fund", n("100"), n("100")));

        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData(" ", n("1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("index FUND", n("1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("X", n("-1"), n("1"))));
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("Y", n("1"), n("-1"))));
    }

    @Test
    void cannotAddBeforeSetUp() {
        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("A", n("1"), n("1"))));
    }

    @Test
    void renamingKeepsTheNumbers() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Old", n("100"), n("150"))));

        HoldingView renamed = only(service.rename(MF, h.id(), new RenameRequest("New")));

        assertEquals("New", renamed.name());
        assertAmount("150", renamed.currentValue());
    }

    @Test
    void updatingTheValueMovesNoMoneyAndRecordsNoTransaction() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("10000"))));

        HoldingView updated = only(service.updateValue(MF, h.id(), new ValueRequest(n("11500"), null, null)));

        assertAmount("10000", updated.investedAmount());
        assertAmount("11500", updated.currentValue());
        assertAmount("1500", updated.gain());
        assertEquals(0, transactionRepository.count());
        assertAmount("100000", balanceOf(bank.getId()));
    }

    @Test
    void totalInvestedStartsAsWhatTheFundsWereAddedWith() {
        service.setUp(MF);
        service.addHolding(MF, new HoldingData("A", n("10000"), n("12000")));
        View v = service.addHolding(MF, new HoldingData("B", n("5000"), n("4000")));

        assertAmount("15000", v.totalInvested());
        assertAmount("15000", v.investedAmount());
    }

    @Test
    void investTakesMoneyFromTheAccountAndAddsItToTotalInvested() {
        View set = service.setUp(MF);
        service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000")));

        View v = service.invest(MF, new InvestRequest(bank.getId(), n("5000")));

        assertAmount("15000", v.totalInvested());
        assertAmount("10000", v.investedAmount());                 // the funds are updated by hand
        assertAmount("12000", v.currentValue());
        assertAmount("5000", v.totalInvested().subtract(v.investedAmount()));
        assertAmount("95000", balanceOf(bank.getId()));            // the money left the bank
        assertAmount("10000", balanceOf(set.accountId()));         // the account counts what was invested in the funds, not their value
        assertEquals(1, transactionRepository.count());
        var t = transactionRepository.findAll().get(0);
        assertEquals(TransactionKind.TRANSFER, t.getKind());
        assertEquals(bank.getId(), t.getFromAccountId());
        assertEquals(set.accountId(), t.getToAccountId());
    }

    @Test
    void investIntoAFundRaisesItsInvestedAmountOnly() {
        service.setUp(MF);
        HoldingView fund = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000"))));

        HoldingView h = only(service.invest(MF, new InvestRequest(bank.getId(), n("5000"), fund.id(), null)));

        assertAmount("15000", h.investedAmount());
        assertAmount("12000", h.currentValue());
    }

    @Test
    void investIntoANewFundCreatesIt() {
        service.setUp(MF);

        View v = service.invest(MF, new InvestRequest(bank.getId(), n("5000"), null, " Gold fund "));

        HoldingView h = only(v);
        assertEquals("Gold fund", h.name());
        assertAmount("5000", h.investedAmount());
        assertAmount("5000", h.currentValue());
        assertAmount("5000", v.totalInvested());
        assertThrows(ValidationException.class,
                () -> service.invest(MF, new InvestRequest(bank.getId(), n("1"), null, "gold fund")));
        assertEquals(1, transactionRepository.count());
    }

    @Test
    void deletingAnInvestmentReturnsTheMoneyAndTakesItOutOfTheFund() {
        service.setUp(MF);
        HoldingView fund = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000"))));
        service.invest(MF, new InvestRequest(bank.getId(), n("5000"), fund.id(), null));
        Long id = transactionRepository.findAll().get(0).getId();

        View v = service.deleteInvestment(MF, id);

        assertAmount("10000", only(v).investedAmount());
        assertAmount("12000", only(v).currentValue());
        assertAmount("10000", v.totalInvested());
        assertAmount("100000", balanceOf(bank.getId()));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void deletingTheInvestmentThatCreatedAFundEmptiesIt() {
        service.setUp(MF);
        service.invest(MF, new InvestRequest(bank.getId(), n("5000"), null, "Gold fund"));
        Long id = transactionRepository.findAll().get(0).getId();

        View v = service.deleteInvestment(MF, id);

        assertAmount("0", only(v).investedAmount());
        assertAmount("0", only(v).currentValue());
        assertAmount("0", v.totalInvested());
        assertThrows(NotFoundException.class, () -> service.deleteInvestment(MF, id));
    }

    @Test
    void deletingAnOlderInvestmentTakesItOutOfTheChosenFund() {
        service.setUp(MF);
        HoldingView fund = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000"))));
        service.invest(MF, new InvestRequest(bank.getId(), n("5000")));      // no fund recorded
        Long id = transactionRepository.findAll().get(0).getId();

        View v = service.deleteInvestment(MF, id, fund.id());

        assertAmount("5000", only(v).investedAmount());
        assertAmount("10000", v.totalInvested());
        assertAmount("100000", balanceOf(bank.getId()));
    }

    @Test
    void investIsRefusedWhenTheAccountDoesNotHoldEnough() {
        service.setUp(MF);
        var ex = assertThrows(ValidationException.class,
                () -> service.invest(MF, new InvestRequest(bank.getId(), n("100001"), null, "Gold fund")));
        assertTrue(ex.getMessage().startsWith("Insufficient balance in 'Bank'"));
        assertEquals(0, transactionRepository.count());
        service.invest(MF, new InvestRequest(bank.getId(), n("100000"), null, "Gold fund"));
    }

    @Test
    void investRejectsBadInput() {
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), n("10"))));
        service.setUp(MF);
        Account gold = accountService.create(new AccountData("Gold", AccountType.GOLD, null, null, n("500"), null, null));

        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), n("0"))));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(bank.getId(), null)));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(null, n("10"))));
        assertThrows(ValidationException.class, () -> service.invest(MF, new InvestRequest(gold.getId(), n("10"))));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void withdrawingTakesTheCostOffTotalInvestedAndBooksTheProfit() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("20000"))));

        View v = service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("10000")));   // half of it: it cost 5000 and paid 10000

        assertAmount("5000", v.investedAmount());
        assertAmount("5000", v.totalInvested());   // Total invested comes down by the cost of what was sold
        assertAmount("0", v.totalInvested().subtract(v.investedAmount()));   // so the difference stays zero
        assertAmount("5000", v.realizedGain());   // the sale made 10000 on a cost of 5000
    }

    @Test
    void sellingEverythingLeavesNothingInvestedAndKeepsTheProfitAndLoss() {
        service.setUp(MF);
        HoldingView up = only(service.addHolding(MF, new HoldingData("Winner", n("60000"), n("63756.55"))));
        service.addHolding(MF, new HoldingData("Loser", n("1000"), n("900")));
        HoldingView down = service.get(MF).holdings().stream().filter(x -> x.name().equals("Loser")).findFirst().orElseThrow();

        service.withdraw(MF, up.id(), new WithdrawRequest(bank.getId(), n("63756.55")));
        View v = service.withdraw(MF, down.id(), new WithdrawRequest(bank.getId(), n("900")));

        assertAmount("0", v.investedAmount());
        assertAmount("0", v.totalInvested());
        assertAmount("0", v.currentValue());
        assertAmount("3656.55", v.realizedGain());   // 3756.55 made on one and 100 lost on the other
    }

    @Test
    void aPortfolioWithNoSalesHasMadeNothing() {
        service.setUp(MF);
        service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000")));

        assertAmount("0", service.get(MF).realizedGain());
    }

    @Test
    void aQuantityIsOptionalCanBeCorrectedAndComesDownWithAWithdrawal() {
        service.setUp(MF);
        HoldingView plain = only(service.addHolding(MF, new HoldingData("No quantity", n("1000"), n("1000"))));
        assertNull(plain.quantity(), "left out: nothing is made up");

        HoldingView h = service.get(MF).holdings().stream().filter(x -> x.name().equals("No quantity")).findFirst().orElseThrow();
        service.updateValue(MF, h.id(), new ValueRequest(n("1000"), null, null, n("12.5")));
        assertAmount("12.5", service.get(MF).holdings().get(0).quantity());
        service.updateValue(MF, h.id(), new ValueRequest(n("1000"), null, null));
        assertAmount("12.5", service.get(MF).holdings().get(0).quantity());         // not given: unchanged

        HoldingView shares = service.get(MF).holdings().get(0);
        service.withdraw(MF, shares.id(), new WithdrawRequest(bank.getId(), n("250")));            // a quarter of its value
        assertAmount("9.375", service.get(MF).holdings().get(0).quantity());

        assertThrows(ValidationException.class, () -> service.addHolding(MF, new HoldingData("Bad", n("1"), n("1"), n("-1"))));
        assertThrows(ValidationException.class, () -> service.updateValue(MF, shares.id(), new ValueRequest(n("1"), null, null, n("-2"))));
    }

    @Test
    void updatingTheFundsNeverChangesTotalInvested() {
        View set = service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000"))));

        View v = service.updateValue(MF, h.id(), new ValueRequest(n("6500"), n("5500"), null));

        assertAmount("10000", v.totalInvested());
        assertAmount("5500", only(v).investedAmount());
        assertAmount("6500", only(v).currentValue());
        assertAmount("1000", only(v).gain());
        assertAmount("5500", balanceOf(set.accountId()));
        assertThrows(ValidationException.class,
                () -> service.updateValue(MF, h.id(), new ValueRequest(n("1"), n("-5"), null)));
    }

    @Test
    void portfoliosKeepTheirOwnTotals() {
        service.setUp(MF);
        service.setUp(PortfolioKind.SHARES);

        service.invest(MF, new InvestRequest(bank.getId(), n("1000")));

        assertAmount("0", service.get(PortfolioKind.SHARES).totalInvested());
    }

    @Test
    void moneyOnlyMovesToBankOrCash() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("100"), n("100"))));
        Account gold = accountService.create(new AccountData("Gold", AccountType.GOLD, null, null, n("500"), null, null));

        assertThrows(ValidationException.class, () -> service.withdraw(MF, h.id(), new WithdrawRequest(gold.getId(), n("10"))));
        assertThrows(ValidationException.class, () -> service.withdraw(MF, h.id(), new WithdrawRequest(null, n("10"))));
    }

    // ---- dividends

    private Long dividendCategory() {
        Category parent = categoryRepository.findAll().stream()
                .filter(c -> c.getParentId() == null && c.getName().equals("Investment Income")).findFirst().orElse(null);
        if (parent == null) {
            parent = new Category();
            parent.setName("Investment Income");
            parent.setKind(CategoryKind.INCOME);
            parent = categoryRepository.save(parent);
        }
        Long parentId = parent.getId();
        return categoryRepository.findAll().stream()
                .filter(c -> parentId.equals(c.getParentId()) && c.getName().equals("Dividends")).findFirst()
                .orElseGet(() -> {
                    Category dividends = new Category();
                    dividends.setName("Dividends");
                    dividends.setKind(CategoryKind.INCOME);
                    dividends.setParentId(parentId);
                    return categoryRepository.save(dividends);
                }).getId();
    }

    @Test
    void aDividendIsIncomeIntoTheAccountLinkedToTheShare() {
        Long dividendsId = dividendCategory();
        View set = service.setUp(PortfolioKind.SHARES);
        HoldingView h = only(service.addHolding(PortfolioKind.SHARES, new HoldingData("Infosys", n("10000"), n("12000"))));

        View v = service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), n("450"), null));

        assertAmount("100450", balanceOf(bank.getId()));                 // the cash arrived
        assertAmount("10000", only(v).investedAmount());                 // the holding did not change
        assertAmount("12000", only(v).currentValue());
        assertAmount("10000", v.totalInvested());
        assertAmount("10000", balanceOf(set.accountId()));
        var t = transactionRepository.findAll().get(0);
        assertEquals(TransactionKind.INCOME, t.getKind());
        assertEquals(bank.getId(), t.getToAccountId());
        assertNull(t.getFromAccountId());
        assertEquals(h.id(), t.getHoldingId());
        assertEquals(dividendsId, t.getCategoryId());
        assertEquals(LocalDate.now(), t.getDate());
        assertTrue(t.getNote().contains("Infosys"));
    }

    @Test
    void aDividendCanBeDatedInThePast() {
        service.setUp(PortfolioKind.SHARES);
        HoldingView h = only(service.addHolding(PortfolioKind.SHARES, new HoldingData("ITC", n("100"), n("100"))));

        service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), n("5"), LocalDate.now().minusDays(40)));

        assertEquals(LocalDate.now().minusDays(40), transactionRepository.findAll().get(0).getDate());
    }

    @Test
    void aDividendWithoutTheDividendsSubCategoryIsFiledUnderInvestmentIncome() {
        Long id = dividendCategory();
        Category removed = categoryRepository.findById(id).orElseThrow();
        String name = removed.getName();
        Long parentId = removed.getParentId();
        categoryRepository.delete(removed);
        try {
            service.setUp(PortfolioKind.SHARES);
            HoldingView h = only(service.addHolding(PortfolioKind.SHARES, new HoldingData("ITC", n("100"), n("100"))));

            service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), n("5"), null));

            assertEquals(parentId, transactionRepository.findAll().get(0).getCategoryId(), "the starter list has no sub categories");
        } finally {
            Category back = new Category();          // leave the categories as other tests expect them
            back.setName(name);
            back.setKind(CategoryKind.INCOME);
            back.setParentId(parentId);
            categoryRepository.save(back);
        }
    }

    @Test
    void badDividendsAreRefused() {
        service.setUp(PortfolioKind.SHARES);
        HoldingView h = only(service.addHolding(PortfolioKind.SHARES, new HoldingData("ITC", n("100"), n("100"))));
        Account gold = accountService.create(new AccountData("Gold", AccountType.GOLD, null, null, n("500"), null, null));

        assertThrows(ValidationException.class, () -> service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), n("0"), null)));
        assertThrows(ValidationException.class, () -> service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), null, null)));
        assertThrows(ValidationException.class, () -> service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(null, n("5"), null)));
        assertThrows(ValidationException.class, () -> service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(gold.getId(), n("5"), null)));
        assertThrows(ValidationException.class, () -> service.dividend(PortfolioKind.SHARES, h.id(), new DividendRequest(bank.getId(), n("5"), LocalDate.now().plusDays(1))));
        assertThrows(RuntimeException.class, () -> service.dividend(MF, h.id(), new DividendRequest(bank.getId(), n("5"), null)));
        assertEquals(0, transactionRepository.count());
    }

    // ---- stats

    private ValueRequest on(String value, String invested, int daysAgo) {
        return new ValueRequest(n(value), invested == null ? null : n(invested), LocalDate.now().minusDays(daysAgo));
    }

    @Test
    void noStatsBeforeSetUp() {
        Stats s = service.stats(MF);

        assertTrue(s.overall().isEmpty());
        assertTrue(s.holdings().isEmpty());
    }

    @Test
    void aNewFundHasOnePointFromToday() {
        service.setUp(MF);
        service.addHolding(MF, new HoldingData("A", n("1000"), n("1200")));

        Stats s = service.stats(MF);

        assertEquals(1, s.holdings().get(0).points().size());
        StatPoint p = s.holdings().get(0).points().get(0);
        assertEquals(LocalDate.now(), p.date());
        assertAmount("1000", p.investedAmount());
        assertAmount("1200", p.currentValue());
        assertEquals(1, s.overall().size());
    }

    @Test
    void aSecondChangeOnTheSameDayReplacesTheFirst() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("A", n("1000"), n("1200"))));

        service.updateValue(MF, h.id(), new ValueRequest(n("1300"), null, null));

        List<StatPoint> points = service.stats(MF).holdings().get(0).points();
        assertEquals(1, points.size());
        assertAmount("1300", points.get(0).currentValue());
    }

    @Test
    void datedUpdatesBuildAHistoryThatStartsAtTheFirstOne() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("A", n("1000"), n("1000"))));
        service.updateValue(MF, h.id(), on("1100", null, 60));
        service.updateValue(MF, h.id(), on("1250", null, 30));
        service.updateValue(MF, h.id(), new ValueRequest(n("1400"), null, null));

        List<StatPoint> points = service.stats(MF).holdings().get(0).points();

        assertEquals(3, points.size());                                             // today's replaced the first record
        assertEquals(LocalDate.now().minusDays(60), points.get(0).date());         // nothing is invented before this
        assertAmount("1100", points.get(0).currentValue());
        assertAmount("1250", points.get(1).currentValue());
        assertAmount("1400", points.get(2).currentValue());
    }

    @Test
    void theOverallLineAddsUpWhatIsKnownOnEachDay() {
        service.setUp(MF);
        HoldingView a = only(service.addHolding(MF, new HoldingData("A", n("1000"), n("1000"))));
        service.updateValue(MF, a.id(), on("900", "900", 40));
        service.updateValue(MF, a.id(), on("1200", "1000", 10));
        service.updateValue(MF, a.id(), new ValueRequest(n("1200"), n("1000"), null));   // dated updates end with today's
        View two = service.addHolding(MF, new HoldingData("B", n("500"), n("600")));   // B only exists from today
        assertEquals(2, two.holdings().size());

        List<StatPoint> overall = service.stats(MF).overall();

        assertEquals(3, overall.size());
        assertEquals(LocalDate.now().minusDays(40), overall.get(0).date());
        assertAmount("900", overall.get(0).currentValue());          // only A existed
        assertAmount("1200", overall.get(1).currentValue());
        assertAmount("1800", overall.get(2).currentValue());         // today: A 1,200 (from 10 days ago) + B 600
        assertAmount("1500", overall.get(2).investedAmount());
    }

    @Test
    void aFutureDateIsRejected() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("A", n("1"), n("1"))));

        assertThrows(ValidationException.class, () -> service.updateValue(MF, h.id(),
                new ValueRequest(n("5"), null, LocalDate.now().plusDays(1))));
    }

    @Test
    void aRemovedFundStopsCountingFromToday() {
        service.setUp(MF);
        HoldingView a = only(service.addHolding(MF, new HoldingData("A", n("1000"), n("1000"))));
        service.updateValue(MF, a.id(), on("1000", null, 20));
        service.withdraw(MF, a.id(), new WithdrawRequest(bank.getId(), n("1000")));
        service.deleteHolding(MF, a.id());

        Stats s = service.stats(MF);

        assertTrue(s.holdings().isEmpty());
        assertAmount("1000", s.overall().get(0).currentValue());
        assertAmount("0", s.overall().get(s.overall().size() - 1).currentValue());
    }

    @Test
    void startUpGivesOlderHoldingsAFirstPoint() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("A", n("1000"), n("1200"))));
        snapshotRepository.deleteAll();                              // as if it was added before stats existed

        service.recordMissingSnapshots();

        assertEquals(1, service.stats(MF).holdings().get(0).points().size());
        assertEquals(h.id(), service.stats(MF).holdings().get(0).id());
    }

    @Test
    void withdrawingTakesTheSameShareOffTheInvestedAmount() {
        View set = service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("20000"))));

        HoldingView after = only(service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("5000"))));

        assertAmount("15000", after.currentValue());        // a quarter of the value came out
        assertAmount("7500", after.investedAmount());       // so a quarter of what was invested
        assertAmount("100", after.gainPercent());           // the gain percentage did not change
        assertAmount("105000", balanceOf(bank.getId()));
        assertAmount("7500", balanceOf(set.accountId()));         // the account counts what is still invested
    }

    @Test
    void withdrawingEverythingLeavesNothingInvested() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("13333"))));

        HoldingView after = only(service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("13333"))));

        assertAmount("0", after.currentValue());
        assertAmount("0", after.investedAmount());
    }

    @Test
    void cannotWithdrawMoreThanItIsWorth() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("100"), n("100"))));

        assertThrows(ValidationException.class,
                () -> service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("101"))));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void aFundCanOnlyBeRemovedOnceItIsWorthNothing() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("100"), n("100"))));

        assertThrows(ValidationException.class, () -> service.deleteHolding(MF, h.id()));

        service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("100")));
        assertTrue(service.deleteHolding(MF, h.id()).holdings().isEmpty());
    }

    @Test
    void anotherKindsHoldingCannotBeTouched() {
        service.setUp(MF);
        service.setUp(PortfolioKind.SHARES);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("100"), n("100"))));

        assertThrows(RuntimeException.class,
                () -> service.updateValue(PortfolioKind.SHARES, h.id(), new ValueRequest(n("5"), null, null)));
    }

    @Test
    void theAccountBalanceIsTheSumOfInvestedAmountsSoGainsDoNotRaiseNetWorth() {
        View set = service.setUp(MF);
        service.addHolding(MF, new HoldingData("A", n("10000"), n("13000")));
        service.addHolding(MF, new HoldingData("B", n("5000"), n("4000")));

        assertAmount("15000", balanceOf(set.accountId()));   // 10,000 + 5,000 invested, whatever they are worth today
        Point now = netWorthService.history(1).get(0);
        assertAmount("115000", now.assets());               // 100,000 bank + 15,000 invested in funds
    }

    @Test
    void ordinaryTransactionsCannotTouchThePortfolioAccount() {
        View set = service.setUp(MF);

        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(
                LocalDate.now(), TransactionKind.TRANSFER, bank.getId(), set.accountId(), n("10"), null, null,
                null, null, null)));
    }

    @Test
    void netWorthHistoryCountsWhatWasInvestedBothInThePastAndToday() {
        View set = service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("A", n("10000"), n("15000"))));

        List<Point> points = netWorthService.history(2);

        assertAmount("110000", points.get(0).assets());      // last month: 100,000 + 10,000 invested
        assertAmount("110000", points.get(1).assets());      // today: the same, the 15,000 value is not counted
        assertEquals(set.accountId(), accountRepository.findAll().stream()
                .filter(a -> a.getType() == AccountType.INVESTMENT).findFirst().orElseThrow().getId());
        assertEquals("A", h.name());
    }

    @Test
    void deletingTheEmptyAccountRemovesItsHoldingsAndMarker() {
        View set = service.setUp(MF);
        service.addHolding(MF, new HoldingData("A", n("0"), n("0")));

        accountService.delete(set.accountId());

        assertFalse(service.get(MF).configured());
        assertEquals(0, holdingRepository.count());
    }

    @Test
    void anInvestmentAndAWithdrawalCanBeDatedInThePast() {
        service.setUp(MF);
        LocalDate first = LocalDate.now().minusDays(30);
        LocalDate second = LocalDate.now().minusDays(10);

        service.invest(MF, new InvestRequest(bank.getId(), n("5000"), null, "Mid Cap Fund", first));
        Long fund = service.get(MF).holdings().get(0).id();
        service.invest(MF, new InvestRequest(bank.getId(), n("3000"), fund, null, second));
        service.withdraw(MF, fund, new WithdrawRequest(bank.getId(), n("1000"), second));

        List<Transaction> moved = transactionRepository.findAll().stream().sorted(Comparator.comparing(Transaction::getDate).thenComparing(Transaction::getId)).toList();
        assertEquals(List.of(first, second, second), moved.stream().map(Transaction::getDate).toList(), "the transfers carry the days of the request");
        assertAmount("93000", accountService.listSummaries().stream().filter(a -> a.id().equals(bank.getId())).findFirst().orElseThrow().balance());
        assertTrue(snapshotRepository.findAll().stream().anyMatch(sn -> sn.getDate().equals(first)), "the value history starts on the first day");
    }

    @Test
    void anInvestmentCannotBeDatedInTheFuture() {
        service.setUp(MF);

        assertThrows(ValidationException.class, () -> service.invest(MF,
                new InvestRequest(bank.getId(), n("5000"), null, "Mid Cap Fund", LocalDate.now().plusDays(1))));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void aSaleWithTheInvestedAmountAndWhatItFetchedBooksTheDifferenceAsProfit() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("30000"), n("36000"))));

        // sell 10000 of what was invested, which is now worth 14000: 4000 profit
        View v = service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("14000"), null, n("10000")));

        assertAmount("20000", only(v).investedAmount());      // 30000 less the 10000 sold at cost
        assertAmount("22000", only(v).currentValue());         // 36000 less the 14000 it fetched
        assertAmount("20000", v.totalInvested());
        assertAmount("4000", v.realizedGain());
        assertAmount("114000", balanceOf(bank.getId()));
    }

    @Test
    void aSaleThatFetchedMoreThanTheValueOnFileBringsTheValueUpToWhatItFetched() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("60000"), n("60000"))));   // never updated

        View v = service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("63756.55"), null, n("60000")));

        assertAmount("0", only(v).investedAmount());
        assertAmount("0", only(v).currentValue());
        assertAmount("0", v.totalInvested());
        assertAmount("3756.55", v.realizedGain());
    }

    @Test
    void aSaleAtALossBooksTheLoss() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("8000"))));

        View v = service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("4000"), null, n("5000")));

        assertAmount("-1000", v.realizedGain());
        assertAmount("5000", only(v).investedAmount());
        assertAmount("4000", only(v).currentValue());
    }

    @Test
    void theInvestedAmountSoldCannotBeMoreThanWhatIsInvestedOrNegative() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("12000"))));

        assertThrows(ValidationException.class, () -> service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("5000"), null, n("10000.01"))));
        assertThrows(ValidationException.class, () -> service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("5000"), null, n("-1"))));
        assertEquals(0, transactionRepository.count());
        assertAmount("10000", service.get(MF).holdings().get(0).investedAmount());
    }

    @Test
    void leavingTheInvestedAmountOutKeepsTheOldRuleThatItComesOffInProportion() {
        service.setUp(MF);
        HoldingView h = only(service.addHolding(MF, new HoldingData("Index fund", n("10000"), n("20000"))));

        View v = service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("5000")));

        assertAmount("7500", only(v).investedAmount());
        assertAmount("2500", v.realizedGain());
        assertThrows(ValidationException.class, () -> service.withdraw(MF, h.id(), new WithdrawRequest(bank.getId(), n("15001"))), "more than it is worth");
    }
}
