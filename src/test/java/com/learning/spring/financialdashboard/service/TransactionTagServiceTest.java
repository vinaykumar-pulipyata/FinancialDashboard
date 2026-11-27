package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.CategoryService.CategoryData;
import com.learning.spring.financialdashboard.service.TransactionService.TransactionData;
import com.learning.spring.financialdashboard.startup.TransactionTagBackfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tags on transactions: from the note and description first, then the sub category, then the category; or exactly what was typed. */
@SpringBootTest
class TransactionTagServiceTest {

    @Autowired
    TransactionTagService tags;
    @Autowired
    TransactionService service;
    @Autowired
    TransactionRepository repository;
    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    CategoryService categoryService;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    AppSettingRepository settings;
    @Autowired
    TransactionTagBackfill backfill;

    Account bank;
    Category food;
    Category dining;

    @BeforeEach
    void setUp() {
        clean();
        bank = accountService.create(new AccountData("Tag bank", AccountType.BANK, null, null, new BigDecimal("1000"), null, null));
        food = categoryService.create(new CategoryData("Food", CategoryKind.EXPENSE, null));
        dining = categoryService.create(new CategoryData("Dining out", CategoryKind.EXPENSE, food.getId()));
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
        categoryRepository.deleteAll();
        accountRepository.deleteAll();
        settings.deleteAll();
    }

    private Transaction spend(String note, String description, Long categoryId, List<String> tagList) {
        return service.create(new TransactionData(LocalDate.of(2026, 10, 1), TransactionKind.EXPENSE, bank.getId(), null,
                new BigDecimal("100"), null, categoryId, note, description, null, tagList));
    }

    @Test
    void wordsFromTheNoteComeFirstAndStopWordsAreLeftOut() {
        assertEquals(List.of("team", "dinner", "ravi"), tags.suggest("Paid for the team dinner with ravi", null, dining.getId()));
    }

    @Test
    void aRunOfCapitalisedWordsIsOneNameAndTheDescriptionCountsToo() {
        assertEquals(List.of("barbeque-nation", "dinner", "birthday"), tags.suggest("Dinner at Barbeque Nation", "birthday", dining.getId()));
        assertEquals("barbeque-nation", tags.suggest("Dinner at Barbeque Nation", null, null).get(0));
    }

    @Test
    void withTooFewTagsFromTheTextTheSubCategoryThenTheCategoryFillUp() {
        assertEquals(List.of("lunch", "dining-out", "food"), tags.suggest("lunch", null, dining.getId()));
        assertEquals(List.of("dining-out", "food"), tags.suggest(null, null, dining.getId()), "no note: the sub category, then the category");
        assertEquals(List.of("food"), tags.suggest("  ", "", food.getId()), "a plain category has no sub category");
        assertEquals(List.of("team", "dinner", "ravi"), tags.suggest("team dinner ravi", null, dining.getId()), "three from the text already: nothing added");
        assertEquals(List.of(), tags.suggest(null, null, null), "a transfer with no note has nothing to tag");
    }

    @Test
    void phrasesAndWordsUsedBeforeComeFirst() {
        spend("Dinner at Barbeque Nation", null, dining.getId(), List.of("barbeque-nation", "family"));
        spend("Family trip", null, dining.getId(), List.of("family"));
        // "family" was used twice and "barbeque nation" is a learned phrase, so both lead even though the text mentions them late
        assertEquals(List.of("barbeque-nation", "family", "snacks"), tags.suggest("snacks and family at barbeque nation", null, dining.getId()));
    }

    @Test
    void aTransactionSavedWithoutTagsGetsThemAndOneSavedWithTagsKeepsExactlyThose() {
        Transaction auto = spend("Cab to the airport", null, dining.getId(), null);
        assertEquals(Set.of("airport", "cab"), auto.getTags().stream().filter(t -> !t.equals("dining-out")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, auto.getTags().size());

        Transaction typed = spend("Cab to the airport", null, dining.getId(), List.of(" Trip ", "#work", "trip"));
        assertEquals(Set.of("trip", "work"), typed.getTags(), "cleaned, no duplicates, nothing added");
        assertEquals(Set.of(), spend("Cab", null, dining.getId(), List.of()).getTags(), "an empty list means no tags at all");
    }

    @Test
    void editingWithoutTagsWorksThemOutAgainAndEditingWithTagsKeepsThem() {
        Transaction t = spend("Cab ride", null, null, null);
        assertTrue(t.getTags().contains("cab"));
        TransactionData same = new TransactionData(t.getDate(), t.getKind(), t.getFromAccountId(), null, t.getAmount(), null, null, "Metro card", null, null, null);
        assertEquals(Set.of("metro", "card"), service.update(t.getId(), same).getTags());
        TransactionData typed = new TransactionData(t.getDate(), t.getKind(), t.getFromAccountId(), null, t.getAmount(), null, null, "Metro card", null, null, List.of("commute"));
        assertEquals(Set.of("commute"), service.update(t.getId(), typed).getTags());
    }

    @Test
    void tagsAreCheckedLikeDiaryTagsWithoutQuotingThem() {
        for (List<String> bad : List.of(List.of("no good!"), List.of("x".repeat(31)),
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"))) {
            ValidationException e = assertThrows(ValidationException.class, () -> spend("x", null, null, bad));
            assertTrue(!e.getMessage().contains("no good"), "the tag text is not in the message: " + e.getMessage());
        }
    }

    @Test
    void deletingATransactionTakesItsTagsWithIt() {
        Transaction t = spend("Cab", null, null, List.of("cab", "work"));
        service.delete(t.getId());
        assertTrue(tags.vocabulary().isEmpty());
    }

    @Test
    void theBackfillTagsEarlierTransactionsOnceAndNeverAgain() {
        Transaction old = repository.save(plain("Dinner at Barbeque Nation", dining.getId()));
        Transaction empty = repository.save(plain(null, null));
        assertTrue(old.getTags().isEmpty());

        backfill.backfill();
        assertEquals(3, repository.findById(old.getId()).orElseThrow().getTags().size());
        assertTrue(repository.findById(old.getId()).orElseThrow().getTags().contains("barbeque-nation"));
        assertTrue(repository.findById(empty.getId()).orElseThrow().getTags().isEmpty(), "nothing to tag from");

        // a tag removed by hand afterwards stays removed: the backfill does not run twice
        Transaction again = repository.findById(old.getId()).orElseThrow();
        again.setTags(List.of());
        repository.save(again);
        backfill.backfill();
        assertTrue(repository.findById(old.getId()).orElseThrow().getTags().isEmpty());
    }

    private Transaction plain(String note, Long categoryId) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.of(2026, 9, 1));
        t.setKind(TransactionKind.EXPENSE);
        t.setFromAccountId(bank.getId());
        t.setAmount(new BigDecimal("50"));
        t.setCategoryId(categoryId);
        t.setNote(note);
        return t;
    }
}
