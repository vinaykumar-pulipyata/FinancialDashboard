package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.ExchangeRate;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The rupee value of one unit of each foreign currency, entered by hand (there are no live prices). The rate used for a day is the
 * latest one on or before it; a day before the first rate uses the first rate, so an account is never left without one.
 */
@Service
@Transactional
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000");

    private final ExchangeRateRepository rates;
    private final AccountRepository accounts;

    public ExchangeRateService(ExchangeRateRepository rates, AccountRepository accounts) {
        this.rates = rates;
        this.accounts = accounts;
    }

    /** Every rate, by currency and then newest day first. */
    @Transactional(readOnly = true)
    public List<ExchangeRate> list() {
        return rates.findAllByOrderByCurrencyAscDateDesc();
    }

    /** Sets the rate of a currency on a day, replacing the one that day had. */
    public ExchangeRate save(Currency currency, LocalDate date, BigDecimal rate) {
        if (currency == null || currency.isBase()) {
            throw new ValidationException("Choose a currency other than INR: rupees need no rate");
        }
        if (date == null) {
            throw new ValidationException("Date is required");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new ValidationException("A rate cannot be dated in the future");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new ValidationException("The rate must be more than zero");
        }
        if (rate.stripTrailingZeros().scale() > Currency.RATE_SCALE) {
            throw new ValidationException("A rate can have at most " + Currency.RATE_SCALE + " decimals");
        }
        if (rate.compareTo(MAX_RATE) > 0) {
            throw new ValidationException("That rate is too large");
        }
        ExchangeRate row = rates.findByCurrencyAndDate(currency, date).orElseGet(ExchangeRate::new);
        row.setCurrency(currency);
        row.setDate(date);
        row.setRate(rate.setScale(Currency.RATE_SCALE, java.math.RoundingMode.UNNECESSARY));
        ExchangeRate saved = rates.save(row);
        log.info("Exchange rate saved: id={} currency={} date={}", saved.getId(), currency, date);
        return saved;
    }

    /** Deletes a rate, but not the last one of a currency an account is held in (its balance could no longer be shown in rupees). */
    public void delete(Long id) {
        ExchangeRate row = rates.findById(id).orElseThrow(() -> new NotFoundException("Exchange rate " + id + " not found"));
        if (rates.countByCurrency(row.getCurrency()) <= 1 && accounts.existsByCurrency(row.getCurrency())) {
            throw new ValidationException("An account is held in " + row.getCurrency() + ", so its last rate cannot be deleted");
        }
        rates.delete(row);
        log.info("Exchange rate deleted: id={} currency={}", id, row.getCurrency());
    }

    /** Refuses a currency that has no rate yet: an account in it could not be counted in the totals. */
    @Transactional(readOnly = true)
    public void requireRate(Currency currency) {
        if (!currency.isBase() && rates.countByCurrency(currency) == 0) {
            throw new ValidationException("Add an exchange rate for " + currency + " first (Menu > Exchange rates)");
        }
    }

    /** All the rates in memory, for a loop over many accounts or months. Load it once per request. */
    @Transactional(readOnly = true)
    public Table table() {
        Map<Currency, List<ExchangeRate>> byCurrency = new EnumMap<>(Currency.class);
        for (Currency c : Currency.values()) {
            if (!c.isBase()) {
                byCurrency.put(c, rates.findAllByCurrencyOrderByDateAsc(c));
            }
        }
        return new Table(byCurrency);
    }

    public static final class Table {
        private final Map<Currency, List<ExchangeRate>> byCurrency;

        Table(Map<Currency, List<ExchangeRate>> byCurrency) {
            this.byCurrency = byCurrency;
        }

        /** Rupees per 1 unit on {@code day}: 1 for INR, else the latest rate on or before it, else the earliest. */
        public BigDecimal on(Currency currency, LocalDate day) {
            if (currency.isBase()) {
                return BigDecimal.ONE;
            }
            List<ExchangeRate> list = byCurrency.getOrDefault(currency, new ArrayList<>());
            if (list.isEmpty()) {
                throw new ValidationException("There is no exchange rate for " + currency);
            }
            ExchangeRate found = list.get(0);
            for (ExchangeRate r : list) {
                if (r.getDate().isAfter(day)) {
                    break;
                }
                found = r;
            }
            return found.getRate();
        }

        /** {@code amount} in {@code currency} as rupees on {@code day}. */
        public BigDecimal toRupees(BigDecimal amount, Currency currency, LocalDate day) {
            return currency.isBase() ? amount : Currency.toRupees(amount, on(currency, day));
        }
    }
}
