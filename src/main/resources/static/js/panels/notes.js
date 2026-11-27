/* Notes: a diary with any number of notes for each day. The Notes view lists them newest first with a search box;
   the calendar marks days that have notes and lists a chosen day's notes under its transactions. */

import { clientLog, describeError } from "../core/client-log.js";
import { setNoteDays } from "../core/state.js";
import { $, api, el, isoDay } from "../core/util.js";
import { renderCalendar } from "./calendar.js";
import { dlg, ledgerFail, openLedgerDialog, runLedger } from "./ledger.js";
import { field } from "./transaction-form.js";
import { showWorkspaceView, wsView } from "./workspace.js";

const NOTE_MAX = 10000;
const NOTE_MAX_TAGS = 10;
const NOTE_TAG_MAX = 30;
let editingNote = null;   // the note being edited, or null for a new one
let noteSearchTimer = null;
let noteTagFilter = "";   // the tag the list is narrowed to, or ""
let noteFolder = "all";   // "all", "daily" (the Daily Calendar Notes: no folder) or a folder's id as text
let folderList = { daily: 0, folders: [] };   // as the server last said: the diary's count and each folder with its count
let notePage = 1;         // the page on show, counting from 1
let notePageSize = 10;
let formTags = [];        // the tags on the note being edited
let tagsChosen = false;   // true once a person has added or removed a tag (or pressed Suggest): suggestions then leave them alone
let suggestTimer = null, suggestAsk = 0;

/* The Daily Calendar Notes (no folder) are private: they show as dots until the password is typed again (Reveal), then for DIARY_REVEAL_MS or until
   Hide, the screen lock or a reload. This only hides them on the screen; on the server they are stored sealed (DiaryVault). */
const DIARY_REVEAL_MS = 5 * 60 * 1000;
const HIDDEN_TITLE = "••••••••";
const HIDDEN_BODY = "•••••••• ••••• •••••••• •••••";
let diaryRevealed = false;
let diaryWatch = null;     // checks every second whether the reveal has run out or the screen was locked
let diaryRevealedUntil = 0;

const isDiaryNote = note => note.folderId == null;
const diaryHidden = note => isDiaryNote(note) && !diaryRevealed;

function showDiary(on) {
    diaryRevealed = on;
    clearInterval(diaryWatch);
    diaryWatch = null;
    if (on) {
        diaryRevealedUntil = Date.now() + DIARY_REVEAL_MS;
        diaryWatch = setInterval(() => {
            const locked = $("lockOverlay") && !$("lockOverlay").classList.contains("hidden");
            if (Date.now() > diaryRevealedUntil || locked) hideDiary();
        }, 1000);
    }
}

/* Puts the diary back behind its dots and redraws what shows it. */
function hideDiary() {
    showDiary(false);
    redrawNotes();
}

function redrawNotes() {
    renderCalendar();
    if (wsView === "notes") loadNotes().catch(e => alert(e.message));
}

/* Asks for the password, and on the right one shows the diary. */
function askToReveal() {
    const f = {};
    openLedgerDialog("Show the Daily Calendar Notes", "Show", body => {
        f.password = el("input", { type: "password", value: "", autocomplete: "current-password" });
        body.append(el("div", { className: "edit-hint", textContent: "Your diary stays hidden until you type the password again. " +
            "It is hidden again after 5 minutes, when the screen locks or when you press Hide." }), field("Password", f.password));
        f.password.addEventListener("keydown", e => { if (e.key === "Enter") dlg.onPrimary(); });
        f.password.focus();
    });
    dlg.onPrimary = async () => {
        if (!f.password.value) return ledgerFail("Type your password.");
        const ok = await runLedger(() => api("/api/auth/verify", { method: "POST", body: { password: f.password.value } }));
        if (ok) {
            showDiary(true);
            redrawNotes();
        }
    };
}

