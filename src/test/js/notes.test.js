const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/notes.js");
    p.run("var wsView = 'calendar'; function showWorkspaceView(v) { wsView = v; } function loadNotesStub() {}");
    return p;
}

test("a tag is cleaned like the server cleans it: lower case, no #, spaces become hyphens", () => {
    const p = page();
    assert.deepEqual(["Insurance", "#Car", "##Tax_2026", "  home loan ", "A   B", "", "  #  "].map(t => p.run(`normalizeTag(${JSON.stringify(t)})`)),
        ["insurance", "car", "tax_2026", "home-loan", "a-b", "", ""]);
});

test("typing tags: words, commas and Enter become tags, repeats are ignored, bad ones are explained", () => {
    const p = page();
    p.run("formTags = []");
    p.node("noteTagInput").value = "Car loan, #Tax,, car-loan";
    assert.equal(p.run("commitTagInput()"), true);
    assert.deepEqual(p.run("formTags"), ["car-loan", "tax"]);
    assert.equal(p.node("noteTagInput").value, "");
    assert.equal(p.node("noteTagPills").children.length >= 4, true, "a pill and a space for each tag");

    p.node("noteTagInput").value = "rent & bills";
    assert.equal(p.run("commitTagInput()"), false);
    assert.match(p.node("noteError").textContent, /letters, numbers/);
    assert.deepEqual(p.run("formTags"), ["car-loan", "tax"], "a refused tag changes nothing");

    p.node("noteTagInput").value = "x".repeat(31);
    assert.equal(p.run("commitTagInput()"), false);
    assert.match(p.node("noteError").textContent, /at most 30/);

    p.node("noteTagInput").value = "वेतन";
    assert.equal(p.run("commitTagInput()"), true, "any script is allowed");
    assert.ok(p.run("formTags").includes("वेतन"));
});

test("a note can have at most ten tags", () => {
    const p = page();
    p.run("formTags = []");
    p.node("noteTagInput").value = Array.from({ length: 10 }, (_, i) => "t" + i).join(",");
    assert.equal(p.run("commitTagInput()"), true);
    p.node("noteTagInput").value = "eleventh";
    assert.equal(p.run("commitTagInput()"), false);
    assert.match(p.node("noteError").textContent, /at most 10/);
    assert.equal(p.run("formTags.length"), 10);
});

test("removing a tag with its × takes it off the note", () => {
    const p = page();
    p.run(`formTags = ["a", "b"]; renderFormTags()`);
    const pill = p.node("noteTagPills").children.find(c => typeof c !== "string");
    pill.find(n => n.tag === "button")[0].fire("click");
    assert.deepEqual(p.run("formTags"), ["b"]);
});

