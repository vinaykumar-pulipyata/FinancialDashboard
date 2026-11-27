package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Holding;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Net worth at the end of each month, worked out by replaying the transactions on top of the opening balances with the
 * same rules as the balances on the dashboard: an asset is opening + money in - money out, a liability (what is owed)
 * flips the sign, and Receivables (money lent, not with the user yet) is left out altogether. The current month takes in
 * every recorded transaction (a future-dated one too, as the account balances do), and only active accounts count, as in the summary at the top of the page.
 *
 * <p>Only what is recorded as a transaction is replayed. A change in the value of an investment, gold or property
 * is not tracked, so it does not appear here. The one exception is the Mutual Funds and Shares accounts: today's point
 * uses what their holdings cost, the invested amounts (as the dashboard does), so selling or topping up a holding shows in the latest
 * month and a market gain or loss never does.
 */
@Service
@Transactional(readOnly = true)
public class NetWorthService {

    public record Point(String month, String label, BigDecimal netWorth, BigDecimal assets, BigDecimal liabilities) {
    }

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PortfolioDetailRepository portfolios;
    private final HoldingRepository holdings;
    private final ExchangeRateService exchangeRates;

    public NetWorthService(AccountRepository accounts, TransactionRepository transactions,
                           PortfolioDetailRepository portfolios,
                           HoldingRepository holdings, ExchangeRateService exchangeRates) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.portfolios = portfolios;
        this.holdings = holdings;
        this.exchangeRates = exchangeRates;
    }

    /** The last {@code months} months (1 to 60), oldest first, ending with the current month. */
    public List<Point> history(int months) {
        int count = Math.max(1, Math.min(months, 60));
        LocalDate today = LocalDate.now();
        List<Account> active = accounts.findAllByOrderByLevelAscNameAsc().stream().filter(Account::isActive).toList();
        List<Transaction> all = transactions.findAll();
        ExchangeRateService.Table rates = exchangeRates.table();
        Map<Long, BigDecimal> portfolioValues = new HashMap<>();
        portfolios.findAll().forEach(p -> portfolioValues.put(p.getAccountId(), BigDecimal.ZERO));
        for (Holding h : holdings.findAll()) {
            portfolioValues.computeIfPresent(h.getAccountId(), (id, sum) -> sum.add(h.getInvestedAmount()));
        }

        // One pass over the transactions in date order: the running money in and out of each account is carried from month to
        // month, so the work is (transactions + months x accounts) instead of months x accounts x transactions.
        List<Transaction> byDate = all.stream().sorted(Comparator.comparing(Transaction::getDate)).toList();
        Map<Long, BigDecimal> in = new HashMap<>();
        Map<Long, BigDecimal> out = new HashMap<>();
        int next = 0;
        List<Point> points = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (int back = count - 1; back >= 0; back--) {
            YearMonth month = current.minusMonths(back);
            // the latest point takes in everything recorded, even a transaction dated ahead, because the balances on the dashboard do
            LocalDate cutoff = back == 0 ? LocalDate.MAX : month.atEndOfMonth();
            while (next < byDate.size() && !byDate.get(next).getDate().isAfter(cutoff)) {
                Transaction t = byDate.get(next++);
                if (t.getToAccountId() != null) {
                    in.merge(t.getToAccountId(), t.receivedAmount(), BigDecimal::add);
                }
                if (t.getFromAccountId() != null) {
                    out.merge(t.getFromAccountId(), t.getAmount().add(t.getFee() == null ? BigDecimal.ZERO : t.getFee()), BigDecimal::add);
                }
            }
            // an account in another currency is counted at the rate of the end of the month (of today, for the current month)
            LocalDate rateDay = back == 0 ? today : month.atEndOfMonth();
            points.add(pointAt(month, active, in, out, back == 0 ? portfolioValues : Map.of(), rates, rateDay));
        }
        return points;
    }

    private Point pointAt(YearMonth month, List<Account> active, Map<Long, BigDecimal> in, Map<Long, BigDecimal> out,
                          Map<Long, BigDecimal> portfolioValues, ExchangeRateService.Table rates, LocalDate rateDay) {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        for (Account account : active) {
            if (account.getType() == AccountType.RECEIVABLE) {
                continue;   // money lent is yours but not with you yet: it is not part of net worth
            }
            BigDecimal moneyIn = in.getOrDefault(account.getId(), BigDecimal.ZERO);
            BigDecimal moneyOut = out.getOrDefault(account.getId(), BigDecimal.ZERO);
            boolean liability = account.getType().isLiability();
            BigDecimal balance = account.getOpeningBalance().add(liability ? moneyOut.subtract(moneyIn) : moneyIn.subtract(moneyOut));
            if (portfolioValues.containsKey(account.getId())) {
                balance = portfolioValues.get(account.getId());
            }
            balance = rates.toRupees(balance, account.getCurrency(), rateDay);
            if (liability) {
                liabilities = liabilities.add(balance);
            } else {
                assets = assets.add(balance);
            }
        }
        String label = month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + month.getYear();
        return new Point(month.toString(), label, assets.subtract(liabilities), assets, liabilities);
    }
}