/* A tag as the server stores it: lower case, no leading #, spaces as hyphens. */
function normalizeTag(raw) {
    return raw.trim().replace(/^#+/, "").trim().toLowerCase().replace(/\s+/g, "-");
}

/* The tags of a note as small pills. Clicking one shows every note with that tag. */
function tagPills(tags) {
    const row = el("div", { className: "tag-row" });
    for (const tag of tags) {
        const pill = el("span", { className: "tag-pill", role: "button", tabIndex: 0, textContent: "#" + tag, title: "Show the notes tagged " + tag });
        const open = e => { e.preventDefault(); e.stopPropagation(); showNotesTagged(tag); };
        pill.addEventListener("click", open);
        pill.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") open(e); });
        row.append(pill);
    }
    return row;
}

/* Opens the Notes view narrowed to one tag (from a pill on the calendar or on a card). */
function showNotesTagged(tag) {
    noteTagFilter = tag;
    notePage = 1;
    $("noteSearch").value = "";
    if (wsView === "notes") loadNotes().catch(e => alert(e.message));
    else showWorkspaceView("notes");   // loading the view loads the notes
}

/* The name of the folder a note is in, or "" for a Daily Calendar Note. */
function folderName(note) {
    const f = note.folderId == null ? null : folderList.folders.find(x => x.id === note.folderId);
    return f ? f.name : "";
}

/* One note as a card. Clicking it opens it for reading (its Edit button opens the form); a hidden diary note asks for the password first. */
export function noteCard(note) {
    const card = el("div", { className: "note-card", role: "button", tabIndex: 0 });
    const open = () => note.locked ? alert("This note was sealed with an earlier password. Set that password again to read it.")
        : diaryHidden(note) ? askToReveal() : readNote(note);
    if (folderName(note)) card.append(el("div", { className: "note-folder-tag", textContent: "📁 " + folderName(note) }));
    if (note.locked) {
        card.classList.add("note-hidden");
        card.append(el("div", { className: "note-body", textContent: "🔒 Sealed with an earlier password" }));
    } else if (diaryHidden(note)) {
        card.classList.add("note-hidden");
        card.append(el("div", { className: "note-title", textContent: HIDDEN_TITLE }), el("div", { className: "note-body", textContent: HIDDEN_BODY }));
        card.title = "Hidden: click to show with your password";
    } else {
        if (note.title) card.append(el("div", { className: "note-title", textContent: note.title }));
        card.append(el("div", { className: "note-body", textContent: note.body }));
        if (note.tags && note.tags.length) card.append(tagPills(note.tags));
    }
    card.addEventListener("click", open);
    card.addEventListener("keydown", e => { if (e.target === card && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); open(); } });
    return card;
}

/* "Monday, 12 October 2026" from yyyy-mm-dd. */
function longDay(iso) {
    return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
}

/* The row of tags above the list; the one the list is narrowed to is filled in, and clicking it again clears it. */
function renderTagBar(counts) {
    const bar = $("noteTags");
    bar.innerHTML = "";
    const shown = counts.slice();
    if (noteTagFilter && !shown.some(c => c.tag === noteTagFilter)) shown.unshift({ tag: noteTagFilter, count: 0 });
    for (const { tag, count } of shown) {
        const pill = el("button", { className: "tag-pill" + (tag === noteTagFilter ? " active" : ""), type: "button",
            title: tag === noteTagFilter ? "Show all notes again" : "Show only the notes tagged " + tag }, "#" + tag + " ",
            el("span", { className: "tag-count", textContent: count ? "(" + count + ")" : "✕" }));
        pill.addEventListener("click", () => {
            noteTagFilter = tag === noteTagFilter ? "" : tag;
            notePage = 1;
            loadNotes().catch(e => alert(e.message));
        });
        bar.append(pill);
    }
    fillTagSuggestions(counts);
}

/* The words offered while typing a tag in the note window. */
function fillTagSuggestions(counts) {
    const list = $("noteTagList");
    list.innerHTML = "";
    for (const { tag } of counts) list.append(el("option", { value: tag }));
}

