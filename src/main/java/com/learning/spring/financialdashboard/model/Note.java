package com.learning.spring.financialdashboard.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** A diary entry for one day. A day can have any number of them. */
@Entity
@Table(name = "note")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    /** Optional short heading. */
    @Column(length = 200)
    private String title;

    @Column(nullable = false, length = 70000)
    private String body;

    /** True for a Daily Calendar Note that is stored sealed: body then holds the encrypted title, text and tags, and title and tags are empty. */
    @Column(nullable = false)
    private boolean sealed;

    /** Set on the copy handed out when a sealed note could not be opened (the password does not open the diary); never stored. */
    @Transient
    private boolean locked;

    /** Short lower-case labels, kept in the note_tag table. NoteService cleans them before they get here. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "note_tag", joinColumns = @JoinColumn(name = "note_id"))
    @Column(name = "tag", length = 30, nullable = false)
    @Fetch(FetchMode.SUBSELECT)
    private Set<String> tags = new HashSet<>();

    /** The folder it is in, or null for a Daily Calendar Note. */
    @Column(name = "folder_id")
    private Long folderId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

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

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    /** The tags in alphabetical order. */
    public Set<String> getTags() {
        return new TreeSet<>(tags);
    }

    public void setTags(Collection<String> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    @JsonIgnore
    public boolean isSealed() {
        return sealed;
    }

    public void setSealed(boolean sealed) {
        this.sealed = sealed;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Long getFolderId() {
        return folderId;
    }

    public void setFolderId(Long folderId) {
        this.folderId = folderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
