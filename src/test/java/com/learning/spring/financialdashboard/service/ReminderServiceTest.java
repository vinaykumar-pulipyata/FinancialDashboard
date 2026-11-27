package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Reminder;
import com.learning.spring.financialdashboard.model.Repeat;
import com.learning.spring.financialdashboard.repository.ReminderRepository;
import com.learning.spring.financialdashboard.service.ReminderService.ReminderData;
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

/** Dated reminders: added, edited, moved, ticked, unticked, deleted. */
@SpringBootTest
class ReminderServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @Autowired
    ReminderService service;
    @Autowired
    ReminderRepository repository;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void aReminderIsSavedOpenAndTrimmed() {
        Reminder saved = service.create(new ReminderData(DAY, "  Renew car insurance  "));
        assertNotNull(saved.getId());
        assertEquals("Renew car insurance", saved.getTitle());
        assertEquals(DAY, saved.getDate());
        assertFalse(saved.isDone());
        assertNull(saved.getDoneOn());
    }

    @Test
    void theListIsOldestDateFirstAndThenInTheOrderAdded() {
        service.create(new ReminderData(DAY, "second day, first added"));
        service.create(new ReminderData(DAY.minusDays(3), "earlier"));
        service.create(new ReminderData(DAY, "second day, second added"));
        assertEquals(List.of("earlier", "second day, first added", "second day, second added"),
                service.all().stream().map(Reminder::getTitle).toList());
    }

    @Test
    void editingChangesTheTitleAndMovingChangesTheDate() {
        Reminder r = service.create(new ReminderData(DAY, "Call the CA"));
        Reminder moved = service.update(r.getId(), new ReminderData(DAY.plusDays(1), "Call the CA about GST"));
        assertEquals(DAY.plusDays(1), moved.getDate());
        assertEquals("Call the CA about GST", service.all().get(0).getTitle());
        assertEquals(1, service.all().size());
    }

    @Test
    void aPastDoneDayCanBeGivenButNotAFutureOne() {
        Reminder r = service.create(new ReminderData(DAY, "Pay the milk bill"));
        LocalDate past = LocalDate.now().minusDays(400);
        assertEquals(past, service.setDone(r.getId(), true, past).getDoneOn());
        assertThrows(ValidationException.class, () -> service.setDone(r.getId(), true, LocalDate.now().plusDays(1)));
        assertNull(service.setDone(r.getId(), false, past).getDoneOn(), "unticking clears it whatever is given");
    }

    @Test
    void tickingRecordsTodayAndUntickingClearsIt() {
        Reminder r = service.create(new ReminderData(DAY, "Pay the milk bill"));
        Reminder done = service.setDone(r.getId(), true);
        assertTrue(done.isDone());
        assertEquals(LocalDate.now(), done.getDoneOn());
        assertEquals(DAY, done.getDate(), "ticking does not move it");
        Reminder open = service.setDone(r.getId(), false);
        assertFalse(open.isDone());
        assertNull(open.getDoneOn());
    }

    @Test
    void aDayOfARepeatingReminderCanBeTickedAndUntickedAndOnlyOnADayItFallsOn() {
        Reminder rent = service.create(new ReminderData(LocalDate.of(2026, 1, 31), "Pay the rent", Repeat.MONTHLY));
        LocalDate february = LocalDate.of(2026, 2, 28);     // a short month uses its last day
        Reminder ticked = service.setOccurrenceDone(rent.getId(), february, true, null);
        assertEquals(LocalDate.now(), ticked.getCompletions().get(february));
        assertFalse(ticked.isDone(), "the reminder as a whole is never done");
        assertEquals(1, repository.findById(rent.getId()).orElseThrow().getCompletions().size(), "it is saved");
        assertThrows(ValidationException.class, () -> service.setOccurrenceDone(rent.getId(), LocalDate.of(2026, 2, 27), true, null), "not a day it falls on");
        assertThrows(ValidationException.class, () -> service.setOccurrenceDone(rent.getId(), LocalDate.of(2025, 12, 31), true, null), "before its first day");
        assertThrows(ValidationException.class, () -> service.setOccurrenceDone(rent.getId(), february, true, LocalDate.now().plusDays(1)), "not done in the future");
        assertTrue(service.setOccurrenceDone(rent.getId(), february, false, null).getCompletions().isEmpty());
        Reminder once = service.create(new ReminderData(DAY, "One-off"));
        assertThrows(ValidationException.class, () -> service.setOccurrenceDone(once.getId(), DAY, true, null), "only a repeating one has days");
    }

    @Test
    void ticksAreDroppedWhenTheScheduleChangesAndWhenTheReminderIsDeleted() {
        Reminder weekly = service.create(new ReminderData(LocalDate.of(2026, 1, 5), "Plan", Repeat.WEEKLY));
        service.setOccurrenceDone(weekly.getId(), LocalDate.of(2026, 1, 12), true, LocalDate.of(2026, 1, 12));
        service.update(weekly.getId(), new ReminderData(LocalDate.of(2026, 1, 5), "Plan the week", Repeat.WEEKLY));
        assertEquals(1, service.all().get(0).getCompletions().size(), "a new title keeps them");
        service.update(weekly.getId(), new ReminderData(LocalDate.of(2026, 1, 6), "Plan the week", Repeat.WEEKLY));
        assertTrue(service.all().get(0).getCompletions().isEmpty(), "a new first day means different days");
        service.setOccurrenceDone(weekly.getId(), LocalDate.of(2026, 1, 13), true, null);
        service.delete(weekly.getId());
        assertTrue(service.all().isEmpty());
    }

    @Test
    void aRepeatingReminderIsSavedAsSuchAndCannotBeTicked() {
        for (Repeat repeat : List.of(Repeat.WEEKLY, Repeat.MONTHLY, Repeat.YEARLY)) {
            Reminder made = service.create(new ReminderData(LocalDate.of(1990, 3, 14), "Repeats " + repeat, repeat));
            assertEquals(repeat, made.getRepeat());
            assertEquals(LocalDate.of(1990, 3, 14), made.getDate(), "the date is the first one");
            ValidationException e = assertThrows(ValidationException.class, () -> service.setDone(made.getId(), true));
            assertTrue(e.getMessage().contains("comes back"), repeat.toString());
            assertFalse(repository.findById(made.getId()).orElseThrow().isDone());
            service.setDone(made.getId(), false);          // reopening is harmless
        }
    }

    @Test
    void anOrdinaryReminderDoesNotRepeatAndLeavingTheSettingOutMeansNone() {
        Reminder r = service.create(new ReminderData(DAY, "Insurance"));
        assertEquals(Repeat.NONE, r.getRepeat());
        assertEquals(Repeat.NONE, service.create(new ReminderData(DAY, "Null repeat", null)).getRepeat());
    }

    @Test
    void makingADoneReminderRepeatReopensItAndTurningRepeatOffMakesItOrdinaryAgain() {
        Reminder r = service.create(new ReminderData(DAY, "Rent"));
        service.setDone(r.getId(), true);
        Reminder monthly = service.update(r.getId(), new ReminderData(DAY, "Rent", Repeat.MONTHLY));
        assertEquals(Repeat.MONTHLY, monthly.getRepeat());
        assertFalse(monthly.isDone());
        assertNull(monthly.getDoneOn());
        Reminder back = service.update(r.getId(), new ReminderData(DAY, "Rent"));
        assertEquals(Repeat.NONE, back.getRepeat(), "leaving the setting out means it does not repeat");
        service.setDone(r.getId(), true);     // an ordinary reminder can be ticked again
    }

    @Test
    void aReminderNeedsADayAndSomethingToRemember() {
        assertThrows(ValidationException.class, () -> service.create(new ReminderData(null, "x")));
        assertThrows(ValidationException.class, () -> service.create(new ReminderData(DAY, null)));
        assertThrows(ValidationException.class, () -> service.create(new ReminderData(DAY, "   ")));
        assertEquals(0, service.all().size());
    }

    @Test
    void theTitleCanBeAtMostTwoHundredCharacters() {
        service.create(new ReminderData(DAY, "x".repeat(ReminderService.MAX_TITLE)));
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.create(new ReminderData(DAY, "x".repeat(ReminderService.MAX_TITLE + 1))));
        assertTrue(e.getMessage().contains("200"));
        assertEquals(1, service.all().size());
    }

    @Test
    void anInvalidEditLeavesTheReminderAsItWas() {
        Reminder r = service.create(new ReminderData(DAY, "Keep me"));
        assertThrows(ValidationException.class, () -> service.update(r.getId(), new ReminderData(DAY, " ")));
        assertEquals("Keep me", service.all().get(0).getTitle());
    }

    @Test
    void deletingRemovesItAndAnUnknownIdIsNotFound() {
        Reminder r = service.create(new ReminderData(DAY, "Delete me"));
        service.delete(r.getId());
        assertTrue(service.all().isEmpty());
        assertThrows(NotFoundException.class, () -> service.delete(r.getId()));
        assertThrows(NotFoundException.class, () -> service.update(999999L, new ReminderData(DAY, "x")));
        assertThrows(NotFoundException.class, () -> service.setDone(999999L, true));
    }
}
