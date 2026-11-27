package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Reminder;
import com.learning.spring.financialdashboard.model.Repeat;
import com.learning.spring.financialdashboard.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Dated reminders of the user's own (bill reminders for cards and the Ledger are worked out in the browser). */
@Service
@Transactional
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    public static final int MAX_TITLE = 200;

    /** {@code repeat} may be left out (null): the reminder then does not repeat. */
    public record ReminderData(LocalDate date, String title, Repeat repeat) {

        /** An ordinary reminder that does not repeat. */
        public ReminderData(LocalDate date, String title) {
            this(date, title, Repeat.NONE);
        }
    }

    private final ReminderRepository repository;

    public ReminderService(ReminderRepository repository) {
        this.repository = repository;
    }

    /** Every reminder, oldest date first. */
    @Transactional(readOnly = true)
    public List<Reminder> all() {
        return repository.findAllByOrderByDateAscIdAsc();
    }

    public Reminder create(ReminderData data) {
        Reminder reminder = new Reminder();
        apply(reminder, data);
        Reminder saved = repository.save(reminder);
        log.info("Reminder added: id={} date={}", saved.getId(), saved.getDate());   // never the title
        return saved;
    }

    public Reminder update(Long id, ReminderData data) {
        Reminder reminder = get(id);
        apply(reminder, data);
        log.info("Reminder updated: id={} date={}", id, reminder.getDate());
        return repository.save(reminder);
    }

    /** Ticks or unticks a reminder. Ticking records today as the day it was done. */
    public Reminder setDone(Long id, boolean done) {
        return setDone(id, done, null);
    }

    /** As above, but a past day can be given as the day it was done (the demo data spreads them over the years); it is ignored when unticking. */
    public Reminder setDone(Long id, boolean done, LocalDate doneOn) {
        Reminder reminder = get(id);
        if (done && doneOn != null && doneOn.isAfter(LocalDate.now())) {
            throw new ValidationException("A reminder cannot have been done in the future");
        }
        if (done && reminder.getRepeat() != Repeat.NONE) {
            throw new ValidationException("A repeating reminder is never done: it comes back every time");
        }
        reminder.setDone(done);
        reminder.setDoneOn(!done ? null : doneOn != null ? doneOn : LocalDate.now());
        log.info("Reminder {}: id={} date={}", done ? "done" : "reopened", id, reminder.getDate());
        return repository.save(reminder);
    }

    /**
     * Ticks or unticks one occurrence of a repeating reminder (the reminder itself is never done). The day must be one the
     * reminder falls on; {@code doneOn} is the day it was ticked (today when left out, never in the future).
     */
    public Reminder setOccurrenceDone(Long id, LocalDate occurrence, boolean done, LocalDate doneOn) {
        Reminder reminder = get(id);
        if (reminder.getRepeat() == Repeat.NONE) {
            throw new ValidationException("Only a repeating reminder has occurrences to tick");
        }
        if (occurrence == null || !fallsOn(reminder, occurrence)) {
            throw new ValidationException("This reminder does not fall on that day");
        }
        if (done) {
            if (doneOn != null && doneOn.isAfter(LocalDate.now())) {
                throw new ValidationException("A reminder cannot have been done in the future");
            }
            reminder.getCompletions().put(occurrence, doneOn != null ? doneOn : LocalDate.now());
        } else {
            reminder.getCompletions().remove(occurrence);
        }
        log.info("Reminder occurrence {}: id={} date={}", done ? "done" : "reopened", id, occurrence);
        return repository.save(reminder);
    }

    /** Does a repeating reminder fall on this day? The same rules as the page: a short month uses its last day, 29 February falls on the 28th in other years. */
    static boolean fallsOn(Reminder r, LocalDate day) {
        if (day.isBefore(r.getDate())) {
            return false;
        }
        LocalDate first = r.getDate();
        return switch (r.getRepeat()) {
            case NONE -> day.equals(first);
            case WEEKLY -> java.time.temporal.ChronoUnit.DAYS.between(first, day) % 7 == 0;
            case MONTHLY -> day.getDayOfMonth() == Math.min(first.getDayOfMonth(), day.lengthOfMonth());
            case YEARLY -> day.getMonth() == first.getMonth() && day.getDayOfMonth() == Math.min(first.getDayOfMonth(), day.lengthOfMonth());
        };
    }

    public void delete(Long id) {
        repository.delete(get(id));
        log.info("Reminder deleted: id={}", id);
    }

    private Reminder get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Reminder " + id + " not found"));
    }

    private void apply(Reminder reminder, ReminderData data) {
        if (data.date() == null) {
            throw new ValidationException("Choose the day this reminder is for");
        }
        String title = data.title() == null ? "" : data.title().strip();
        if (title.isEmpty()) {
            throw new ValidationException("Write what to remember");
        }
        if (title.length() > MAX_TITLE) {
            throw new ValidationException("A reminder can be at most " + MAX_TITLE + " characters (this one has " + title.length() + ")");
        }
        Repeat repeat = data.repeat() == null ? Repeat.NONE : data.repeat();
        if (!data.date().equals(reminder.getDate()) || repeat != reminder.getRepeat()) {
            reminder.getCompletions().clear();     // a new schedule has different occurrences
        }
        reminder.setDate(data.date());
        reminder.setTitle(title);
        reminder.setRepeat(repeat);
        if (repeat != Repeat.NONE) {          // it has no done state
            reminder.setDone(false);
            reminder.setDoneOn(null);
        }
    }
}