test("a note card shows the title, the text and its tags, and clicking a tag filters the list", () => {
    const p = page();
    p.run(`var shown = null; function readNote(n) { shown = n.body; } diaryRevealed = true;`);
    const card = p.raw(`noteCard({ id: 1, date: "2026-10-12", title: "Rent", body: "Paid it", tags: ["rent", "home"] })`);
    assert.ok(card.className.includes("note-card"));
    assert.match(card.allText, /RentPaid it#home#rent|Rent.*Paid it.*#rent.*#home/s);
    const pills = card.find(n => n.className === "tag-pill");
    assert.deepEqual(pills.map(n => n.textContent), ["#rent", "#home"]);
    card.fire("click");
    assert.equal(p.run("shown"), "Paid it");
    p.run("wsView = 'calendar'");
    pills[0].fire("click");
    assert.equal(p.run("noteTagFilter"), "rent");
    assert.equal(p.run("wsView"), "notes", "the Notes view opens, narrowed to the tag");
    assert.equal(p.run("notePage"), 1);
    const untagged = p.raw(`noteCard({ id: 2, date: "2026-10-12", title: null, body: "No tags", tags: [] })`);
    assert.equal(untagged.find(n => n.className === "tag-pill").length, 0);
});

test("the pager shows where you are and disables Previous and Next at the ends", () => {
    const p = page();
    const pager = () => p.node("notePager");
    p.run(`renderNotePager({ notes: new Array(10).fill({}), total: 25, page: 0, size: 10, pages: 3 })`);
    assert.match(pager().allText, /Showing 1–10 of 25/);
    assert.match(pager().allText, /Page 1 of 3/);
    const buttons = () => pager().find(n => n.tag === "button");
    assert.equal(buttons()[0].disabled, true, "no Previous on the first page");
    assert.equal(buttons()[1].disabled, false);
    p.run(`renderNotePager({ notes: new Array(5).fill({}), total: 25, page: 2, size: 10, pages: 3 })`);
    assert.match(pager().allText, /Showing 21–25 of 25/);
    assert.equal(buttons()[1].disabled, true, "no Next on the last page");
    p.run(`renderNotePager({ notes: [], total: 0, page: 0, size: 10, pages: 0 })`);
    assert.ok(pager().classList.contains("hidden"), "no pager for an empty list");
});

test("the tag row marks the chosen tag and offers the others with their counts", () => {
    const p = page();
    p.run(`noteTagFilter = "rent"; renderTagBar([{ tag: "rent", count: 3 }, { tag: "car", count: 1 }])`);
    const pills = p.node("noteTags").children;
    assert.deepEqual(pills.map(n => n.allText), ["#rent (3)", "#car (1)"]);
    assert.ok(pills[0].classList.contains("active") && !pills[1].classList.contains("active"));
    assert.deepEqual(p.node("noteTagList").children.map(o => o.value), ["rent", "car"], "suggestions for the note window");
    p.run(`noteTagFilter = "gone"; renderTagBar([{ tag: "car", count: 1 }])`);
    assert.deepEqual(p.node("noteTags").children.map(n => n.allText), ["#gone ✕", "#car (1)"], "a filter whose notes are gone can still be cleared");
});

test("opening the note window fills it for a new note or an existing one", () => {
    const p = page();
    p.run(`var fetched = []; function api(path) { fetched.push(path); return Promise.resolve([]); }`);
    p.run(`openNote(null, "2026-10-12")`);
    assert.equal(p.node("noteTitleHeading").textContent, "New note");
    assert.equal(p.node("noteDate").value, "2026-10-12");
    assert.equal(p.node("noteBody").value, "");
    assert.ok(p.node("noteDelete").classList.contains("hidden"));
    assert.ok(!p.node("noteOverlay").classList.contains("hidden"));
    p.run(`openNote({ id: 5, date: "2026-03-01", title: "Old", body: "Text", tags: ["x"] })`);
    assert.equal(p.node("noteTitleHeading").textContent, "Edit note");
    assert.deepEqual([p.node("noteDate").value, p.node("noteHeading").value, p.node("noteBody").value], ["2026-03-01", "Old", "Text"]);
    assert.deepEqual(p.run("formTags"), ["x"]);
    assert.ok(!p.node("noteDelete").classList.contains("hidden"));
    assert.equal(p.node("noteCount").textContent, "4 / 10000");
    p.run("closeNote()");
    assert.ok(p.node("noteOverlay").classList.contains("hidden"));
});

test("a long day name is spelled out", () => {
    const p = page();
    assert.equal(p.run(`longDay("2026-10-12")`), "Monday, 12 October 2026");
});

/* Tags worked out by the server from the title and text */
function formPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/notes.js");
    p.run(`var wsView = 'calendar'; function showWorkspaceView(v) { wsView = v; } function renderCalendar() {} function clientLog() {} function describeError() { return ""; }
        var calls = []; var suggestion = ["car", "service"];
        function api(path, options) {
            calls.push({ path, method: options && options.method, body: options && options.body });
            if (path === "/api/notes/suggest-tags") return Promise.resolve(suggestion);
            if (path === "/api/notes/tags") return Promise.resolve([]);
            if (path === "/api/notes/days") return Promise.resolve([]);
            return Promise.resolve({ id: 1, notes: [], total: 0, pages: 1 });
        }
        initNotes();`);
    return p;
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const noteBody = p => p.run(`calls.filter(c => c.path === "/api/notes" && c.method === "POST").map(c => c.body)`)[0];

test("typing a note asks the server for tags and fills the box; saving then sends no list so the server decides", async () => {
    const p = formPage();
    p.run(`openNote(null, "2026-10-12")`);
    p.node("noteBody").value = "Car service done at Honda City workshop";
    p.node("noteBody").fire("input");
    p.node("noteBody").fire("input");
    await wait(550);
    const asked = p.run(`calls.filter(c => c.path === "/api/notes/suggest-tags")`);
    assert.equal(asked.length, 1, "a burst of keystrokes is one question");
    assert.deepEqual(asked[0].body, { title: "", body: "Car service done at Honda City workshop" });
    assert.deepEqual(p.run("formTags"), ["car", "service"]);
    await p.raw(`saveNote()`);
    assert.equal(noteBody(p).tags, null, "not tagged by hand: the server works them out");
});

test("once a person adds or removes a tag, suggestions stop and the list is sent", async () => {
    const p = formPage();
    p.run(`openNote(null, "2026-10-12")`);
    p.node("noteTagInput").value = "family";
    p.run("commitTagInput()");
    p.node("noteBody").value = "Something about the car";
    p.node("noteBody").fire("input");
    await wait(550);
    assert.equal(p.run(`calls.filter(c => c.path === "/api/notes/suggest-tags").length`), 0);
    await p.raw(`saveNote()`);
    assert.deepEqual(noteBody(p).tags, ["family"]);
});

test("a saved note keeps its tags when its text changes, and Suggest replaces them with the server's answer", async () => {
    const p = formPage();
    p.run(`openNote({ id: 5, date: "2026-03-01", title: "Old", body: "Text", tags: ["x"] })`);
    p.node("noteBody").value = "New text about the car";
    p.node("noteBody").fire("input");
    await wait(550);
    assert.deepEqual(p.run("formTags"), ["x"]);
    p.node("noteTagSuggest").fire("click");
    await wait(60);
    assert.deepEqual(p.run("formTags"), ["car", "service"]);
    await p.raw(`saveNote()`);
    const put = p.run(`calls.filter(c => c.path === "/api/notes/5")[0].body`);
    assert.deepEqual(put.tags, ["car", "service"]);
});

test("the folder bar lists All notes, the Daily Calendar Notes and each folder with counts, and the chosen folder can be renamed or deleted", () => {
    const p = page();
    p.run(`folderList = { daily: 3, folders: [{ id: 4, name: "Recipes", count: 2 }, { id: 5, name: "Study Materials", count: 0 }] }; noteFolder = "4"; renderFolderBar()`);
    const bar = p.node("noteFolders");
    assert.deepEqual(bar.find(n => String(n.className).startsWith("chip")).map(n => n.textContent),
        ["All notes (5)", "Daily Calendar Notes (3)", "📁 Recipes (2)", "📁 Study Materials (0)", "+ Folder"]);
    assert.equal(bar.find(n => String(n.className).includes("active"))[0].textContent, "📁 Recipes (2)");
    assert.deepEqual(bar.find(n => n.className === "txn-link").map(n => n.textContent), ["🔒 Diary hidden · Show", "Rename folder", "Delete folder"]);
    p.run(`noteFolder = "daily"; renderFolderBar()`);
    assert.deepEqual(p.node("noteFolders").find(n => n.className === "txn-link").map(n => n.textContent), ["🔒 Diary hidden · Show"], "the diary is not a folder");
});

test("the note window offers the Daily Calendar Notes and the folders, and a new note starts in the folder on show", () => {
    const p = page();
    p.run(`folderList = { daily: 1, folders: [{ id: 4, name: "Recipes", count: 1 }] }; fillFolderSelect(4)`);
    const select = p.node("noteFolderSelect");
    assert.deepEqual(select.children.map(o => o.textContent), ["Daily Calendar Notes", "Recipes"]);
    assert.equal(select.value, "4");
    p.run(`fillFolderSelect(null)`);
    assert.equal(p.node("noteFolderSelect").value, "");
});

test("a card names its folder, a diary note does not", () => {
    const p = page();
    p.run(`folderList = { daily: 1, folders: [{ id: 4, name: "Recipes", count: 1 }] }`);
    assert.equal(p.run(`folderName({ folderId: 4 })`), "Recipes");
    assert.equal(p.run(`folderName({ folderId: null })`), "");
});

test("a Daily Calendar Note shows as dots until the diary is revealed, and clicking it asks for the password", () => {
    const p = page();
    p.run(`var asked = 0, opened = 0, dlg = {}; function ledgerFail() {} function runLedger() {} function readNote() { opened++; }
        function openLedgerDialog(title, label, build) { asked++; build($("ledgerBody")); } function field(label, input) { return el("div", {}, input); }`);
    const card = p.raw(`noteCard({ id: 1, date: "2026-10-12", title: "Secret", body: "Met someone", tags: ["private"], folderId: null })`);
    assert.doesNotMatch(card.allText, /Secret|Met someone|private/);
    assert.match(card.allText, /••••••••/);
    assert.equal(card.find(n => n.className === "tag-pill").length, 0, "no tag names either");
    card.fire("click");
    assert.equal(p.run("asked"), 1, "the password is asked for");
    assert.equal(p.run("opened"), 0, "and the note is not opened");
});

test("a folder note is never hidden, and a revealed diary note shows its text", () => {
    const p = page();
    p.run(`diaryRevealed = false`);
    assert.match(p.raw(`noteCard({ id: 2, date: "2026-10-12", title: "Dal", body: "Soak it", tags: [], folderId: 4 })`).allText, /DalSoak it/);
    p.run(`diaryRevealed = true`);
    assert.match(p.raw(`noteCard({ id: 1, date: "2026-10-12", title: "Secret", body: "Met someone", tags: [], folderId: null })`).allText, /SecretMet someone/);
});

test("a note that could not be opened says it was sealed with an earlier password", () => {
    const p = page();
    p.run(`diaryRevealed = true`);
    assert.match(p.raw(`noteCard({ id: 3, date: "2026-10-12", title: null, body: "", tags: [], folderId: null, locked: true })`).allText, /Sealed with an earlier password/);
});

test("while the diary is hidden the tag list leaves the diary's tags out, and a search is kept out of the diary", () => {
    const p = page();
    p.run(`diaryRevealed = false; noteFolder = "all"`);
    assert.equal(p.run("tagScope()"), "?folder=folders");
    p.run(`noteFolder = "4"`);
    assert.equal(p.run("tagScope()"), "?folder=4");
    p.run(`diaryRevealed = true; noteFolder = "all"`);
    assert.equal(p.run("tagScope()"), "");
});

test("reading a note shows its text and tags with no fields; Edit opens the form", () => {
    const p = page();
    p.run(`diaryRevealed = true; var edited = null; function openNote(n) { edited = n.body; } function api() { return Promise.resolve([]); } initNotes();`);
    p.run(`readNote({ id: 1, date: "2026-10-12", title: "Rent", body: "Paid it", tags: ["rent"], folderId: null })`);
    assert.ok(!p.node("noteReadOverlay").classList.contains("hidden"));
    assert.equal(p.node("noteReadTitle").textContent, "Rent");
    assert.equal(p.node("noteReadText").textContent, "Paid it");
    assert.match(p.node("noteReadTags").allText, /#rent/);
    p.node("noteReadEdit").fire("click");
    assert.ok(p.node("noteReadOverlay").classList.contains("hidden"));
    assert.equal(p.run("edited"), "Paid it");
});
