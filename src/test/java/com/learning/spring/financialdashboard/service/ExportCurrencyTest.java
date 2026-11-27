package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.ExchangeRateRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Excel export of accounts held in a foreign currency: their currency, their value in rupees and the rates used. */
@SpringBootTest
class ExportCurrencyTest {

    @Autowired
    ExportService export;
    @Autowired
    AccountService accountService;
    @Autowired
    ExchangeRateService rates;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;
    @Autowired
    ExchangeRateRepository rateRepository;
    @Autowired
    TransactionService transactionService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    CategoryRepository categoryRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        transactions.deleteAll();
        categoryRepository.deleteAll();
        accounts.deleteAll();
        rateRepository.deleteAll();
    }

    private static Row rowNamed(XSSFSheet sheet, String name) {
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            if (name.equals(sheet.getRow(r).getCell(0).getStringCellValue())) {
                return sheet.getRow(r);
            }
        }
        throw new AssertionError("no row " + name);
    }

    @Test
    void theAccountsSheetShowsEachAccountsCurrencyAndItsValueInRupees() throws IOException {
        rates.save(Currency.USD, LocalDate.now().minusDays(1), new BigDecimal("85.5"));
        rates.save(Currency.VND, LocalDate.now().minusDays(1), new BigDecimal("0.0033"));
        accountService.create(new AccountData("Rupee bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        accountService.create(new AccountData("US bank", AccountType.BANK, null, null, new BigDecimal("100"), null, null, "USD"));
        accountService.create(new AccountData("Hanoi cash", AccountType.CASH, null, null, new BigDecimal("5000000"), null, null, "VND"));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.build()))) {
            XSSFSheet sheet = wb.getSheet("Accounts");
            assertEquals("Currency", sheet.getRow(0).getCell(17).getStringCellValue());
            assertEquals("Balance in ₹ (counted in the totals)", sheet.getRow(0).getCell(18).getStringCellValue());

            Row rupee = rowNamed(sheet, "Rupee bank");
            assertEquals("INR", rupee.getCell(17).getStringCellValue());
            assertEquals(1000.0, rupee.getCell(18).getNumericCellValue());

            Row us = rowNamed(sheet, "US bank");
            assertEquals(100.0, us.getCell(5).getNumericCellValue(), "the balance stays in the account's own currency");
            assertEquals("USD", us.getCell(17).getStringCellValue());
            assertEquals(8550.0, us.getCell(18).getNumericCellValue());

            Row dong = rowNamed(sheet, "Hanoi cash");
            assertEquals("VND", dong.getCell(17).getStringCellValue());
            assertEquals(16500.0, dong.getCell(18).getNumericCellValue());
        }
    }

    @Test
    void theRatesAreOnTheirOwnSheetOnlyWhenThereAreSome() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.build()))) {
            assertNull(wb.getSheet("Exchange rates"), "no rates, no sheet");
        }

        rates.save(Currency.VND, LocalDate.of(2026, 11, 1), new BigDecimal("0.0033"));
        rates.save(Currency.USD, LocalDate.of(2026, 11, 2), new BigDecimal("85.5"));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.build()))) {
            XSSFSheet sheet = wb.getSheet("Exchange rates");
            assertNotNull(sheet);
            assertEquals(3, sheet.getLastRowNum() + 1, "a header and two rates");
            Row usd = rowNamed(sheet, "USD");
            assertEquals(85.5, usd.getCell(2).getNumericCellValue());
            Row vnd = rowNamed(sheet, "VND");
            assertEquals(0.0033, vnd.getCell(2).getNumericCellValue(), 1e-12, "a small rate keeps its digits");
        }
    }

    @Test
    void foreignTransactionsNameTheirCurrencyAndStayOutOfTheRupeeTotals() throws IOException {
        rates.save(Currency.USD, LocalDate.now().minusDays(1), new BigDecimal("80"));
        Account bank = accountService.create(new AccountData("Rupee bank", AccountType.BANK, null, null, new BigDecimal("10000"), null, null));
        Account us = accountService.create(new AccountData("US bank", AccountType.BANK, null, null, new BigDecimal("0"), null, null, "USD"));
        Category pay = categoryService.create(new CategoryData("Pay", CategoryKind.INCOME, null));
        LocalDate today = LocalDate.now();
        transactionService.create(new TransactionData(today, TransactionKind.INCOME, null, bank.getId(), new BigDecimal("500"), null, pay.getId(), null, "rupees", null));
        transactionService.create(new TransactionData(today, TransactionKind.INCOME, null, us.getId(), new BigDecimal("50"), null, pay.getId(), null, "dollars", null));
        transactionService.create(new TransactionData(today, TransactionKind.TRANSFER, bank.getId(), us.getId(), new BigDecimal("800"), null, null, null, "top-up", null,
                null, new BigDecimal("10")));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.build()))) {
            XSSFSheet sheet = wb.getSheet("Transactions");
            assertEquals("Currency", sheet.getRow(0).getCell(12).getStringCellValue());
            assertEquals("Amount received", sheet.getRow(0).getCell(13).getStringCellValue());
            assertEquals("Received in", sheet.getRow(0).getCell(14).getStringCellValue());
            int usdIncome = 0, transfers = 0;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if ("dollars".equals(row.getCell(10).getStringCellValue())) {
                    assertEquals("USD", row.getCell(12).getStringCellValue());
                    usdIncome++;
                }
                if ("top-up".equals(row.getCell(10).getStringCellValue())) {
                    assertEquals("INR", row.getCell(12).getStringCellValue(), "the amount left the rupee account");
                    assertEquals(10.0, row.getCell(13).getNumericCellValue());
                    assertEquals("USD", row.getCell(14).getStringCellValue());
                    transfers++;
                }
            }
            assertEquals(1, usdIncome);
            assertEquals(1, transfers);

            XSSFSheet monthly = wb.getSheet("Monthly");
            assertEquals(500.0, monthly.getRow(1).getCell(1).getNumericCellValue(), "the 50 dollars are not added to the rupee income");
        }
    }
}
