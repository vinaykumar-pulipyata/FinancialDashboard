package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Note;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import com.learning.spring.financialdashboard.service.NoteService.DayCount;
import com.learning.spring.financialdashboard.service.NoteService.NoteData;
import com.learning.spring.financialdashboard.service.NoteService.TagCount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diary: several notes a day, edited, searched, listed and counted by day. */
@SpringBootTest
class NoteServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @Autowired
    NoteService service;
    @Autowired
    NoteRepository repository;
    @Autowired
    com.learning.spring.financialdashboard.repository.NoteFolderRepository folderRepository;
    @Autowired
    com.learning.spring.financialdashboard.repository.AppSettingRepository settings;
    @Autowired
    DiaryVault vault;

    @BeforeEach
    @AfterEach
    void clean() {
        DiaryVault.unbind();
        vault.forget();
        repository.deleteAll();
        folderRepository.deleteAll();
        settings.deleteById(DiaryVault.WRAPPED);
        settings.deleteById(DiaryVault.SALT);
    }

    /** Everything that matches, in one big page. */
    private List<Note> list(LocalDate date, String text, String tag) {
        return service.page(date, text, tag, 0, NoteService.MAX_PAGE_SIZE).notes();
    }

    private Note add(LocalDate date, String title, String body) {
        return service.create(new NoteData(date, title, body));
    }

    @Test
    void aDayCanHaveSeveralNotes() {
        add(DAY, "Salary", "Salary came in, paid rent.");
        add(DAY, null, "Called the bank about the card limit.");
        add(DAY.minusDays(1), "Groceries", "Bought for the week.");

        List<Note> that = list(DAY, null, null);
        assertEquals(2, that.size());
        assertEquals("Salary", that.get(0).getTitle());   // oldest first within a day
        assertNull(that.get(1).getTitle());
        assertEquals(List.of(new DayCount(DAY.minusDays(1), 1), new DayCount(DAY, 2)), service.days());
    }

    @Test
    void textAndTitlesAreTrimmedAndABlankTitleBecomesNothing() {
        Note note = add(DAY, "   ", "   Bought a laptop.  \n");

        assertNull(note.getTitle());
        assertEquals("Bought a laptop.", note.getBody());
        assertNotNull(note.getId());
        assertNotNull(note.getCreatedAt());
        assertEquals("Trip", add(DAY, "  Trip ", "x").getTitle());
    }

    @Test
    void aNoteNeedsADayAndSomeTextAndRespectsTheLimits() {
        assertThrows(ValidationException.class, () -> add(null, "T", "text"));
        assertThrows(ValidationException.class, () -> add(DAY, "T", null));
        assertThrows(ValidationException.class, () -> add(DAY, "T", "   \n  "));
        assertThrows(ValidationException.class, () -> add(DAY, "x".repeat(NoteService.MAX_TITLE + 1), "text"));
        assertThrows(ValidationException.class, () -> add(DAY, "T", "x".repeat(NoteService.MAX_BODY + 1)));

        add(DAY, "x".repeat(NoteService.MAX_TITLE), "y".repeat(NoteService.MAX_BODY));   // the longest allowed
        assertEquals(1, repository.count());
    }

    @Test
    void aNoteCanBeEditedMovedToAnotherDayAndDeleted() {
        Note note = add(DAY, "Old", "Old text");

        Note edited = service.update(note.getId(), new NoteData(DAY.plusDays(2), "New", "New text"));

        assertEquals(DAY.plusDays(2), edited.getDate());
        assertEquals("New", edited.getTitle());
        assertEquals("New text", service.get(note.getId()).getBody());
        assertFalse(edited.getUpdatedAt().isBefore(edited.getCreatedAt()));
        assertEquals(1, repository.count());

        service.delete(note.getId());
        assertEquals(0, repository.count());
    }

    @Test
    void anEditThatIsInvalidLeavesTheNoteAsItWas() {
        Note note = add(DAY, "Keep", "Keep this");

        assertThrows(ValidationException.class, () -> service.update(note.getId(), new NoteData(DAY, "Keep", " ")));

        assertEquals("Keep this", service.get(note.getId()).getBody());
    }

    @Test
    void aMissingNoteIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.get(987654L));
        assertThrows(NotFoundException.class, () -> service.get(null));
        assertThrows(NotFoundException.class, () -> service.update(987654L, new NoteData(DAY, "T", "text")));
        assertThrows(NotFoundException.class, () -> service.delete(987654L));
    }

    @Test
    void searchLooksInTitlesAndTextIgnoringCapitalsAndListsNewestFirst() {
        add(DAY.minusDays(5), "Rent", "Paid the landlord");
        add(DAY, null, "RENT went up this month");
        add(DAY.minusDays(2), "Holiday", "Booked flights");

        List<Note> found = list(null, "  rent ", null);

        assertEquals(2, found.size());
        assertEquals(DAY, found.get(0).getDate());
        assertEquals(DAY.minusDays(5), found.get(1).getDate());
        assertEquals(1, list(null, "FLIGHTS", null).size());
        assertTrue(list(null, "nothing like this", null).isEmpty());
    }

    @Test
    void notesComeInPagesNewestFirstWithTheTotals() {
        for (int i = 0; i < 55; i++) {
            add(DAY.minusDays(i), null, "note " + i);   // note 0 is the newest
        }

        NoteService.NotePage first = service.page(null, null, null, 0, 20);
        assertEquals(20, first.notes().size());
        assertEquals(55, first.total());
        assertEquals(3, first.pages());
        assertEquals(0, first.page());
        assertEquals(20, first.size());
        assertEquals("note 0", first.notes().get(0).getBody());
        assertEquals("note 19", first.notes().get(19).getBody());

        NoteService.NotePage last = service.page(null, null, null, 2, 20);
        assertEquals(15, last.notes().size());
        assertEquals("note 40", last.notes().get(0).getBody());
        assertEquals("note 54", last.notes().get(14).getBody());

        NoteService.NotePage beyond = service.page(null, null, null, 9, 20);
        assertTrue(beyond.notes().isEmpty());
        assertEquals(55, beyond.total());
    }

    @Test
    void thePageNumberAndSizeAreKeptSensible() {
        for (int i = 0; i < 120; i++) {
            add(DAY, null, "note " + i);
        }

        assertEquals(NoteService.DEFAULT_PAGE_SIZE, service.page(null, null, null, null, null).notes().size());
        assertEquals(NoteService.DEFAULT_PAGE_SIZE, service.page(null, null, null, 0, 0).size());           // no size
        assertEquals(NoteService.DEFAULT_PAGE_SIZE, service.page(null, null, null, 0, -5).size());
        assertEquals(NoteService.MAX_PAGE_SIZE, service.page(null, null, null, 0, 5000).notes().size());   // capped
        assertEquals(0, service.page(null, null, null, -3, 10).page());                                      // first page
    }

    @Test
    void searchTagAndBothPageTooWithTheirOwnTotals() {
        for (int i = 0; i < 25; i++) {
            tagged(DAY.minusDays(i), "rent payment " + i, "rent");
        }
        for (int i = 0; i < 7; i++) {
            tagged(DAY.minusDays(i), "other rent talk " + i, "misc");
        }

        NoteService.NotePage byText = service.page(null, "RENT", null, 1, 10);
        assertEquals(32, byText.total());
        assertEquals(4, byText.pages());
        assertEquals(10, byText.notes().size());

        NoteService.NotePage byTag = service.page(null, null, "rent", 2, 10);
        assertEquals(25, byTag.total());
        assertEquals(5, byTag.notes().size());

        NoteService.NotePage both = service.page(null, "talk", "misc", 0, 5);
        assertEquals(7, both.total());
        assertEquals(2, both.pages());
        assertEquals(5, both.notes().size());
    }

    @Test
    void aDayIsOnePageHoweverManyNotesItHas() {
        for (int i = 0; i < 14; i++) {
            add(DAY, null, "note " + i);
        }
        add(DAY.minusDays(1), null, "another day");

        NoteService.NotePage day = service.page(DAY, null, null, 3, 2);   // page and size are ignored for a day

        assertEquals(14, day.notes().size());
        assertEquals(14, day.total());
        assertEquals(1, day.pages());
        assertEquals("note 0", day.notes().get(0).getBody());
        assertEquals(0, service.page(DAY.plusDays(5), null, null, null, null).total());
        assertEquals(1, service.page(DAY.plusDays(5), null, null, null, null).size());
    }

    // ------------------------------------------------------------------------------------------ tags

    private Note tagged(LocalDate date, String body, String... tags) {
        return service.create(new NoteData(date, null, body, List.of(tags)));
    }

    @Test
    void tagsAreStoredLowerCaseWithoutAHashAndWithSpacesAsHyphens() {
        Note note = tagged(DAY, "text", "Insurance", "#Car", "  home loan ", "insurance", "##Tax_2026", "", "  ");

        assertEquals(List.of("car", "home-loan", "insurance", "tax_2026"), List.copyOf(note.getTags()));   // sorted, no repeats
        assertEquals(List.of("car", "home-loan", "insurance", "tax_2026"), List.copyOf(service.get(note.getId()).getTags()));
    }

    @Test
    void aNoteWithoutATagListHasNoTags_butNullMeansWorkThemOut() {
        assertTrue(add(DAY, null, "no tags").getTags().isEmpty(), "the three-argument form is explicitly untagged");
        assertEquals(java.util.Set.of("garden", "plants"), service.create(new NoteData(DAY, null, "garden plants", null)).getTags(), "null: worked out from the text");
        assertTrue(service.create(new NoteData(DAY, null, "garden plants", List.of())).getTags().isEmpty(), "an empty list: none");
    }

    @Test
    void tagsAreLimitedInNumberLengthAndCharacters() {
        assertThrows(ValidationException.class, () -> tagged(DAY, "text", "a".repeat(NoteService.MAX_TAG_LENGTH + 1)));
        assertThrows(ValidationException.class, () -> tagged(DAY, "text", "rent & bills"));
        assertThrows(ValidationException.class, () -> tagged(DAY, "text", "#"));
        String[] eleven = new String[NoteService.MAX_TAGS + 1];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = "tag" + i;
        }
        assertThrows(ValidationException.class, () -> tagged(DAY, "text", eleven));
        tagged(DAY, "text", java.util.Arrays.copyOf(eleven, NoteService.MAX_TAGS));   // exactly ten is fine
        tagged(DAY, "text", "a".repeat(NoteService.MAX_TAG_LENGTH), "वेतन", "ünï");        // the longest, and other scripts
        assertEquals(2, repository.count());
    }

    @Test
    void anEditReplacesTheTagsAndAFailedEditKeepsTheOldOnes() {
        Note note = tagged(DAY, "text", "old", "keep");

        service.update(note.getId(), new NoteData(DAY, null, "text", List.of("new")));
        assertEquals(List.of("new"), List.copyOf(service.get(note.getId()).getTags()));

        assertThrows(ValidationException.class, () -> service.update(note.getId(), new NoteData(DAY, null, "text", List.of("bad tag!"))));
        assertEquals(List.of("new"), List.copyOf(service.get(note.getId()).getTags()));
    }

    @Test
    void notesCanBeListedByTagAndByTagTogetherWithText() {
        tagged(DAY, "Premium paid", "insurance", "car");
        tagged(DAY.minusDays(3), "Renewal reminder", "insurance");
        tagged(DAY.minusDays(1), "Bought a laptop", "gadgets");

        List<Note> insurance = list(null, null, "Insurance");   // the filter is cleaned like a tag
        assertEquals(2, insurance.size());
        assertEquals(DAY, insurance.get(0).getDate());

        assertEquals(1, list(null, "renewal", "#insurance").size());
        assertEquals(1, list(null, "car", "insurance").size());        // text found in another tag of the same note
        assertTrue(list(null, "laptop", "insurance").isEmpty());
        assertTrue(list(null, null, "unused").isEmpty());
        List<Note> thatDay = list(DAY, null, "gadgets");   // asking for a day ignores the tag
        assertEquals(1, thatDay.size());
        assertEquals("Premium paid", thatDay.get(0).getBody());
    }

    @Test
    void searchAlsoLooksInTheTags() {
        tagged(DAY, "Nothing about it in the text", "holiday");
        add(DAY, null, "A plain note");

        assertEquals(1, list(null, "HOLI", null).size());
    }

    @Test
    void tagCountsAreListedMostUsedFirstAndGoWhenTheirNotesDo() {
        Note a = tagged(DAY, "a", "rent", "bills");
        tagged(DAY, "b", "rent");
        tagged(DAY, "c", "rent", "car");

        assertEquals(List.of(new TagCount("rent", 3), new TagCount("bills", 1), new TagCount("car", 1)), service.tags());

        service.delete(a.getId());
        assertEquals(List.of(new TagCount("rent", 2), new TagCount("car", 1)), service.tags());
    }

    @Test
    void tagsLeftOutAreWorkedOutFromTheTitleAndTextButAListIsKeptExactly() {
        Note auto = service.create(new NoteData(DAY, "Car service", "Took the car to Honda City workshop for the yearly service and tyres.", null));
        assertTrue(auto.getTags().containsAll(java.util.Set.of("car", "service", "honda-city")), auto.getTags().toString());
        assertTrue(auto.getTags().size() <= NoteService.SUGGESTED_TAGS, "at most four");

        assertEquals(java.util.Set.of("trip"), service.create(new NoteData(DAY, "Goa", "Booked the flights", List.of("Trip"))).getTags());
        assertTrue(service.create(new NoteData(DAY, "Goa", "Booked the flights", List.of())).getTags().isEmpty(), "an empty list means none");
        assertTrue(service.create(new NoteData(DAY, null, "Booked")).getTags().isEmpty(), "the three-argument form is a note with no tags");
    }

    @Test
    void wordsAndPhrasesFromEarlierNoteTagsComeFirst() {
        service.create(new NoteData(DAY, "Insurance", "Premium due", List.of("insurance", "car-loan")));
        List<String> tags = service.suggestTags(null, "Spoke about the car loan and the insurance renewal with the bank");
        assertEquals(List.of("car-loan", "insurance"), tags.subList(0, 2), "a learned phrase, then a learned word");
    }

    @Test
    void aLongNoteIsOnlyTaggedWithWordsItUsesMoreThanOnce() {
        String filler = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda sigma omega ".repeat(6);
        List<String> tags = service.suggestTags(null, "garden " + filler + " garden mango");
        assertTrue(tags.contains("garden"), tags.toString());
        assertTrue(!tags.contains("mango"), "a word used once in a long note is not a tag: " + tags);
    }

    @Test
    void notesCanLiveInFoldersAndTheListNarrowsToOne() {
        var recipes = service.createFolder("  Recipes ");
        var study = service.createFolder("Study Materials");
        assertEquals("Recipes", recipes.getName());
        add(DAY, "Diary", "A day in the diary.");
        service.create(new NoteData(DAY, "Dal", "Soak the dal for an hour.", List.of("dal"), recipes.getId()));
        service.create(new NoteData(DAY.minusDays(3), "Spring", "Boot notes about dal prices.", List.of(), study.getId()));

        assertEquals(3, service.page(null, null, null, null, 0, 50).total());
        assertEquals(1, service.page(null, null, null, "daily", 0, 50).total());
        assertEquals("Diary", service.page(null, null, null, "daily", 0, 50).notes().get(0).getTitle());
        assertEquals(1, service.page(null, null, null, String.valueOf(recipes.getId()), 0, 50).total());
        assertEquals(1, service.page(null, "dal", null, String.valueOf(recipes.getId()), 0, 50).total(), "text inside a folder");
        assertEquals(2, service.page(null, "dal", null, null, 0, 50).total(), "text in every folder");
        assertEquals(1, service.page(null, null, "dal", String.valueOf(recipes.getId()), 0, 50).total(), "a tag inside a folder");
        assertEquals(0, service.page(null, null, "dal", String.valueOf(study.getId()), 0, 50).total());
        assertEquals(2, service.page(DAY, null, null, null, 0, 50).total(), "a day lists every folder's notes, like the calendar");
        assertEquals(1, service.page(DAY, null, null, "daily", 0, 50).total());

        var counts = service.folders();
        assertEquals(1, counts.daily());
        assertEquals(List.of("Recipes", "Study Materials"), counts.folders().stream().map(NoteService.FolderView::name).toList());
        assertEquals(List.of(1L, 1L), counts.folders().stream().map(NoteService.FolderView::count).toList());
    }

    @Test
    void aFolderNeedsAUniqueNameAndOnlyAnEmptyOneIsDeleted() {
        var recipes = service.createFolder("Recipes");
        assertThrows(ValidationException.class, () -> service.createFolder("recipes"));
        assertThrows(ValidationException.class, () -> service.createFolder("  "));
        assertThrows(ValidationException.class, () -> service.createFolder("daily calendar notes"));
        assertThrows(ValidationException.class, () -> service.createFolder("x".repeat(61)));
        assertEquals("Recipes", service.renameFolder(recipes.getId(), "Recipes").getName(), "keeping its own name is fine");
        assertEquals("Food", service.renameFolder(recipes.getId(), "Food").getName());

        var note = service.create(new NoteData(DAY, null, "Pasta", List.of(), recipes.getId()));
        assertThrows(ValidationException.class, () -> service.deleteFolder(recipes.getId()));
        service.update(note.getId(), new NoteData(DAY, null, "Pasta", List.of(), null));   // moved back to the diary
        service.deleteFolder(recipes.getId());
        assertTrue(service.folders().folders().isEmpty());
        assertThrows(NotFoundException.class, () -> service.deleteFolder(recipes.getId()));
        assertThrows(ValidationException.class, () -> service.create(new NoteData(DAY, null, "x", List.of(), 999L)));   // no such folder
    }

    @Test
    void aDailyCalendarNoteIsStoredSealedAndReadBackWithThePassword() {
        DiaryVault.bind("correct horse");
        var diary = service.create(new NoteData(DAY, "Secret title", "Met someone I should not name.", null));
        var recipe = service.create(new NoteData(DAY, "Dal", "Soak the dal.", List.of("dal"), service.createFolder("Recipes").getId()));

        assertEquals("Met someone I should not name.", diary.getBody(), "the caller gets the text back");
        Note stored = repository.findById(diary.getId()).orElseThrow();
        assertTrue(stored.isSealed());
        assertFalse(stored.getBody().contains("someone"), "the database holds no readable text");
        assertNull(stored.getTitle());
        assertTrue(stored.getTags().isEmpty(), "the tags are sealed too");
        Note plain = repository.findById(recipe.getId()).orElseThrow();
        assertFalse(plain.isSealed(), "a folder note is ordinary");
        assertEquals("Soak the dal.", plain.getBody());

        var listed = service.page(null, "someone", null, 0, 10).notes();
        assertEquals(1, listed.size(), "search works on the opened text");
        assertEquals("Secret title", listed.get(0).getTitle());
        assertTrue(listed.get(0).getTags().size() > 0 || listed.get(0).getTags().isEmpty());
        assertEquals(1, service.page(null, null, "dal", 0, 10).total());
    }

    @Test
    void theWrongPasswordSeesLockedNotesAndCannotChangeThem() {
        DiaryVault.bind("correct horse");
        var diary = service.create(new NoteData(DAY, "Title", "Private text", List.of("mine")));
        DiaryVault.bind("another password");

        var listed = service.page(null, null, null, 0, 10).notes();
        assertEquals(1, listed.size());
        assertTrue(listed.get(0).isLocked());
        assertEquals("", listed.get(0).getBody());
        assertEquals(0, service.page(null, "private", null, 0, 10).total(), "nothing readable to find");
        assertThrows(ValidationException.class, () -> service.update(diary.getId(), new NoteData(DAY, null, "overwrite", List.of())));
        assertThrows(ValidationException.class, () -> service.create(new NoteData(DAY, null, "new", List.of())));

        DiaryVault.bind("correct horse");
        assertEquals("Private text", service.get(diary.getId()).getBody(), "the notes are still there");
    }

    @Test
    void changingThePasswordKeepsTheNotesReadable() {
        DiaryVault.bind("first password");
        var diary = service.create(new NoteData(DAY, null, "Keep me", List.of()));

        vault.passwordChanged("first password", "second password");

        DiaryVault.bind("second password");
        assertEquals("Keep me", service.get(diary.getId()).getBody());
        DiaryVault.bind("first password");
        assertTrue(service.get(diary.getId()).isLocked(), "the old password no longer opens it");
    }

    @Test
    void notesWrittenBeforeAPasswordExistedAreSealedOnceAKeyIsThere() {
        var early = service.create(new NoteData(DAY, "Early", "Written with no password yet.", List.of("old")));
        assertFalse(repository.findById(early.getId()).orElseThrow().isSealed());

        DiaryVault.bind("a new password");
        assertEquals("Written with no password yet.", service.page(null, null, null, 0, 10).notes().get(0).getBody());

        Note stored = repository.findById(early.getId()).orElseThrow();
        assertTrue(stored.isSealed());
        assertFalse(stored.getBody().contains("password yet"));
    }

    @Test
    void movingANoteBetweenTheDiaryAndAFolderSealsOrOpensIt() {
        DiaryVault.bind("pw for moving");
        var folder = service.createFolder("Ideas");
        var note = service.create(new NoteData(DAY, "Plan", "Plain plan", List.of()));
        assertTrue(repository.findById(note.getId()).orElseThrow().isSealed());

        service.update(note.getId(), new NoteData(DAY, "Plan", "Plain plan", List.of(), folder.getId()));
        Note inFolder = repository.findById(note.getId()).orElseThrow();
        assertFalse(inFolder.isSealed());
        assertEquals("Plain plan", inFolder.getBody());

        service.update(note.getId(), new NoteData(DAY, "Plan", "Plain plan", List.of(), null));
        assertTrue(repository.findById(note.getId()).orElseThrow().isSealed());
    }
}
