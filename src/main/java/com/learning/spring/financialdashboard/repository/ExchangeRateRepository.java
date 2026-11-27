package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    List<ExchangeRate> findAllByOrderByCurrencyAscDateDesc();

    List<ExchangeRate> findAllByCurrencyOrderByDateAsc(Currency currency);

    Optional<ExchangeRate> findByCurrencyAndDate(Currency currency, LocalDate date);

    long countByCurrency(Currency currency);
}