/* The tags offered while the diary is hidden leave out the diary's own, so its tag names do not show either. */
function tagScope() {
    if (diaryRevealed) return noteFolder === "all" ? "" : "?folder=" + noteFolder;
    return noteFolder === "all" ? "?folder=folders" : noteFolder === "daily" ? "?folder=daily" : "?folder=" + noteFolder;
}

/* The row of folders above the tags: All notes, the Daily Calendar Notes, each folder, and + Folder. A chosen folder gets Rename and Delete. */
function renderFolderBar() {
    const bar = $("noteFolders");
    bar.innerHTML = "";
    const total = folderList.daily + folderList.folders.reduce((sum, f) => sum + f.count, 0);
    const choices = [["all", "All notes", total], ["daily", "Daily Calendar Notes", folderList.daily],
        ...folderList.folders.map(f => [String(f.id), "📁 " + f.name, f.count])];
    for (const [key, label, count] of choices) {
        const chip = el("button", { className: "chip" + (noteFolder === key ? " active" : ""), type: "button", textContent: `${label} (${count})` });
        chip.addEventListener("click", () => { noteFolder = key; notePage = 1; loadNotes().catch(e => alert(e.message)); });
        bar.append(chip);
    }
    const add = el("button", { className: "chip", type: "button", textContent: "+ Folder", title: "Make a folder for general notes, such as Recipes" });
    add.addEventListener("click", addFolder);
    bar.append(add);
    const eye = el("button", { className: "txn-link", type: "button",
        textContent: diaryRevealed ? "🔓 Hide the diary" : "🔒 Diary hidden · Show", title: diaryRevealed ? "Hide the Daily Calendar Notes again" : "Type your password to read the Daily Calendar Notes" });
    eye.addEventListener("click", () => diaryRevealed ? hideDiary() : askToReveal());
    bar.append(eye);
    const chosen = folderList.folders.find(f => String(f.id) === noteFolder);
    if (chosen) {
        const rename = el("button", { className: "txn-link", type: "button", textContent: "Rename folder" });
        const remove = el("button", { className: "txn-link", type: "button", textContent: "Delete folder", title: "Only an empty folder can be deleted" });
        rename.addEventListener("click", () => renameFolder(chosen));
        remove.addEventListener("click", () => deleteFolder(chosen));
        bar.append(rename, remove);
    }
}

/* Runs a folder change, then redraws the list; the server's refusal (a name in use, a folder with notes) is shown as it is. */
async function changeFolders(change) {
    try {
        await change();
        await loadNotes();
    } catch (e) {
        alert(e.message);
        clientLog("WARN", "note.folder.failed", describeError(e));
    }
}

function addFolder() {
    const name = prompt("Name of the new folder (for example Recipes or Study Materials):");
    if (name && name.trim()) changeFolders(async () => {
        const made = await api("/api/notes/folders", { method: "POST", body: { name } });
        noteFolder = String(made.id);
        notePage = 1;
    });
}

function renameFolder(folder) {
    const name = prompt("New name for this folder:", folder.name);
    if (name && name.trim() && name.trim() !== folder.name) changeFolders(() => api(`/api/notes/folders/${folder.id}`, { method: "PUT", body: { name } }));
}

function deleteFolder(folder) {
    if (confirm(`Delete the folder "${folder.name}"? It has to be empty.`)) changeFolders(async () => {
        await api(`/api/notes/folders/${folder.id}`, { method: "DELETE" });
        noteFolder = "all";
        notePage = 1;
    });
}

/* Fetches the folders and keeps them for the cards and the note window. */
async function loadFolders() {
    folderList = await api("/api/notes/folders");
    if (noteFolder !== "all" && noteFolder !== "daily" && !folderList.folders.some(f => String(f.id) === noteFolder)) noteFolder = "all";
    return folderList;
}

