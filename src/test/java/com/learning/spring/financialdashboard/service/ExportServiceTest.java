package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
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

@SpringBootTest
class ExportServiceTest {

    @Autowired
    ExportService export;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CategoryRepository categories;

    @BeforeEach
    @AfterEach
    void clean() {
        transactions.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void everyAreaGetsASheetAndTheCategoryPiesAreDrawn() throws IOException {
        Account bank = accountService.create(
                new AccountData("Export bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        Category pay = categoryService.create(new CategoryData("Export pay", CategoryKind.INCOME, null));
        Category food = categoryService.create(new CategoryData("Export food", CategoryKind.EXPENSE, null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.INCOME, null, bank.getId(),
                new BigDecimal("500"), null, pay.getId(), null, "salary", null));
        transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, bank.getId(), null,
                new BigDecimal("120"), null, food.getId(), null, "groceries", null));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.build()))) {
            for (String name : new String[]{"Accounts", "Transactions", "Income & Expenses", "Monthly", "Net worth",
                    "Ledger", "Emergency Fund", "Mutual Funds", "Shares", "Loans", "Categories", "Holding history"}) {
                assertNotNull(wb.getSheet(name), "missing sheet " + name);
            }
            XSSFSheet txns = wb.getSheet("Transactions");
            assertEquals(2, txns.getLastRowNum());   // header + 2 transactions
            assertEquals(2, wb.getSheet("Income & Expenses").getDrawingPatriarch().getCharts().size());
            assertEquals(1, wb.getSheet("Monthly").getDrawingPatriarch().getCharts().size());
        } finally {
            transactions.deleteAll();
            categories.delete(pay);
            categories.delete(food);
        }
    }
}
