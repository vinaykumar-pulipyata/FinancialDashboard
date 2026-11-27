package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Bucket;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.EntryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.EmergencyFundService.Settings;
import com.learning.spring.financialdashboard.service.LedgerService.BucketData;
import com.learning.spring.financialdashboard.service.LedgerService.BucketView;
import com.learning.spring.financialdashboard.service.LedgerService.CloseRequest;
import com.learning.spring.financialdashboard.service.LedgerService.FundRequest;
import com.learning.spring.financialdashboard.service.LedgerService.InterestRequest;
import com.learning.spring.financialdashboard.service.LedgerService.PayRequest;
import com.learning.spring.financialdashboard.service.LedgerService.TransferRequest;
import com.learning.spring.financialdashboard.service.LedgerService.View;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LedgerServiceTest {

    @Autowired
    LedgerService service;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    RingFenceService ringFenceService;

    @Autowired
    EmergencyFundService emergencyFund;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    BucketRepository bucketRepository;

    @Autowired
    BucketEntryRepository entryRepository;

    @Autowired
    LedgerDetailRepository ledgerRepository;

    @Autowired
    RingFenceRepository ringFenceRepository;

    @Autowired
    EmergencyFundDetailRepository emergencyDetails;

    @Autowired
    CategoryRepository categoryRepository;

    Account bank;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        entryRepository.deleteAll();
        bucketRepository.deleteAll();
        ledgerRepository.deleteAll();
        ringFenceRepository.deleteAll();
        emergencyDetails.deleteAll();
        accountRepository.deleteAll();
        bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, n("100000"), null, null));
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private BucketData data(String name, String monthly, String payment, Integer months, LocalDate next, LocalDate end) {
        return new BucketData(name, n(monthly), payment == null ? null : n(payment), months, next, null, end, null);
    }

    private BucketView bucket(View v, String name) {
        return v.buckets().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    private Long id(String name) {
        return bucket(service.get(), name).id();
    }

    /** The example from the design: insurance 20k, wifi 5k, subscriptions 5k and a 10k buffer, 40k a month. */
    private View example() {
        service.setUp();
        service.addBucket(data("Insurance", "20000", "60000", 3, LocalDate.now().plusDays(30), null));
        service.addBucket(data("Wifi", "5000", "5000", 1, LocalDate.now().plusDays(10), null));
        service.addBucket(data("Subscriptions", "5000", null, null, null, null));
        // The Ledger already starts with a Buffer. Give it its monthly 10,000.
        return service.updateBucket(id("Buffer"), data("Buffer", "10000", null, null, null, null));
    }

    private View fundPlan() {
        return service.fund(new FundRequest(bank.getId(), n("40000"), null));
    }

    private BigDecimal bankBalance() {
        return accountService.listSummaries().stream().filter(s -> s.id().equals(bank.getId()))
                .map(s -> s.balance()).findFirst().orElseThrow();
    }

    private void assertAddsUp(View v) {
        BigDecimal inBuckets = v.buckets().stream().map(BucketView::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertAmount(v.balance().toPlainString(), inBuckets);
    }

    // ---------------------------------------------------------------------------------------------- set up

    @Test
    void notSetUpAtFirstAndNothingWorksUntilItIs() {
        assertFalse(service.get().configured());
        assertThrows(ValidationException.class, () -> service.addBucket(data("Wifi", "5", null, null, null, null)));
    }

    @Test
    void setUpCreatesTheAccountOnlyOnce() {
        View first = service.setUp();
        View second = service.setUp();

        assertTrue(first.configured());
        assertEquals(first.accountId(), second.accountId());
        assertEquals("Future Expenses Ledger", first.name());
        assertAmount("0", first.balance());
        assertEquals(2, accountRepository.count());
    }

    @Test
    void theLedgerStartsWithADefaultBuffer() {
        View v = service.setUp();
        service.setUp();

        assertEquals(1, v.buckets().size());
        BucketView buffer = v.buckets().get(0);
        assertEquals("Buffer", buffer.name());
        assertTrue(buffer.buffer());
        assertAmount("0", buffer.monthlyAllocation());
        assertEquals(1, bucketRepository.count());          // setting up again does not add another
    }

    @Test
    void aLedgerWithNoBucketsAtAllGetsTheBufferAtStartUp() {
        service.setUp();
        entryRepository.deleteAll();
        bucketRepository.deleteAll();                       // as a Ledger set up before the Buffer existed

        service.tidyUp();
        service.tidyUp();

        assertEquals(1, bucketRepository.count());
        assertTrue(bucket(service.get(), "Buffer").buffer());
    }

    @Test
    void anOlderLedgerWithBucketsButNoBufferGetsOneAtStartUp() {
        service.setUp();
        bucketRepository.deleteAll();
        service.addBucket(data("Wifi", "1", null, null, null, null));

        service.tidyUp();
        service.tidyUp();

        assertEquals(2, bucketRepository.count());          // Wifi and a new Buffer, not two Buffers
        assertTrue(bucket(service.get(), "Buffer").buffer());
    }

    @Test
    void moneyThatBelongedToNoBucketMovesIntoTheBufferAtStartUp() {
        service.setUp();
        looseFunding("3000");                                // in the Ledger, but in no bucket

        service.tidyUp();
        service.tidyUp();                                    // running it again changes nothing

        View v = service.get();
        assertAmount("3000", bucket(v, "Buffer").balance());
        assertAmount("3000", v.balance());
        assertAddsUp(v);
    }

    // ---------------------------------------------------------------------------------------------- buckets

    @Test
    void bucketRulesAreChecked() {
        service.setUp();
        service.addBucket(data("Wifi", "5000", null, null, null, null));

        assertThrows(ValidationException.class, () -> service.addBucket(data(" ", "1", null, null, null, null)));
        assertThrows(ValidationException.class, () -> service.addBucket(data("wifi", "1", null, null, null, null)));
        assertThrows(ValidationException.class, () -> service.addBucket(data("A", "-1", null, null, null, null)));
        assertThrows(ValidationException.class, () -> service.addBucket(data("A", "1", "0", null, null, null)));
        assertThrows(ValidationException.class,
                () -> service.addBucket(data("A", "1", null, 1, null, null)));
        assertThrows(ValidationException.class,
                () -> service.addBucket(data("A", "1", null, null, LocalDate.now().plusDays(5), null)));
        assertThrows(ValidationException.class,
                () -> service.addBucket(data("A", "1", null, 0, LocalDate.now().plusDays(5), null)));
        assertThrows(ValidationException.class,
                () -> service.addBucket(data("A", "1", null, 121, LocalDate.now().plusDays(5), null)));
        assertThrows(ValidationException.class,
                () -> service.addBucket(data("A", "1", null, null, null, LocalDate.now().minusDays(1))));
        assertEquals(2, bucketRepository.count());          // the default Buffer and Wifi
    }

    @Test
    void moneyAlreadyInABucketCountsTowardsTheLedger() {
        service.setUp();

        View v = service.addBucket(new BucketData("Insurance", n("20000"), n("60000"), 3, LocalDate.now().plusDays(30), null, null, n("25000")));

        assertAmount("25000", bucket(v, "Insurance").balance());
        assertAmount("25000", v.balance());                                // the Ledger's total includes it
        assertAddsUp(v);
        assertAmount("25000", accountRepository.findById(v.accountId()).orElseThrow().getOpeningBalance());
        assertEquals(List.of(EntryKind.OPENING),
                entryRepository.findAllByBucketIdOrderByDateAscIdAsc(id("Insurance")).stream().map(e -> e.getKind()).toList());
    }

    @Test
    void theDefaultBufferCanBeGivenItsExistingMoney() {
        service.setUp();

        View v = service.updateBucket(id("Buffer"), new BucketData("Buffer", n("0"), null, null, null, null, null, n("8000")));

        assertAmount("8000", bucket(v, "Buffer").balance());
        assertAmount("8000", v.balance());
        assertAddsUp(v);
    }

    @Test
    void changingMoneyHeldCorrectsTheBalanceAndTheLedgerTotal() {
        service.setUp();
        service.addBucket(new BucketData("Wifi", n("5000"), null, null, null, null, null, n("10000")));
        Long wifi = id("Wifi");

        View v = service.updateBucket(wifi, new BucketData("Wifi", n("5000"), null, null, null, null, null, n("7000")));

        assertAmount("7000", bucket(v, "Wifi").balance());
        assertAmount("7000", v.balance());
        assertAddsUp(v);
        List<EntryKind> kinds = entryRepository.findAllByBucketIdOrderByDateAscIdAsc(wifi).stream().map(e -> e.getKind()).toList();
        assertEquals(List.of(EntryKind.OPENING, EntryKind.ADJUST), kinds);
        // Saving without changing the amount adds nothing.
        service.updateBucket(wifi, new BucketData("Wifi", n("5000"), null, null, null, null, null, n("7000")));
        assertEquals(2, entryRepository.findAllByBucketIdOrderByDateAscIdAsc(wifi).size());
    }

    @Test
    void editingWithoutAMoneyHeldFigureLeavesTheBalanceAlone() {
        service.setUp();
        service.addBucket(new BucketData("Wifi", n("5000"), null, null, null, null, null, n("3000")));

        View v = service.updateBucket(id("Wifi"), data("Wifi", "6000", null, null, null, null));

        assertAmount("3000", bucket(v, "Wifi").balance());
        assertAmount("6000", bucket(v, "Wifi").monthlyAllocation());
    }

    @Test
    void moneyHeldCannotBeNegative() {
        service.setUp();

        assertThrows(ValidationException.class, () -> service.addBucket(
                new BucketData("Wifi", n("1"), null, null, null, null, null, n("-1"))));
        assertEquals(1, bucketRepository.count());          // only the default Buffer
    }

    @Test
    void closingPassesTheMonthlyAllocationOnWithTheMoney() {
        example();
        fundPlan();

        View v = service.close(id("Wifi"), new CloseRequest(id("Buffer")));

        assertAmount("15000", bucket(v, "Buffer").balance());                 // its 5,000 moved in
        assertAmount("15000", bucket(v, "Buffer").monthlyAllocation());       // and its 5,000 a month now goes to the buffer
        assertAmount("40000", v.monthlyPlan());                               // so the plan is unchanged
    }

    @Test
    void closingWithoutChoosingWhereGoesToTheBuffer() {
        example();
        fundPlan();

        View v = service.close(id("Wifi"), new CloseRequest(null));

        assertAmount("15000", bucket(v, "Buffer").balance());
        assertAmount("15000", bucket(v, "Buffer").monthlyAllocation());
        assertAmount("40000", v.monthlyPlan());
        assertAddsUp(v);
    }

    @Test
    void aBucketWithNothingLeftStillPassesItsMonthlyAllocationOn() {
        example();          // nothing funded yet, so Wifi holds nothing

        View v = service.close(id("Wifi"), new CloseRequest(id("Insurance")));

        assertAmount("25000", bucket(v, "Insurance").monthlyAllocation());
        assertAmount("40000", v.monthlyPlan());
    }

    @Test
    void aBucketNeedsNoDueDateOrTarget() {
        service.setUp();

        BucketView view = bucket(service.addBucket(data("Cushion", "10000", null, null, null, null)), "Cushion");

        assertFalse(view.funded());
        assertAmount("0", view.shortBy());
        assertFalse(view.buffer());          // only the default Buffer is ever the buffer
    }

    @Test
    void bucketsAreListedByNextPaymentWithUndatedOnesLast() {
        View v = example();

        assertEquals(List.of("Wifi", "Insurance", "Buffer", "Subscriptions"),
                v.buckets().stream().map(BucketView::name).toList());
    }

    // ---------------------------------------------------------------------------------------------- funding

    @Test
    void fundingTheExactPlanGivesEachBucketItsAllocation() {
        example();

        View v = fundPlan();

        assertAmount("20000", bucket(v, "Insurance").balance());
        assertAmount("5000", bucket(v, "Wifi").balance());
        assertAmount("5000", bucket(v, "Subscriptions").balance());
        assertAmount("10000", bucket(v, "Buffer").balance());
        assertAmount("40000", v.balance());
        assertAmount("60000", bankBalance());
        assertAddsUp(v);
        Transaction moved = transactionRepository.findAll().get(0);
        assertEquals(TransactionKind.TRANSFER, moved.getKind());
        assertEquals(bank.getId(), moved.getFromAccountId());
        assertEquals(v.accountId(), moved.getToAccountId());
    }

    @Test
    void aFundingCanBeDatedInThePastButNotTheFuture() {
        service.setUp();
        LocalDate day = LocalDate.now().minusDays(40);

        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("1000"), null, LocalDate.now().plusDays(1))));
        assertEquals(0, transactionRepository.count());

        service.fund(new FundRequest(bank.getId(), n("1000"), null, day));

        assertEquals(1, transactionRepository.count());
        assertEquals(day, transactionRepository.findAll().get(0).getDate(), "the transfer is on the day given");
        assertAmount("1000", bucket(service.get(), "Buffer").balance());   // no plan yet: everything goes to the Buffer
    }

    @Test
    void fundingLessThanThePlanNeedsTheSplitToAddUp() {
        example();
        Map<Long, BigDecimal> split = Map.of(id("Insurance"), n("20000"), id("Wifi"), n("5000"), id("Buffer"), n("5000"));

        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("30000"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("31000"), split)));
        assertEquals(0, transactionRepository.count());

        View v = service.fund(new FundRequest(bank.getId(), n("30000"), split));

        assertAmount("20000", bucket(v, "Insurance").balance());
        assertAmount("0", bucket(v, "Subscriptions").balance());
        assertAmount("5000", bucket(v, "Buffer").balance());
        assertAmount("30000", v.balance());
        assertAddsUp(v);
    }

    @Test
    void moreThanThePlanSendsTheExtraToTheBuffer() {
        example();

        View v = service.fund(new FundRequest(bank.getId(), n("45000"), null));

        assertAmount("15000", bucket(v, "Buffer").balance());
        assertAddsUp(v);
    }

    @Test
    void anOlderLedgerWithoutABufferGetsOneWhenFunded() {
        service.setUp();
        service.addBucket(data("Wifi", "5000", null, null, null, null));
        bucketRepository.deleteById(id("Buffer"));       // a Ledger from before the Buffer was always there

        View v = service.fund(new FundRequest(bank.getId(), n("8000"), null));

        assertAmount("5000", bucket(v, "Wifi").balance());
        assertAmount("3000", bucket(v, "Buffer").balance());       // the extra went to a new Buffer
        assertTrue(bucket(v, "Buffer").buffer());
        assertAddsUp(v);
    }

    @Test
    void aBucketPastItsEndDateMustBeClosedOrExtendedBeforeFunding() {
        example();
        Long wifi = id("Wifi");
        Bucket entity = bucketRepository.findById(wifi).orElseThrow();
        entity.setEndDate(LocalDate.now().minusDays(1));
        bucketRepository.save(entity);
        assertTrue(bucket(service.get(), "Wifi").endDatePassed());

        ValidationException refused = assertThrows(ValidationException.class, this::fundPlan);
        assertTrue(refused.getMessage().contains("Wifi"));

        service.updateBucket(wifi, data("Wifi", "5000", "5000", 1, LocalDate.now().plusDays(10), LocalDate.now().plusMonths(6)));
        assertAmount("5000", bucket(fundPlan(), "Wifi").balance());

        entity = bucketRepository.findById(wifi).orElseThrow();
        entity.setEndDate(LocalDate.now().minusDays(1));
        bucketRepository.save(entity);
        service.close(wifi, new CloseRequest(null));
        // Wifi's 5,000 went to the Buffer, and its monthly 5,000 with it, so the plan is still 40,000.
        assertAmount("80000", service.fund(new FundRequest(bank.getId(), n("40000"), null)).balance());
    }

    @Test
    void fundingWithOnlyTheDefaultBufferSendsEverythingToIt() {
        service.setUp();

        View v = service.fund(new FundRequest(bank.getId(), n("1000"), null));

        assertAmount("1000", bucket(v, "Buffer").balance());
    }

    @Test
    void theMoneyMustComeFromAnAllowedAccount() {
        View v = example();
        Account fenced = accountService.create(new AccountData("Goal", AccountType.GOAL_FUND, null, null, n("500"), n("500"), null));
        ringFenceService.fence(fenced.getId());
        Account inactive = accountService.create(new AccountData("Old", AccountType.BANK, null, null, n("500"), null, null));
        accountService.deactivate(inactive.getId());

        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(v.accountId(), n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(fenced.getId(), n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(inactive.getId(), n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(9999L, n("100"), null)));
        assertThrows(ValidationException.class, () -> service.fund(new FundRequest(bank.getId(), n("0"), null)));
    }

    // ---------------------------------------------------------------------------------------------- deleting a funding

    /** A transfer into the Ledger with no bucket entries, as an older version could leave behind. */
    private Transaction looseFunding(String amount) {
        Transaction raw = new Transaction();
        raw.setKind(TransactionKind.TRANSFER);
        raw.setFromAccountId(bank.getId());
        raw.setToAccountId(service.get().accountId());
        raw.setAmount(n(amount));
        raw.setDate(LocalDate.now());
        return transactionRepository.save(raw);
    }

    private Transaction theFunding() {
        return transactionRepository.findAll().stream()
                .filter(x -> x.getKind() == TransactionKind.TRANSFER).findFirst().orElseThrow();
    }

    @Test
    void aFundingCanBeDeletedAndTheMoneyComesBackOutOfTheBuckets() {
        example();
        fundPlan();
        Long funding = theFunding().getId();

        View v = service.deleteFunding(funding);

        assertAmount("0", v.balance());
        v.buckets().forEach(b -> assertAmount("0", b.balance()));
        assertAmount("100000", bankBalance());
        assertEquals(0, transactionRepository.count());
        assertEquals(0, entryRepository.count());
        assertAddsUp(v);
    }

    @Test
    void deletingAFundingWithExtraTakesTheExtraBackOutOfTheBuffer() {
        example();
        service.fund(new FundRequest(bank.getId(), n("45000"), null));          // 5,000 extra went to the Buffer

        View v = service.deleteFunding(theFunding().getId());

        assertAmount("0", v.balance());
        v.buckets().forEach(b -> assertAmount("0", b.balance()));
        assertAmount("100000", bankBalance());
    }

    @Test
    void anOlderFundingWhoseLooseMoneyWentToTheBufferCanStillBeDeleted() {
        service.setUp();
        Transaction old = looseFunding("3000");
        service.tidyUp();                                       // the loose 3,000 moves into the Buffer
        assertAmount("3000", bucket(service.get(), "Buffer").balance());

        View v = service.deleteFunding(old.getId());

        assertAmount("0", v.balance());
        assertAmount("0", bucket(v, "Buffer").balance());
        assertAddsUp(v);
    }

    @Test
    void aFundingCannotBeDeletedOnceABucketHasUsedItsMoney() {
        example();
        fundPlan();
        service.pay(new PayRequest(id("Wifi"), n("3000")));      // Wifi now holds 2,000 of the 5,000 it was given

        ValidationException refused = assertThrows(ValidationException.class, () -> service.deleteFunding(theFunding().getId()));

        assertTrue(refused.getMessage().contains("Wifi"));
        assertAmount("37000", service.get().balance());           // nothing changed
        assertEquals(2, transactionRepository.count());
    }

    @Test
    void onlyAFundingCanBeDeletedThisWay() {
        example();
        fundPlan();
        service.pay(new PayRequest(id("Wifi"), n("1000")));
        service.creditInterest(new InterestRequest(n("100")));
        Transaction payment = transactionRepository.findAll().stream().filter(x -> x.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow();
        Transaction interest = transactionRepository.findAll().stream().filter(x -> x.getKind() == TransactionKind.INCOME).findFirst().orElseThrow();
        Transaction ordinary = transactionService.create(new TransactionData(LocalDate.now(), TransactionKind.EXPENSE,
                bank.getId(), null, n("10"), null, null, null, null, null));

        assertThrows(ValidationException.class, () -> service.deleteFunding(payment.getId()));
        assertThrows(ValidationException.class, () -> service.deleteFunding(interest.getId()));
        assertThrows(ValidationException.class, () -> service.deleteFunding(ordinary.getId()));
        assertThrows(com.learning.spring.financialdashboard.exception.NotFoundException.class, () -> service.deleteFunding(9999L));
    }

    // ---------------------------------------------------------------------------------------------- paying

    @Test
    void payingTakesFromTheBucketAndTheLedgerAndMovesTheDates() {
        example();
        fundPlan();
        LocalDate next = LocalDate.now().plusDays(30);

        View v = service.pay(new PayRequest(id("Insurance"), n("15000")));

        BucketView insurance = bucket(v, "Insurance");
        assertAmount("5000", insurance.balance());
        assertAmount("25000", v.balance());
        assertEquals(LocalDate.now(), insurance.previousPaymentDate());
        assertEquals(next.plusMonths(3), insurance.nextPaymentDate());
        assertAddsUp(v);
        Transaction paid = transactionRepository.findAll().stream()
                .filter(t -> t.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow();
        assertEquals(v.accountId(), paid.getFromAccountId());
        assertEquals("Paid: Insurance", paid.getNote());
        assertAmount("15000", paid.getAmount());
    }

    @Test
    void anyNumberOfMonthsBetweenBillsWorks() {
        service.setUp();
        LocalDate due = LocalDate.now().plusDays(5);
        service.addBucket(data("Every two months", "2000", "2000", 2, due, null));
        service.fund(new FundRequest(bank.getId(), n("2000"), null));          // exactly the plan: the bucket gets 2,000

        View v = service.pay(new PayRequest(id("Every two months"), n("2000")));

        assertEquals(due.plusMonths(2), bucket(v, "Every two months").nextPaymentDate());
        assertEquals(Integer.valueOf(2), bucket(v, "Every two months").frequencyMonths());
    }

    @Test
    void payingIsRefusedWhenTheBucketHoldsLessAndWorksAfterATransfer() {
        example();
        fundPlan();      // Insurance holds 20,000

        ValidationException refused = assertThrows(ValidationException.class,
                () -> service.pay(new PayRequest(id("Wifi"), n("8000"))));   // Wifi holds 5,000
        assertTrue(refused.getMessage().contains("3000"));
        assertAmount("5000", bucket(service.get(), "Wifi").balance());
        assertEquals(1, transactionRepository.count());                       // only the funding

        service.transfer(new TransferRequest(id("Buffer"), id("Wifi"), n("3000")));
        View v = service.pay(new PayRequest(id("Wifi"), n("8000")));

        assertAmount("0", bucket(v, "Wifi").balance());
        assertAmount("7000", bucket(v, "Buffer").balance());
        assertAddsUp(v);
    }

    @Test
    void anAmountIsRequiredToPay() {
        example();
        fundPlan();

        assertThrows(ValidationException.class, () -> service.pay(new PayRequest(id("Wifi"), null)));
        assertThrows(ValidationException.class, () -> service.pay(new PayRequest(id("Wifi"), n("0"))));
    }

    // ---------------------------------------------------------------------------------------------- moving money

    @Test
    void moneyMovesBetweenBuckets() {
        example();
        service.fund(new FundRequest(bank.getId(), n("45000"), null));          // Buffer holds 15,000

        View v = service.transfer(new TransferRequest(id("Buffer"), id("Insurance"), n("2000")));
        assertAmount("22000", bucket(v, "Insurance").balance());
        assertAmount("13000", bucket(v, "Buffer").balance());

        v = service.transfer(new TransferRequest(id("Insurance"), id("Wifi"), n("400")));
        assertAmount("21600", bucket(v, "Insurance").balance());
        assertAmount("5400", bucket(v, "Wifi").balance());
        assertAddsUp(v);
    }

    @Test
    void movingMoreThanIsThereOrNowhereIsRefused() {
        example();
        fundPlan();

        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(id("Wifi"), id("Buffer"), n("6000"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(null, id("Buffer"), n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(id("Wifi"), id("Wifi"), n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(null, null, n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(id("Wifi"), id("Buffer"), n("0"))));
    }

    // ---------------------------------------------------------------------------------------------- closing

    @Test
    void closingMovesWhatIsLeftAndKeepsTheHistory() {
        example();
        fundPlan();
        service.pay(new PayRequest(id("Wifi"), n("2000")));                     // Wifi has 3,000 left
        Long wifi = id("Wifi");

        View v = service.close(wifi, new CloseRequest(id("Buffer")));

        assertAmount("13000", bucket(v, "Buffer").balance());
        assertTrue(v.buckets().stream().noneMatch(b -> b.name().equals("Wifi")));
        assertEquals(1, v.closed().size());
        assertEquals("Wifi", v.closed().get(0).name());
        assertAmount("2000", v.closed().get(0).totalPaid());
        assertAddsUp(v);
        // Nothing was deleted: the bucket and all its entries are still there.
        assertTrue(bucketRepository.findById(wifi).orElseThrow().getClosedAt() != null);
        List<EntryKind> kinds = entryRepository.findAllByBucketIdOrderByDateAscIdAsc(wifi).stream().map(e -> e.getKind()).toList();
        assertEquals(List.of(EntryKind.FUND, EntryKind.PAY, EntryKind.CLOSE_OUT), kinds);
    }

    @Test
    void aClosedBucketCannotBeUsedAndItsDestinationMustBeAnotherOpenBucket() {
        example();
        fundPlan();
        Long wifi = id("Wifi");

        assertThrows(ValidationException.class, () -> service.close(wifi, new CloseRequest(wifi)));
        service.close(wifi, new CloseRequest(id("Buffer")));

        assertThrows(ValidationException.class, () -> service.pay(new PayRequest(wifi, n("1"))));
        assertThrows(ValidationException.class, () -> service.transfer(new TransferRequest(wifi, id("Buffer"), n("1"))));
        assertThrows(ValidationException.class, () -> service.close(wifi, new CloseRequest(null)));
        assertThrows(ValidationException.class, () -> service.updateBucket(wifi, data("Wifi", "1", null, null, null, null)));
    }

    @Test
    void theBufferHasNoBillAmountPaymentDatesOrEndDate() {
        service.setUp();
        Long buffer = id("Buffer");
        LocalDate past = LocalDate.now().minusDays(5);

        assertThrows(ValidationException.class, () -> service.updateBucket(buffer, data("Buffer", "0", "1000", null, null, null)));
        assertThrows(ValidationException.class, () -> service.updateBucket(buffer,
                data("Buffer", "0", null, 1, LocalDate.now().plusDays(5), null)));
        assertThrows(ValidationException.class, () -> service.updateBucket(buffer,
                data("Buffer", "0", null, null, null, LocalDate.now().plusMonths(3))));
        assertThrows(ValidationException.class, () -> service.updateBucket(buffer,
                new BucketData("Buffer", n("0"), null, null, null, past, null, null)));
        BucketView view = bucket(service.get(), "Buffer");
        assertEquals(null, view.paymentAmount());
        assertEquals(null, view.endDate());
        assertEquals(null, view.nextPaymentDate());
        assertEquals(null, view.previousPaymentDate());
    }

    @Test
    void aNewBucketCanNeverBeTheBuffer() {
        service.setUp();

        View v = service.addBucket(data("Reserve", "1", "500", null, null, null));

        assertFalse(bucket(v, "Reserve").buffer());
        assertEquals(1, v.buckets().stream().filter(BucketView::buffer).count());
    }

    @Test
    void thePreviousPaymentDateCanBeEnteredAndIsChecked() {
        service.setUp();
        LocalDate last = LocalDate.now().minusDays(20);

        View v = service.addBucket(new BucketData("Insurance", n("1000"), n("3000"), 3,
                LocalDate.now().plusDays(70), last, null, null));
        assertEquals(last, bucket(v, "Insurance").previousPaymentDate());

        assertThrows(ValidationException.class, () -> service.addBucket(new BucketData("Future", n("1"), null, null,
                null, LocalDate.now().plusDays(1), null, null)));
        v = service.updateBucket(id("Insurance"), new BucketData("Insurance", n("1000"), n("3000"), 3,
                LocalDate.now().plusDays(70), null, null, null));
        assertEquals(null, bucket(v, "Insurance").previousPaymentDate());        // it can be cleared again
    }

    @Test
    void interestGoesToTheBufferAndRaisesTheLedger() {
        categoryRepository.deleteAll();
        Category parent = new Category();
        parent.setName("Investment Income");
        parent.setKind(CategoryKind.INCOME);
        parent = categoryRepository.save(parent);
        Category interest = new Category();
        interest.setName("Interest");
        interest.setKind(CategoryKind.INCOME);
        interest.setParentId(parent.getId());
        interest = categoryRepository.save(interest);
        example();
        fundPlan();

        View v = service.creditInterest(new InterestRequest(n("1250")));

        assertAmount("11250", bucket(v, "Buffer").balance());
        assertAmount("41250", v.balance());
        assertAddsUp(v);
        Transaction income = transactionRepository.findAll().stream()
                .filter(x -> x.getKind() == TransactionKind.INCOME).findFirst().orElseThrow();
        assertEquals(v.accountId(), income.getToAccountId());
        assertEquals(interest.getId(), income.getCategoryId());
        assertAmount("1250", income.getAmount());
        assertEquals(List.of(EntryKind.FUND, EntryKind.INTEREST),
                entryRepository.findAllByBucketIdOrderByDateAscIdAsc(id("Buffer")).stream().map(e -> e.getKind()).toList());
    }

    @Test
    void interestStillWorksWhenTheInterestCategoryIsGone() {
        categoryRepository.deleteAll();
        service.setUp();

        service.creditInterest(new InterestRequest(n("500")));

        assertEquals(null, transactionRepository.findAll().get(0).getCategoryId());
        assertAmount("500", bucket(service.get(), "Buffer").balance());
    }

    @Test
    void interestNeedsAnAmount() {
        service.setUp();

        assertThrows(ValidationException.class, () -> service.creditInterest(new InterestRequest(null)));
        assertThrows(ValidationException.class, () -> service.creditInterest(new InterestRequest(n("0"))));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void interestStillGoesToABufferInAnOlderLedgerThatLostIt() {
        service.setUp();
        bucketRepository.deleteById(id("Buffer"));

        View v = service.creditInterest(new InterestRequest(n("100")));

        assertAmount("100", bucket(v, "Buffer").balance());
        assertAddsUp(v);
    }

    @Test
    void theBufferCannotBeClosed() {
        example();

        assertThrows(ValidationException.class, () -> service.close(id("Buffer"), new CloseRequest(null)));
        assertThrows(ValidationException.class, () -> service.close(id("Buffer"), new CloseRequest(id("Wifi"))));
        assertTrue(bucket(service.get(), "Buffer").buffer());
        assertEquals(0, service.get().closed().size());
    }

    @Test
    void aBucketIsFundedOnceItsBalanceReachesTheBill() {
        service.setUp();
        service.addBucket(data("Tax", "10000", "5000", 12, LocalDate.now().plusDays(60), null));
        Long tax = id("Tax");
        service.fund(new FundRequest(bank.getId(), n("4000"), Map.of(tax, n("4000"))));      // less than the 10,000 plan

        BucketView view = bucket(service.get(), "Tax");
        assertFalse(view.funded());
        assertAmount("1000", view.shortBy());

        service.fund(new FundRequest(bank.getId(), n("1000"), Map.of(tax, n("1000"))));

        view = bucket(service.get(), "Tax");
        assertTrue(view.funded());
        assertAmount("0", view.shortBy());
    }

    // ---------------------------------------------------------------------------------------------- the rest of the app

    @Test
    void theOrdinaryFormsCannotTouchTheLedger() {
        View v = example();
        fundPlan();
        TransactionData into = new TransactionData(LocalDate.now(), TransactionKind.TRANSFER, bank.getId(),
                v.accountId(), n("100"), null, null, null, null, null);
        TransactionData spend = new TransactionData(LocalDate.now(), TransactionKind.EXPENSE, v.accountId(), null,
                n("100"), null, null, null, null, null);
        Transaction funding = transactionRepository.findAll().get(0);

        assertThrows(ValidationException.class, () -> transactionService.create(into));
        assertThrows(ValidationException.class, () -> transactionService.create(spend));
        assertThrows(ValidationException.class, () -> transactionService.update(funding.getId(), into));
        assertThrows(ValidationException.class, () -> transactionService.delete(funding.getId()));
        assertEquals(1, transactionRepository.count());
    }

    @Test
    void theLedgerCannotBeRingFencedOrBeTheEmergencyFund() {
        View v = example();

        assertThrows(ValidationException.class, () -> ringFenceService.fence(v.accountId()));
        assertThrows(ValidationException.class, () -> emergencyFund.save(
                new Settings(v.accountId(), n("0"), com.learning.spring.financialdashboard.model.TargetMode.AMOUNT, null,
                        n("1000"), null, null)));
    }

    @Test
    void deletingAnEmptyLedgerRemovesItsBucketsButOneWithTransactionsIsKept() {
        View v = example();
        accountService.delete(v.accountId());
        assertEquals(0, bucketRepository.count());
        assertEquals(0, ledgerRepository.count());

        View again = example();
        fundPlan();
        assertThrows(ValidationException.class, () -> accountService.delete(again.accountId()));
        assertEquals(4, bucketRepository.count());
    }
}