/* The Folder choices in the note window. */
function fillFolderSelect(selected) {
    const select = $("noteFolderSelect");
    select.innerHTML = "";
    select.append(el("option", { value: "", textContent: "Daily Calendar Notes" }),
        ...folderList.folders.map(f => el("option", { value: f.id, textContent: f.name })));
    select.value = selected == null ? "" : String(selected);
}

export async function loadNotes() {
    await loadFolders();
    const q = $("noteSearch").value.trim();
    const params = new URLSearchParams({ page: notePage - 1, size: notePageSize });
    if (q) params.set("q", q);
    if (noteTagFilter) params.set("tag", noteTagFilter);
    const searching = !!(q || noteTagFilter);
    // While the diary is hidden a search or tag never looks into it, so a match cannot give its words away.
    const folder = !diaryRevealed && searching && noteFolder === "all" ? "folders" : noteFolder;
    if (folder !== "all") params.set("folder", folder);
    const closedDiary = !diaryRevealed && searching && noteFolder === "daily";
    const [data, counts] = closedDiary ? [{ notes: [], total: 0, page: 0, size: notePageSize, pages: 0 }, []]
        : await Promise.all([api("/api/notes?" + params), diaryRevealed || noteFolder !== "daily" ? api("/api/notes/tags" + tagScope()) : []]);
    if (data.notes.length === 0 && data.total > 0 && notePage > 1) {   // the last note of the last page was deleted
        notePage = data.pages;
        return loadNotes();
    }
    renderFolderBar();
    renderTagBar(counts);
    const list = $("noteList");
    list.innerHTML = "";
    if (data.total === 0) {
        list.append(el("div", { className: "empty-state", textContent: closedDiary ? "The diary is hidden. Show it to search it." : q || noteTagFilter || noteFolder !== "all" ? "No notes match." : "No notes yet. Use + New note to write the first one." }));
    }
    let day = null;
    for (const note of data.notes) {
        if (note.date !== day) {
            day = note.date;
            list.append(el("div", { className: "note-day", textContent: longDay(day) }));
        }
        list.append(noteCard(note));
    }
    renderNotePager(data);
}

/* Previous / Next and the page size, laid out like the transactions list's. */
function renderNotePager(data) {
    const pager = $("notePager");
    pager.innerHTML = "";
    pager.classList.toggle("hidden", data.total <= 0);
    if (data.total <= 0) return;
    const first = data.page * data.size + 1;
    const prev = el("button", { className: "btn btn-secondary", textContent: "‹ Previous", disabled: data.page <= 0 });
    const next = el("button", { className: "btn btn-secondary", textContent: "Next ›", disabled: data.page >= data.pages - 1 });
    prev.addEventListener("click", () => { notePage--; loadNotes().catch(e => alert(e.message)); });
    next.addEventListener("click", () => { notePage++; loadNotes().catch(e => alert(e.message)); });
    const size = el("select", { className: "page-size" },
        ...[10, 25, 50].map(n => el("option", { value: n, textContent: `${n} per page`, selected: n === notePageSize })));
    size.addEventListener("change", () => { notePageSize = Number(size.value); notePage = 1; loadNotes().catch(e => alert(e.message)); });
    pager.append(
        el("span", { textContent: `Showing ${first}–${first + data.notes.length - 1} of ${data.total}` }),
        el("div", { className: "pager-controls" }, size, prev, el("span", { textContent: `Page ${data.page + 1} of ${data.pages}` }), next));
}

/* After any change: the calendar's marks, the calendar day, and the list if it is on show. */
async function afterNoteChange() {
    setNoteDays(new Map((await api("/api/notes/days")).map(d => [d.date, d.count])));
    renderCalendar();
    if (wsView === "notes") await loadNotes();
}

