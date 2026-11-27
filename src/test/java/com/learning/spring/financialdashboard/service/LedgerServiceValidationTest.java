package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.EntryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.LedgerService.BucketData;
import com.learning.spring.financialdashboard.service.LedgerService.BucketView;
import com.learning.spring.financialdashboard.service.LedgerService.CloseRequest;
import com.learning.spring.financialdashboard.service.LedgerService.FundRequest;
import com.learning.spring.financialdashboard.service.LedgerService.InterestRequest;
import com.learning.spring.financialdashboard.service.LedgerService.PayRequest;
import com.learning.spring.financialdashboard.service.LedgerService.TransferRequest;
import com.learning.spring.financialdashboard.service.LedgerService.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Ledger's refusals: bucket details, funding, deleting a funding, paying, moving and closing. */
@SpringBootTest
class LedgerServiceValidationTest {

    @Autowired
    LedgerService service;
    @Autowired
    AccountService accountService;
    @Autowired
    CategoryService categoryService;
    @Autowired
    AccountRepository accounts;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    BucketRepository buckets;
    @Autowired
    BucketEntryRepository entries;
    @Autowired
    LedgerDetailRepository ledgers;
    @Autowired
    CategoryRepository categories;

    Account bank;

    private void wipe() {
        transactions.deleteAll();
        entries.deleteAll();
        buckets.deleteAll();
        ledgers.deleteAll();
        accounts.deleteAll();
        categories.deleteAll();
    }

    @BeforeEach
    void setUp() {
        wipe();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
        service.setUp();
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private BucketData bucket(String name, String monthly, String payment, Integer every, LocalDate next, LocalDate previous, LocalDate end, String held) {
        return new BucketData(name, monthly == null ? null : n(monthly), payment == null ? null : n(payment), every, next, previous, end,
                held == null ? null : n(held));
    }

    private BucketData simple(String name) {
        return bucket(name, "100", null, null, null, null, null, null);
    }

    private Long bucketId(String name) {
        return service.get().buckets().stream().filter(b -> b.name().equals(name)).findFirst().map(BucketView::id).orElseThrow();
    }

    private void refusesBucket(BucketData data) {
        assertThrows(ValidationException.class, () -> service.addBucket(data));
    }

    @Test
    void settingUpTwiceKeepsTheLedgerButAnAccountWithTheNameBlocksIt() {
        View again = service.setUp();   // already set up: nothing changes
        assertEquals(service.get().accountId(), again.accountId());

        ledgers.deleteAll();
        // The account is still there but no longer marked as the Ledger, so a new one cannot take its name.
        assertThrows(ValidationException.class, () -> service.setUp());
    }

    @Test
    void aBucketNeedsAValidNameAmountsAndDates() {
        LocalDate next = LocalDate.now().plusDays(10);
        refusesBucket(bucket(" ", "100", null, null, null, null, null, null));
        refusesBucket(bucket(null, "100", null, null, null, null, null, null));
        service.addBucket(simple("Rent"));
        refusesBucket(simple("rent"));                                                                   // duplicate
        refusesBucket(bucket("Neg", "-1", null, null, null, null, null, null));
        refusesBucket(bucket("Pay", "100", "0", 1, next, null, null, null));
        refusesBucket(bucket("Held", "100", null, null, null, null, null, "-5"));
        refusesBucket(bucket("Prev", "100", null, null, null, LocalDate.now().plusDays(1), null, null)); // future "previous"
        refusesBucket(bucket("Freq0", "100", "50", 0, next, null, null, null));
        refusesBucket(bucket("Freq121", "100", "50", 121, next, null, null, null));
        refusesBucket(bucket("NoDate", "100", "50", 3, null, null, null, null));                         // months without a date
        refusesBucket(bucket("NoMonths", "100", "50", null, next, null, null, null));                    // date without months
        refusesBucket(bucket("Ended", "100", null, null, null, null, LocalDate.now().minusDays(1), null));
    }

    @Test
    void aMissingMonthlyAllocationCountsAsZeroAndMoneyHeldNowIsRecorded() {
        View v = service.addBucket(bucket("Gift fund", null, null, null, null, null, null, "250"));
        BucketView gift = v.buckets().stream().filter(b -> b.name().equals("Gift fund")).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(gift.monthlyAllocation()));
        assertEquals(0, n("250").compareTo(gift.balance()));
        assertTrue(entries.findAll().stream().anyMatch(e -> e.getKind() == EntryKind.OPENING));
    }

