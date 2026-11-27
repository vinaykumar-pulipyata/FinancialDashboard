package com.learning.spring.financialdashboard.controller;

import com.learning.spring.financialdashboard.model.Note;
import com.learning.spring.financialdashboard.model.NoteFolder;
import com.learning.spring.financialdashboard.service.NoteService;
import com.learning.spring.financialdashboard.service.NoteService.DayCount;
import com.learning.spring.financialdashboard.service.NoteService.NoteData;
import com.learning.spring.financialdashboard.service.NoteService.NotePage;
import com.learning.spring.financialdashboard.service.NoteService.TagCount;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    /**
     * One page of notes, newest first (see {@link NoteService#page}). {@code date} gives one day's notes; {@code tag}
     * the notes with that tag; {@code q} searches the titles, text and tags (inside the tag's notes when both are given);
     * {@code page} (from 0) and {@code size} choose the page.
     */
    @GetMapping
    public NotePage list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) String tag,
                         @RequestParam(required = false) String folder,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        return service.page(date, q, tag, folder, page, size);
    }

    /** The Daily Calendar Notes count and each folder with its count. */
    @GetMapping("/folders")
    public NoteService.Folders folders() {
        return service.folders();
    }

    public record FolderRequest(String name) {
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteFolder createFolder(@RequestBody FolderRequest request) {
        return service.createFolder(request.name());
    }

    @PutMapping("/folders/{id}")
    public NoteFolder renameFolder(@PathVariable Long id, @RequestBody FolderRequest request) {
        return service.renameFolder(id, request.name());
    }

    @DeleteMapping("/folders/{id}")
    public void deleteFolder(@PathVariable Long id) {
        service.deleteFolder(id);
    }

    @GetMapping("/tags")
    public List<TagCount> tags(@RequestParam(required = false) String folder) {
        return service.tags(folder);
    }

    @GetMapping("/days")
    public List<DayCount> days() {
        return service.days();
    }

    /** The tags a note with this title and text would get; nothing is saved. */
    public record TagRequest(String title, String body) {
    }

    @PostMapping("/suggest-tags")
    public List<String> suggestTags(@RequestBody TagRequest request) {
        return service.suggestTags(request.title(), request.body());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Note create(@RequestBody NoteData data) {
        return service.create(data);
    }

    @PutMapping("/{id}")
    public Note update(@PathVariable Long id, @RequestBody NoteData data) {
        return service.update(id, data);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
