package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.JewelleryItemRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.JewelleryService.ItemData;
import com.learning.spring.financialdashboard.service.JewelleryService.ItemView;
import com.learning.spring.financialdashboard.service.JewelleryService.SaleRequest;
import com.learning.spring.financialdashboard.service.JewelleryService.View;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
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
class JewelleryServiceTest {

    @Autowired
    JewelleryService service;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    JewelleryItemRepository itemRepository;

    @Autowired
    CategoryRepository categoryRepository;

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
        itemRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountService.listSummaries().stream().filter(a -> a.id().equals(accountId)).map(AccountSummary::balance).findFirst().orElseThrow();
    }

    private ItemData item(String metal, String name, String form, String grams, String price, Long paidFrom) {
        return new ItemData(metal, name, form, n(grams), n(price), paidFrom);
    }

    private ItemView find(View v, String name) {
        return v.metals().stream().flatMap(m -> m.items().stream()).filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void notSetUpUntilAskedAndSettingUpTwiceKeepsOneAccount() {
        assertFalse(service.get().configured());
        service.setUp();
        service.setUp();
        assertTrue(service.get().configured());
        assertEquals(1, accountRepository.findAll().stream().filter(a -> a.getType() == AccountType.JEWELLERY).count());
    }

    @Test
    void itemsAreGroupedByKindGoldFirstThenSilverThenOthersAndTheBalanceIsWhatTheyCost() {
        service.setUp();
        service.add(item("gold", "Wedding necklace", "ornament", "40.5", "250000", null));
        service.add(item("Gold", "1 g coin", "coin", "1", "7500.50", null));
        service.add(item("SILVER", "Pooja plate", "ornament", "200", "18000", null));
        View v = service.add(item("Platinum", "Ring", "other", "5", "30000", null));

        assertEquals(List.of("Gold", "Silver", "Platinum"), v.metals().stream().map(m -> m.metal()).toList());
        assertEquals(List.of("1 g coin", "Wedding necklace"), v.metals().get(0).items().stream().map(ItemView::name).toList(), "by name inside a kind");
        assertAmount("41.5", v.metals().get(0).weight());
        assertAmount("257500.50", v.metals().get(0).boughtPrice());
        assertAmount("305500.50", v.boughtPrice());
        assertAmount("305500.50", balanceOf(v.accountId()));
    }

    @Test
    void anItemPaidForFromTheBankMovesMoneyOneOwnedAlreadyDoesNot() {
        View v = service.setUp();
        service.add(item("Gold", "Owned", "bar", "10", "60000", null));
        assertEquals(0, transactionRepository.count());
        assertAmount("100000", balanceOf(bank.getId()));

        v = service.add(item("Gold", "New chain", "ornament", "8", "50000", bank.getId()));
        assertEquals(1, transactionRepository.count());
        assertAmount("50000", balanceOf(bank.getId()));
        assertAmount("110000", balanceOf(v.accountId()));
    }

    @Test
    void sellingAtAProfitMovesWhatItCostAndRecordsTheProfitAsIncome() {
        Category income = new Category();
        income.setName("Investment Income");
        income.setKind(CategoryKind.INCOME);
        categoryRepository.save(income);
        View v = service.setUp();
        v = service.add(item("Gold", "Coin", "coin", "10", "50000", null));
        Long id = find(v, "Coin").id();

        View after = service.sell(id, new SaleRequest(bank.getId(), n("70000"), null));

        assertTrue(after.metals().isEmpty(), "the whole item is gone");
        assertAmount("0", balanceOf(after.accountId()));
        assertAmount("170000", balanceOf(bank.getId()));       // 100,000 + the 50,000 cost + the 20,000 profit
        List<Transaction> all = transactionRepository.findAll();
        assertEquals(2, all.size());
        Transaction profit = all.stream().filter(t -> t.getKind() == TransactionKind.INCOME).findFirst().orElseThrow();
        assertAmount("20000", profit.getAmount());
        assertTrue(profit.getCategoryId() != null, "filed under Investment Income");
    }

    @Test
    void sellingAtALossRecordsTheLossAsAnExpense() {
        View v = service.setUp();
        v = service.add(item("Silver", "Bar", "bar", "500", "40000", null));
        service.sell(find(v, "Bar").id(), new SaleRequest(bank.getId(), n("35000"), null));
        assertAmount("135000", balanceOf(bank.getId()));       // 100,000 + the 40,000 cost - the 5,000 loss
        assertEquals(1, transactionRepository.findAll().stream().filter(t -> t.getKind() == TransactionKind.EXPENSE).count());
    }

    @Test
    void sellingPartOfTheWeightTakesItsShareOfWhatItCost() {
        View v = service.setUp();
        v = service.add(item("Gold", "Loose gold", "loose", "20", "100000", null));
        View after = service.sell(find(v, "Loose gold").id(), new SaleRequest(bank.getId(), n("30000"), n("5")));

        ItemView rest = find(after, "Loose gold");
        assertAmount("15", rest.weight());
        assertAmount("75000", rest.boughtPrice());
        assertAmount("75000", balanceOf(after.accountId()));
        assertThrows(ValidationException.class, () -> service.sell(rest.id(), new SaleRequest(bank.getId(), n("1"), n("15.001"))));
        assertThrows(ValidationException.class, () -> service.sell(rest.id(), new SaleRequest(bank.getId(), n("1"), n("0"))));
    }

    @Test
    void removingTakesTheItemOffWithNoMoneyMoving() {
        View v = service.setUp();
        v = service.add(item("Gold", "Gifted", "ornament", "10", "60000", null));
        View after = service.remove(find(v, "Gifted").id());
        assertTrue(after.metals().isEmpty());
        assertAmount("0", balanceOf(after.accountId()));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void correctingAPriceKeepsTheBalanceEqualToWhatTheItemsCost() {
        View v = service.setUp();
        v = service.add(item("Gold", "Chain", "ornament", "10", "60000", null));
        View after = service.update(find(v, "Chain").id(), item("Gold", "Chain", "ornament", "10", "62500.25", null));
        assertAmount("62500.25", balanceOf(after.accountId()));
    }

    @Test
    void badInputIsRefused() {
        service.setUp();
        assertThrows(ValidationException.class, () -> service.add(item("Gold", " ", "coin", "1", "1", null)));
        assertThrows(ValidationException.class, () -> service.add(item("", "X", "coin", "1", "1", null)));
        assertThrows(ValidationException.class, () -> service.add(item("Gold", "X", "brick", "1", "1", null)));
        assertThrows(ValidationException.class, () -> service.add(item("Gold", "X", "coin", "0", "1", null)));
        assertThrows(ValidationException.class, () -> service.add(item("Gold", "X", "coin", "1", "-1", null)));
        Account savingsGoal = accountService.create(new AccountData("Goal", AccountType.GOAL_FUND, null, null, n("0"), null, null));
        assertThrows(ValidationException.class, () -> service.add(item("Gold", "X", "coin", "1", "1", savingsGoal.getId())), "pay from a bank or cash account only");
    }

    @Test
    void ordinaryTransactionsOnTheJewelleryAccountAreRefused() {
        View v = service.setUp();
        assertThrows(ValidationException.class, () -> transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.TRANSFER,
                bank.getId(), v.accountId(), n("10"), null, null, null, null, null)));
    }

    @Test
    void anEmptyJewelleryAccountCanBeDeletedWithItsItems() {
        View v = service.setUp();
        service.add(item("Gold", "Coin", "coin", "1", "100", null));
        service.remove(find(service.get(), "Coin").id());
        accountService.delete(v.accountId());
        assertEquals(0, itemRepository.count());
    }

    @Test
    void removingOrRepricingAPieceBoughtFromTheBankChangesThatPaymentNotTheOpeningBalance() {
        View v = service.setUp();
        v = service.add(item("Gold", "New chain", "ornament", "8", "50000", bank.getId()));
        assertTrue(find(v, "New chain").paidByAccount());
        assertAmount("50000", balanceOf(bank.getId()));

        // A corrected price changes the transfer itself, so the account's opening balance (and past net worth) stay as they were.
        v = service.update(find(v, "New chain").id(), item("Gold", "New chain", "ornament", "8", "45000", null));
        assertAmount("55000", balanceOf(bank.getId()));
        assertAmount("45000", balanceOf(v.accountId()));
        assertAmount("0", accountService.get(v.accountId()).getOpeningBalance());

        // Removing it undoes the payment: the money is back in the bank and no transaction is left.
        v = service.remove(find(v, "New chain").id());
        assertAmount("100000", balanceOf(bank.getId()));
        assertAmount("0", balanceOf(v.accountId()));
        assertAmount("0", accountService.get(v.accountId()).getOpeningBalance());
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void aPieceBoughtFromTheBankThatWasPartlySoldStillUndoesItsPaymentWhenRemovedAndNeverTouchesTheOpeningBalance() {
        View v = service.setUp();
        v = service.add(item("Gold", "Bar", "bar", "10", "100000", bank.getId()));
        v = service.sell(find(v, "Bar").id(), new SaleRequest(bank.getId(), n("50000"), n("5")));
        assertTrue(find(v, "Bar").paidByAccount(), "the payment is still linked");
        v = service.update(find(v, "Bar").id(), item("Gold", "Bar", "bar", "5", "40000", null));
        assertAmount("0", accountService.get(v.accountId()).getOpeningBalance());
        assertAmount("40000", balanceOf(v.accountId()));
        v = service.remove(find(v, "Bar").id());
        assertAmount("0", balanceOf(v.accountId()));
        assertAmount("0", accountService.get(v.accountId()).getOpeningBalance());
        // the payment was cut back to what had already been sold (50000), so the bank is whole again
        assertAmount("100000", balanceOf(bank.getId()));
    }

    @Test
    void aSaleGainIsNeverFiledUnderADeactivatedOrDeletedCategory() {
        Category income = new Category();
        income.setName("Investment Income");
        income.setKind(CategoryKind.INCOME);
        income.setActive(false);
        income = categoryRepository.save(income);
        View v = service.setUp();
        v = service.add(item("Gold", "Ring", "ornament", "5", "20000", bank.getId()));
        service.sell(find(v, "Ring").id(), new SaleRequest(bank.getId(), n("25000"), null));
        Transaction gain = transactionRepository.findAll().stream().filter(t -> t.getKind() == TransactionKind.INCOME).findFirst().orElseThrow();
        assertNull(gain.getCategoryId());
        assertAmount("5000", gain.getAmount());
    }
}