    @Test
    void theBufferHasNoBillDetailsAndAnUnchangedEndDateIsAccepted() {
        Long buffer = bucketId("Buffer");
        assertThrows(ValidationException.class, () -> service.updateBucket(buffer,
                bucket("Buffer", "0", "100", 1, LocalDate.now().plusDays(5), null, null, null)));

        LocalDate end = LocalDate.now().plusMonths(6);
        service.addBucket(bucket("Course", "100", null, null, null, null, end, null));
        Long course = bucketId("Course");
        service.updateBucket(course, bucket("Course", "150", null, null, null, null, end, null));   // end date unchanged
        assertEquals(0, n("150").compareTo(service.get().buckets().stream().filter(b -> b.id().equals(course)).findFirst().orElseThrow().monthlyAllocation()));
    }

    @Test
    void fundingNeedsAnAmountAndAnAccountTheMoneyCanComeFrom() {
        service.addBucket(simple("Rent"));
        Account receivable = accountService.create(new AccountData(null, AccountType.RECEIVABLE, null, null, n("100"), null, null));
        Account dormant = accountService.create(new AccountData("Dormant", AccountType.BANK, null, null, n("100"), null, null));
        accountService.deactivate(dormant.getId());

        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), null, null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), BigDecimal.ZERO, null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(null, n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(987654L, n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(receivable.getId(), n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(dormant.getId(), n("100"), null)));
    }

    @Test
    void whenFundingIsLessThanThePlanTheSplitMustBeValid() {
        service.addBucket(simple("Rent"));
        service.addBucket(simple("Wifi"));
        service.updateBucket(bucketId("Buffer"), bucket("Buffer", "100", null, null, null, null, null, null));   // plan is 300
        Long rent = bucketId("Rent");

        Map<Long, BigDecimal> unknown = new HashMap<>();
        unknown.put(987654L, n("50"));
        Map<Long, BigDecimal> negative = new HashMap<>();
        negative.put(rent, n("-10"));
        Map<Long, BigDecimal> blankShare = new HashMap<>();
        blankShare.put(rent, null);

        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("100"), Map.of())));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("100"), unknown)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("100"), negative)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("100"), blankShare)));   // adds up to 0, not 100
        service.fund(new FundRequest(bank.getId(), n("100"), Map.of(rent, n("100"))));
    }

    @Test
    void onlyAFundingCanBeDeletedAsAFunding() {
        service.addBucket(simple("Rent"));
        service.updateBucket(bucketId("Buffer"), bucket("Buffer", "0", null, null, null, null, null, null));
        service.fund(new FundRequest(bank.getId(), n("100"), null));
        service.pay(new PayRequest(bucketId("Rent"), n("40")));
        Transaction payment = transactions.findAll().stream().filter(t -> t.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow();
        Transaction unrelated = transactions.save(copyOf(payment, bank.getId()));

        assertThrows(ValidationException.class, () -> service.deleteFunding(payment.getId()));    // a payment, not a funding
        assertThrows(ValidationException.class, () -> service.deleteFunding(unrelated.getId()));  // not the Ledger's
        assertThrows(NotFoundException.class, () -> service.deleteFunding(987654L));
        assertThrows(NotFoundException.class, () -> service.deleteFunding(null));
    }

    private Transaction copyOf(Transaction source, Long fromAccountId) {
        Transaction t = new Transaction();
        t.setDate(source.getDate());
        t.setKind(TransactionKind.EXPENSE);
        t.setFromAccountId(fromAccountId);
        t.setAmount(source.getAmount());
        return t;
    }

    @Test
    void payingMovesTheNextBillForwardOnlyWhenTheBucketRepeats() {
        LocalDate next = LocalDate.now().plusDays(10);
        service.addBucket(bucket("Wifi", "100", "100", 1, next, null, null, null));
        service.addBucket(bucket("One-off", "100", null, null, null, null, null, null));
        service.updateBucket(bucketId("Buffer"), bucket("Buffer", "0", null, null, null, null, null, null));
        service.fund(new FundRequest(bank.getId(), n("200"), null));

        View v = service.pay(new PayRequest(bucketId("Wifi"), n("100")));
        assertEquals(next.plusMonths(1), v.buckets().stream().filter(b -> b.name().equals("Wifi")).findFirst().orElseThrow().nextPaymentDate());
        v = service.pay(new PayRequest(bucketId("One-off"), n("50")));
        assertEquals(LocalDate.now(), v.buckets().stream().filter(b -> b.name().equals("One-off")).findFirst().orElseThrow().previousPaymentDate());
        assertThrows(NotFoundException.class, () -> service.pay(new PayRequest(null, n("1"))));
    }

    @Test
    void interestIsFiledUnderInvestmentIncomeInterestWhenThoseCategoriesExist() {
        Category parent = categoryService.create(new CategoryData("Investment Income", CategoryKind.INCOME, null));
        Category interest = categoryService.create(new CategoryData("Interest", CategoryKind.INCOME, parent.getId()));

        service.creditInterest(new InterestRequest(n("75")));

        assertTrue(transactions.findAll().stream().anyMatch(t -> interest.getId().equals(t.getCategoryId())));
        assertThrows(ValidationException.class, () -> service.creditInterest(new InterestRequest(BigDecimal.ZERO)));
    }

    @Test
    void interestIsFiledUnderInvestmentIncomeItselfWhenItHasNoInterestSubCategory() {
        Category parent = categoryService.create(new CategoryData("Investment Income", CategoryKind.INCOME, null));

        service.creditInterest(new InterestRequest(n("75")));

        assertTrue(transactions.findAll().stream().anyMatch(t -> parent.getId().equals(t.getCategoryId())));
    }

    @Test
    void interestWithoutThoseCategoriesIsLeftUncategorised() {
        service.creditInterest(new InterestRequest(n("75")));
        assertTrue(transactions.findAll().stream().anyMatch(t -> t.getCategoryId() == null && t.getKind() == TransactionKind.INCOME));
    }

    @Test
    void movingMoneyNeedsTwoDifferentOpenBucketsAndAnAmount() {
        service.addBucket(simple("Rent"));
        Long rent = bucketId("Rent");
        Long buffer = bucketId("Buffer");

        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(null, buffer, n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(rent, null, n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(rent, rent, n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(rent, buffer, null)));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(rent, buffer, BigDecimal.ZERO)));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(rent, buffer, n("1"))));   // holds nothing
        assertThrows(NotFoundException.class, () -> service.transfer(new TransferRequest(987654L, buffer, n("1"))));
    }

    @Test
    void closingNeedsAnOpenBucketThatIsNotTheBufferAndAValidDestination() {
        service.addBucket(simple("Rent"));
        Long rent = bucketId("Rent");
        Long buffer = bucketId("Buffer");

        assertThrows(ValidationException.class, () -> service.close(buffer, null));
        assertThrows(ValidationException.class, () -> service.close(rent, new CloseRequest(rent)));
        assertThrows(NotFoundException.class, () -> service.close(null, null));
        assertThrows(NotFoundException.class, () -> service.close(987654L, null));
        View closed = service.close(rent, null);   // no request at all: what is left goes to the Buffer
        assertEquals(1, closed.closed().size());
    }

    @Test
    void aLedgerWhoseAccountWasRemovedIsForgottenAndCanBeSetUpAgain() {
        Long ledgerAccount = service.get().accountId();
        accounts.deleteById(ledgerAccount);

        assertFalse(service.get().configured());    // a Ledger with no account is not a Ledger
        assertTrue(service.setUp().configured());   // so a new one can be made, and the stale record is replaced
        assertEquals(1, ledgers.count());
    }

    @Test
    void anOlderFundingThatPutPartOfItsMoneyInNoBucketCannotBeDeletedOnceTheBufferHasMovedOn() {
        // An older funding: money went into the Ledger account, but no bucket entry records where it went.
        Transaction old = new Transaction();
        old.setDate(LocalDate.now());
        old.setKind(TransactionKind.TRANSFER);
        old.setFromAccountId(bank.getId());
        old.setToAccountId(service.get().accountId());
        old.setAmount(n("100"));
        Transaction saved = transactions.save(old);

        assertThrows(ValidationException.class, () -> service.deleteFunding(saved.getId()));   // the Buffer does not hold that 100
    }

    @Test
    void theLedgerCannotBeFundedFromJewellery() {
        service.setUp();
        Account jewellery = accountService.create(new AccountData("Jewellery", AccountType.JEWELLERY, null, null, n("50000"), null, null));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(jewellery.getId(), n("100"), null)));
    }
}