function renderFormTags() {
    const pills = $("noteTagPills");
    pills.innerHTML = "";
    for (const tag of formTags) {
        const remove = el("button", { className: "tag-x", type: "button", textContent: "×", title: "Remove the tag " + tag });
        remove.setAttribute("aria-label", "Remove the tag " + tag);
        remove.addEventListener("click", () => { formTags = formTags.filter(t => t !== tag); tagsChosen = true; renderFormTags(); $("noteTagInput").focus(); });
        pills.append(el("span", { className: "tag-pill" }, "#" + tag, remove), " ");
    }
}

/* Turns what has been typed in the tag box (one word, or several separated by commas) into tags. False if one is not allowed. */
function commitTagInput() {
    const input = $("noteTagInput");
    const error = $("noteError");
    for (const part of input.value.split(",")) {
        const tag = normalizeTag(part);
        if (!tag) continue;
        if (tag.length > NOTE_TAG_MAX) { error.textContent = `A tag can be at most ${NOTE_TAG_MAX} characters.`; return false; }
        if (!/^[\p{L}\p{M}\p{N}_-]+$/u.test(tag)) { error.textContent = "A tag can only have letters, numbers, - and _."; return false; }
        if (formTags.includes(tag)) continue;
        if (formTags.length >= NOTE_MAX_TAGS) { error.textContent = `A note can have at most ${NOTE_MAX_TAGS} tags.`; return false; }
        formTags.push(tag);
        tagsChosen = true;
    }
    input.value = "";
    error.textContent = "";
    renderFormTags();
    return true;
}

let readingNote = null;

/* A note in reading form: the day and folder, the title, the whole text at a comfortable size and its tags, with no fields to change. */
function readNote(note) {
    readingNote = note;
    $("noteReadTitle").textContent = note.title || "Note";
    $("noteReadMeta").textContent = longDay(note.date) + (folderName(note) ? "  \u00b7  \ud83d\udcc1 " + folderName(note) : "");
    $("noteReadText").textContent = note.body;
    $("noteReadTags").replaceChildren(...(note.tags || []).map(tag => el("span", { className: "tag-pill", textContent: "#" + tag })));
    $("noteReadOverlay").classList.remove("hidden");
}

function closeRead() {
    $("noteReadOverlay").classList.add("hidden");
    readingNote = null;
}

/* note is null to write a new one; day (yyyy-mm-dd) is the day it starts on. */
export function openNote(note, day) {
    editingNote = note || null;
    formTags = note && note.tags ? [...note.tags] : [];
    tagsChosen = !!note;   // a saved note keeps its tags when its text changes; a new one follows the suggestions until its tags are touched
    $("noteTagInput").value = "";
    renderFormTags();
    api("/api/notes/tags" + (diaryRevealed ? "" : "?folder=folders")).then(fillTagSuggestions).catch(() => {});   // suggestions are a nicety
    $("noteTitleHeading").textContent = note ? "Edit note" : "New note";
    $("noteDate").value = note ? note.date : (day || isoDay(new Date()));
    fillFolderSelect(note ? note.folderId : (noteFolder === "all" || noteFolder === "daily" ? null : Number(noteFolder)));   // a new note starts in the folder on show
    loadFolders().then(() => fillFolderSelect($("noteFolderSelect").value === "" ? null : Number($("noteFolderSelect").value))).catch(() => {});
    $("noteHeading").value = note && note.title ? note.title : "";
    $("noteBody").value = note ? note.body : "";
    $("noteDelete").classList.toggle("hidden", !note);
    $("noteError").textContent = "";
    $("noteSave").disabled = false;
    updateNoteCount();
    $("noteOverlay").classList.remove("hidden");
    $("noteBody").focus();
}

function closeNote() {
    clearTimeout(suggestTimer);
    suggestAsk++;   // a suggestion still on its way is dropped
    $("noteOverlay").classList.add("hidden");
}

function updateNoteCount() {
    $("noteCount").textContent = `${$("noteBody").value.length} / ${NOTE_MAX}`;
}

