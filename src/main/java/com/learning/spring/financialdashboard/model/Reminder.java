package com.learning.spring.financialdashboard.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** Something to remember on a day: a title, the date it is for, and whether it has been done. */
@Entity
@Table(name = "reminder")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private boolean done;

    /** How often it comes back (a birthday, rent). A repeating reminder is never ticked or late; date is its first day. */
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_every", nullable = false, length = 10, columnDefinition = "varchar(10) default 'NONE'")
    private Repeat repeat = Repeat.NONE;

    /** The day it was ticked; null while it is open. */
    private LocalDate doneOn;

    /** For a repeating reminder: the occurrences that have been ticked, occurrence day -> the day it was ticked (table reminder_completion). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reminder_completion", joinColumns = @JoinColumn(name = "reminder_id"))
    @MapKeyColumn(name = "occurrence_date")
    @Column(name = "done_on", nullable = false)
    @Fetch(FetchMode.SUBSELECT)
    private Map<LocalDate, LocalDate> completions = new TreeMap<>();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Map<LocalDate, LocalDate> getCompletions() {
        return completions;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Repeat getRepeat() {
        return repeat;
    }

    public void setRepeat(Repeat repeat) {
        this.repeat = repeat;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public LocalDate getDoneOn() {
        return doneOn;
    }

    public void setDoneOn(LocalDate doneOn) {
        this.doneOn = doneOn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
