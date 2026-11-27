package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Note;
import com.learning.spring.financialdashboard.model.NoteFolder;
import com.learning.spring.financialdashboard.repository.NoteFolderRepository;
import com.learning.spring.financialdashboard.repository.NoteRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The diary: any number of notes for any day. */
@Service
@Transactional
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    public static final int MAX_BODY = 10000;
    public static final int MAX_TITLE = 200;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LENGTH = 30;

    /** tags: left out (null) = worked out from the title and text; a list = exactly those; an empty list = none. */
    public record NoteData(LocalDate date, String title, String body, List<String> tags, Long folderId) {

        /** A Daily Calendar Note (no folder). */
        public NoteData(LocalDate date, String title, String body, List<String> tags) {
            this(date, title, body, tags, null);
        }

        /** A Daily Calendar Note with no tags. */
        public NoteData(LocalDate date, String title, String body) {
            this(date, title, body, List.of(), null);
        }
    }

    /** A folder with how many notes it holds. */
    public record FolderView(Long id, String name, long count) {
    }

    /** The folders for the Notes view: how many Daily Calendar Notes there are (no folder) and each folder. */
    public record Folders(long daily, List<FolderView> folders) {
    }

    public static final int MAX_FOLDER_NAME = 60;
    public static final String DAILY_NAME = "Daily Calendar Notes";

    /** How many notes carry a tag. */
    public record TagCount(String tag, long count) {
    }

    /** One page of notes, newest first, with how many there are in all. {@code page} counts from 0. */
    public record NotePage(List<Note> notes, long total, int page, int size, int pages) {
    }

    /** How many notes a day has. */
    public record DayCount(LocalDate date, long count) {
    }

    /** Tags worked out for a note: at most this many. */
    public static final int SUGGESTED_TAGS = 4;
    /** A note longer than this only gets tags from words it uses more than once. */
    static final int SHORT_NOTE = 300;

    private final NoteRepository repository;
    private final NoteFolderRepository folders;
    private final TransactionTagService tagService;
    private final DiaryVault vault;
    @PersistenceContext
    private EntityManager em;

    public NoteService(NoteRepository repository, NoteFolderRepository folders, TransactionTagService tagService, DiaryVault vault) {
        this.repository = repository;
        this.folders = folders;
        this.tagService = tagService;
        this.vault = vault;
    }

    @Transactional(readOnly = true)
    public Folders folders() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : repository.countsByFolder()) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return new Folders(repository.countByFolderIdIsNull(),
                folders.findAllByOrderByNameAsc().stream().map(f -> new FolderView(f.getId(), f.getName(), counts.getOrDefault(f.getId(), 0L))).toList());
    }

    public NoteFolder createFolder(String name) {
        NoteFolder saved = folders.save(new NoteFolder(cleanFolderName(name, null)));
        log.info("Note folder added: id={}", saved.getId());   // never the name
        return saved;
    }

    public NoteFolder renameFolder(Long id, String name) {
        NoteFolder folder = folder(id);
        folder.setName(cleanFolderName(name, id));
        log.info("Note folder renamed: id={}", id);
        return folders.save(folder);
    }

    /** Only an empty folder: its notes are moved or deleted first, so nothing is lost by accident. */
    public void deleteFolder(Long id) {
        NoteFolder folder = folder(id);
        if (repository.countByFolderId(id) > 0) {
            throw new ValidationException("The folder \"" + folder.getName() + "\" still has notes. Move or delete them first");
        }
        folders.delete(folder);
        log.info("Note folder deleted: id={}", id);
    }

    private NoteFolder folder(Long id) {
        return folders.findById(id == null ? -1L : id).orElseThrow(() -> new NotFoundException("Folder " + id + " not found"));
    }

    private String cleanFolderName(String raw, Long ownId) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ValidationException("Give the folder a name");
        }
        if (name.length() > MAX_FOLDER_NAME) {
            throw new ValidationException("A folder name can be at most " + MAX_FOLDER_NAME + " characters");
        }
        if (name.equalsIgnoreCase(DAILY_NAME) || folders.findAll().stream().anyMatch(f -> f.getName().equalsIgnoreCase(name) && !f.getId().equals(ownId))) {
            throw new ValidationException("There is already a folder called \"" + name + "\"");
        }
        return name;
    }

    /**
     * The tags a note with this title and text would get: the meaningful words of the title first, then of the text (names with
     * capitals, phrases and words used in earlier notes' tags first; in a long text only words used more than once), at most
     * {@value #SUGGESTED_TAGS}. Nothing is saved.
     */
    @Transactional
    public List<String> suggestTags(String title, String body) {
        Map<String, Integer> vocabulary = new HashMap<>();
        for (TagCount count : tags()) {
            vocabulary.put(count.tag(), (int) count.count());
        }
        String text = body == null ? "" : body.strip();
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : tagService.keywords(title == null ? "" : title, vocabulary, 1)) {
            tags.add(tag);
        }
        for (String tag : tagService.keywords(text, vocabulary, text.length() < SHORT_NOTE ? 1 : 2)) {
            tags.add(tag);
        }
        return tags.stream().limit(SUGGESTED_TAGS).toList();
    }

    public Note create(NoteData data) {
        Note note = new Note();
        Content content = apply(note, data);
        Note saved = repository.saveAndFlush(note);
        log.info("Note added: id={} date={}", saved.getId(), saved.getDate());   // never the text
        return shown(saved, content);
    }

    public Note update(Long id, NoteData data) {
        Note note = repository.findById(id == null ? -1L : id).orElseThrow(() -> new NotFoundException("Note " + id + " not found"));
        if (note.isSealed() && key().isEmpty()) {
            throw lockedDiary();   // it cannot be read, so it must not be overwritten
        }
        Content content = apply(note, data);
        note.setUpdatedAt(Instant.now());
        Note saved = repository.saveAndFlush(note);
        log.info("Note updated: id={} date={}", id, saved.getDate());
        return shown(saved, content);
    }

    public void delete(Long id) {
        repository.delete(repository.findById(id == null ? -1L : id).orElseThrow(() -> new NotFoundException("Note " + id + " not found")));
        log.info("Note deleted: id={}", id);
    }

    @Transactional(readOnly = true)
    public Note get(Long id) {
        return open(repository.findById(id == null ? -1L : id)
                .orElseThrow(() -> new NotFoundException("Note " + id + " not found")));
    }

    /**
     * Newest day first, one page at a time. {@code date} gives that day's notes in a single page. Otherwise {@code tag}
     * keeps the notes with that tag and {@code text} (matched in the title, the body and the tags, ignoring capitals)
     * narrows them further. {@code page} counts from 0; {@code size} is kept between 1 and {@value #MAX_PAGE_SIZE}
     * (a missing or invalid size means {@value #DEFAULT_PAGE_SIZE}).
     */
    @Transactional
    public NotePage page(LocalDate date, String text, String tag, Integer page, Integer size) {
        return page(date, text, tag, null, page, size);
    }

    /** As above, narrowed to a folder: null or "" = every note, "daily" = the Daily Calendar Notes (no folder), otherwise a folder id. */
    @Transactional
    public NotePage page(LocalDate date, String text, String tag, String folder, Integer page, Integer size) {
        long inFolder = folderChoice(folder);
        List<Note> notes = new ArrayList<>();
        for (Note n : everything()) {
            boolean inside = inFolder == 0 || (inFolder == -1 ? n.getFolderId() == null
                    : inFolder == -2 ? n.getFolderId() != null : n.getFolderId() != null && n.getFolderId() == inFolder);
            if (inside && (date == null || date.equals(n.getDate()))) {
                notes.add(n);
            }
        }
        if (date != null) {
            notes.sort((a, b) -> Long.compare(a.getId(), b.getId()));
            return new NotePage(notes, notes.size(), 0, Math.max(notes.size(), 1), 1);
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasTag = tag != null && !tag.isBlank();
        String needle = hasText ? text.trim().toLowerCase(Locale.ROOT) : "";
        String wanted = hasTag ? cleanTag(tag) : "";
        List<Note> found = notes.stream()
                .filter(n -> !hasTag || n.getTags().contains(wanted))
                .filter(n -> !hasText || matches(n, needle))
                .toList();
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pages = Math.max((found.size() + pageSize - 1) / pageSize, 1);
        int number = Math.min(Math.max(page == null ? 0 : page, 0), Integer.MAX_VALUE);
        int from = (int) Math.min((long) number * pageSize, found.size());
        List<Note> slice = found.subList(from, Math.min(from + pageSize, found.size()));
        return new NotePage(new ArrayList<>(slice), found.size(), number, pageSize, found.isEmpty() ? 0 : pages);
    }

    /** Title, text or any tag contains the text (already lower case). A locked note has nothing readable to match. */
    private static boolean matches(Note n, String needle) {
        return !n.isLocked() && ((n.getBody() != null && n.getBody().toLowerCase(Locale.ROOT).contains(needle))
                || (n.getTitle() != null && n.getTitle().toLowerCase(Locale.ROOT).contains(needle))
                || n.getTags().stream().anyMatch(t -> t.contains(needle)));
    }

    private long folderChoice(String folder) {
        if (folder == null || folder.isBlank()) {
            return 0;
        }
        if (folder.equals("daily")) {
            return -1;
        }
        if (folder.equals("folders")) {
            return -2;   // every note that is in a folder, so none of the Daily Calendar Notes
        }
        try {
            long id = Long.parseLong(folder.trim());
            folder(id);
            return id;
        } catch (NumberFormatException e) {
            throw new ValidationException("Unknown folder");
        }
    }

    /** Every tag in use with its note count, most used first, for the tag bar and the suggestions. */
    @Transactional
    public List<TagCount> tags() {
        return tags(null);
    }

    /** The tags of the notes in a folder choice (as for {@link #page}): the screen hides the diary's, so it asks for "folders" until the diary is revealed. */
    @Transactional
    public List<TagCount> tags(String folder) {
        long inFolder = folderChoice(folder);
        Map<String, Long> counts = new HashMap<>();
        for (Note n : everything()) {
            boolean inside = inFolder == 0 || (inFolder == -1 ? n.getFolderId() == null
                    : inFolder == -2 ? n.getFolderId() != null : n.getFolderId() != null && n.getFolderId() == inFolder);
            if (!inside) {
                continue;
            }
            for (String tag : n.getTags()) {
                counts.merge(tag, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()))
                .map(e -> new TagCount(e.getKey(), e.getValue())).toList();
    }

    /** The days that have notes, oldest first, for the marks on the calendar. */
    @Transactional(readOnly = true)
    public List<DayCount> days() {
        return repository.countsByDate().stream()
                .map(row -> new DayCount((LocalDate) row[0], ((Number) row[1]).longValue())).toList();
    }

    /** Every note, oldest first, for the Excel export. */
    @Transactional
    public List<Note> all() {
        sealPending();
        return repository.findAllByOrderByDateAscIdAsc().stream().map(this::open).toList();
    }

    /** Every note, newest day first, opened (a Daily Calendar Note is decrypted). */
    private List<Note> everything() {
        sealPending();
        return repository.findAllByOrderByDateDescIdDesc().stream().map(this::open).toList();
    }

    private Optional<SecretKey> key() {
        return vault.key();
    }

    private ValidationException lockedDiary() {
        return new ValidationException("The Daily Calendar Notes were sealed with an earlier password and cannot be opened with this one. "
                + "Set that password again to read or change them");
    }

    /** The text of a note as it is shown, kept apart from what is stored. */
    private record Content(String title, String body, Set<String> tags) {
    }

    /** What a saved note looks like to the caller: the plain text, not the sealed value. */
    private Note shown(Note saved, Content content) {
        if (!saved.isSealed()) {
            return saved;
        }
        em.detach(saved);   // from here on changes are not stored
        saved.setTitle(content.title());
        saved.setBody(content.body());
        saved.setTags(content.tags());
        return saved;
    }

    /** A sealed note opened with the key of this request (on a detached copy), or marked locked when the key does not open it. */
    private Note open(Note note) {
        if (!note.isSealed()) {
            return note;
        }
        em.detach(note);
        Optional<Content> content = key().flatMap(k -> vault.unseal(note.getBody(), k)).map(NoteService::unpack);
        if (content.isPresent()) {
            note.setTitle(content.get().title());
            note.setBody(content.get().body());
            note.setTags(content.get().tags());
        } else {
            note.setLocked(true);
            note.setTitle(null);
            note.setBody("");
            note.setTags(Set.of());
        }
        return note;
    }

    /** Seals the Daily Calendar Notes written before a key existed, as soon as a request carries one. */
    private void sealPending() {
        Optional<SecretKey> key = key();
        if (key.isEmpty()) {
            return;
        }
        for (Note n : repository.findAllByFolderIdIsNullAndSealedFalse()) {
            seal(n, new Content(n.getTitle(), n.getBody(), n.getTags()), key.get());
        }
    }

    private void seal(Note note, Content content, SecretKey key) {
        note.setBody(vault.seal(pack(content), key));
        note.setTitle(null);
        note.setTags(Set.of());
        note.setSealed(true);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    /** title.body.tags, each part in base64 (the tags joined by commas, which a tag cannot contain). */
    private static String pack(Content c) {
        return b64(c.title() == null ? "" : c.title()) + "." + b64(c.body()) + "." + b64(String.join(",", c.tags()));
    }

    private static Content unpack(String packed) {
        String[] parts = packed.split("\\.", -1);
        String title = unb64(parts[0]);
        String tags = unb64(parts[2]);
        return new Content(title.isEmpty() ? null : title, unb64(parts[1]),
                tags.isEmpty() ? new TreeSet<>() : new TreeSet<>(List.of(tags.split(","))));
    }

    private Content apply(Note note, NoteData data) {
        if (data.date() == null) {
            throw new ValidationException("Choose the day this note is for");
        }
        String body = data.body() == null ? "" : data.body().strip();
        if (body.isEmpty()) {
            throw new ValidationException("Write something in the note");
        }
        if (body.length() > MAX_BODY) {
            throw new ValidationException("A note can be at most " + MAX_BODY + " characters (this one has " + body.length() + ")");
        }
        String title = data.title() == null ? "" : data.title().strip();
        if (title.length() > MAX_TITLE) {
            throw new ValidationException("The title can be at most " + MAX_TITLE + " characters");
        }
        Set<String> tags = new LinkedHashSet<>();
        if (data.tags() == null) {
            tags.addAll(suggestTags(title, body));   // tags left out of the request: worked out from the title and text
        } else {
            data.tags().stream().filter(t -> t != null && !t.isBlank()).forEach(t -> tags.add(cleanTag(t)));
        }
        if (tags.size() > MAX_TAGS) {
            throw new ValidationException("A note can have at most " + MAX_TAGS + " tags");
        }
        if (data.folderId() != null && !folders.existsById(data.folderId())) {
            throw new ValidationException("That folder does not exist");
        }
        note.setFolderId(data.folderId());
        note.setDate(data.date());
        Content content = new Content(title.isEmpty() ? null : title, body, tags);
        Optional<SecretKey> key = data.folderId() == null ? key() : Optional.empty();
        if (data.folderId() == null && key.isEmpty() && DiaryVault.hasLogin() && vault.exists()) {
            throw lockedDiary();   // a login was sent but it does not open the diary: do not store a diary note in the clear
        }
        if (key.isPresent()) {
            seal(note, content, key.get());   // a Daily Calendar Note is stored sealed
        } else {
            note.setSealed(false);
            note.setTitle(content.title());
            note.setBody(body);
            note.setTags(tags);
        }
        return content;
    }

    /**
     * One tag in its stored form: lower case, a leading # dropped, spaces turned into hyphens. Only letters, numbers,
     * hyphens and underscores are allowed, at most {@value #MAX_TAG_LENGTH} characters.
     */
    static String cleanTag(String raw) {
        String tag = raw.strip().toLowerCase(Locale.ROOT);
        while (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        tag = tag.strip().replaceAll("\\s+", "-");
        if (tag.isEmpty()) {
            throw new ValidationException("A tag cannot be empty");
        }
        if (tag.length() > MAX_TAG_LENGTH) {
            throw new ValidationException("A tag can be at most " + MAX_TAG_LENGTH + " characters");
        }
        if (!tag.matches("[\\p{L}\\p{M}\\p{N}_-]+")) {
            throw new ValidationException("A tag can only have letters, numbers, - and _ (\"" + raw.strip() + "\" has something else)");
        }
        return tag;
    }
}