/* Asks the server which tags this title and text would get, a moment after the last keystroke. force = the Suggest button: the answer
   replaces the tags and counts as chosen; otherwise it only fills in a note whose tags nobody has touched. */
function askForNoteTags(force) {
    if (!force && tagsChosen) return;
    clearTimeout(suggestTimer);
    const ask = ++suggestAsk;
    suggestTimer = setTimeout(async () => {
        if (!$("noteBody").value.trim() && !$("noteHeading").value.trim()) return;
        try {
            const found = await api("/api/notes/suggest-tags", { method: "POST", body: { title: $("noteHeading").value, body: $("noteBody").value } });
            if (ask !== suggestAsk || (tagsChosen && !force)) return;   // a newer question, the form was closed, or a person chose in the meantime
            formTags = found;
            if (force) tagsChosen = true;
            renderFormTags();
        } catch (e) {
            clientLog("WARN", "note.tags.suggest-failed", describeError(e));   // the server still tags the note when it is saved
        }
    }, force ? 0 : 450);
}

async function saveNote() {
    const error = $("noteError");
    if (!$("noteDate").value) { error.textContent = "Choose the day."; return; }
    if (!$("noteBody").value.trim()) { error.textContent = "Write something in the note."; return; }
    if (!commitTagInput()) return;
    $("noteSave").disabled = true;
    try {
        const body = { date: $("noteDate").value, title: $("noteHeading").value, body: $("noteBody").value, tags: tagsChosen ? formTags : null,
            folderId: $("noteFolderSelect").value === "" ? null : Number($("noteFolderSelect").value) };   // not tagged by hand: no list, so the server works the tags out
        await api(editingNote ? `/api/notes/${editingNote.id}` : "/api/notes", { method: editingNote ? "PUT" : "POST", body });
        closeNote();
        await afterNoteChange();
    } catch (e) {
        error.textContent = e.message;
        $("noteSave").disabled = false;
    }
}

async function deleteNote() {
    if (!editingNote || !confirm("Delete this note? This cannot be undone.")) return;
    $("noteDelete").disabled = true;
    try {
        await api(`/api/notes/${editingNote.id}`, { method: "DELETE" });
        closeNote();
        await afterNoteChange();
    } catch (e) {
        $("noteError").textContent = e.message;
    } finally {
        $("noteDelete").disabled = false;
    }
}

export function initNotes() {
    $("noteNew").addEventListener("click", () => openNote(null));
    $("noteSearch").addEventListener("input", () => {
        clearTimeout(noteSearchTimer);
        noteSearchTimer = setTimeout(() => { notePage = 1; loadNotes().catch(e => alert(e.message)); }, 250);
    });
    $("noteReadClose").addEventListener("click", closeRead);
    $("noteReadDone").addEventListener("click", closeRead);
    $("noteReadEdit").addEventListener("click", () => { const n = readingNote; closeRead(); if (n) openNote(n); });
    $("noteSave").addEventListener("click", saveNote);
    $("noteDelete").addEventListener("click", deleteNote);
    $("noteCancel").addEventListener("click", closeNote);
    $("noteClose").addEventListener("click", closeNote);
    $("noteBody").addEventListener("input", () => { updateNoteCount(); askForNoteTags(false); });
    $("noteHeading").addEventListener("input", () => askForNoteTags(false));
    $("noteTagSuggest").addEventListener("click", () => askForNoteTags(true));
    const tagInput = $("noteTagInput");
    tagInput.addEventListener("keydown", e => {
        if (e.key === "Enter" || e.key === ",") { e.preventDefault(); commitTagInput(); }
        else if (e.key === "Backspace" && tagInput.value === "" && formTags.length) { formTags.pop(); tagsChosen = true; renderFormTags(); }
    });
    tagInput.addEventListener("change", commitTagInput);   // picking a suggestion
    $("noteTagBox").addEventListener("click", () => tagInput.focus());
}
