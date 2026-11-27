const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createPage, STATIC } = require("./harness");

function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "panels/workspace.js");
    p.run(`function loadNotes() { return Promise.resolve(); } function renderNetWorth() {} function showCategories() {}`);
    return p;
}

test("every view button has a view to show, and every view is in the holder", () => {
    const html = fs.readFileSync(path.join(STATIC, "panels", "workspace.html"), "utf8");
    const p = page();
    const ids = p.run("WS_VIEW_IDS");
    const tabs = [...html.matchAll(/data-view="([a-z]+)"/g)].map(m => m[1]);
    assert.deepEqual(tabs, ["calendar", "transactions", "budgets", "insights", "stats", "accounts", "networth"]);
    for (const tab of tabs) assert.ok(ids[tab], `no view for ${tab}`);
    for (const id of Object.values(ids)) assert.ok(html.includes(`id="${id}"`), `${id} is missing from workspace.html`);
    assert.ok(ids.level, "the chosen level of Accounts is a view of its own");
});

test("Stats hides both the date row and the red +; Net worth hides only the +", () => {
    const p = page();
    assert.deepEqual([...p.run("[...WS_NO_RANGE]")].sort(), ["accounts", "budgets", "categories", "import", "insights", "level", "notes", "reminders", "stats"]);
    assert.deepEqual([...p.run("[...WS_NO_ADD]")].sort(), ["budgets", "categories", "import", "insights", "networth", "reminders", "stats"]);
    assert.equal(p.run(`WS_NO_RANGE.has("networth")`), false, "Net worth keeps Today / From / To");
    assert.equal(p.run(`WS_NO_ADD.has("calendar") || WS_NO_ADD.has("transactions")`), false, "the + stays on Calendar and Transactions");
});

test("switching views shows or hides the date row and the +", () => {
    const p = page();
    const range = p.node("wsRange");
    const plus = p.select("#wsRange .cal-add-wrap");
    for (const id of ["wsFaceA", "wsFaceB", "wsHolder", "wsFlip", "calendarView", "txnView", "statsView", "insightsView", "budgetsView", "accountsView", "levelView", "networthView", "notesView", "remindersView"]) p.node(id);
    const states = {};
    for (const view of ["stats", "networth", "transactions", "calendar"]) {
        p.run(`showWorkspaceView("${view}")`);
        states[view] = [range.classList.contains("hidden"), plus.classList.contains("hidden")];
        p.run("wsSettle()");
    }
    assert.deepEqual(states, { stats: [true, true], networth: [false, true], transactions: [false, false], calendar: [false, false] });
});

test("choosing the view you are already on does nothing", () => {
    const p = page();
    for (const id of ["wsFaceA", "wsFaceB", "wsHolder", "wsFlip"]) p.node(id);
    p.run(`showWorkspaceView("calendar")`);
    assert.equal(p.node("wsFlip").classList.contains("flipped"), false);
});

test("Reminders and Notes are in the menu, not the tab row; choosing one shows its view and marks the item", () => {
    const html = fs.readFileSync(path.join(STATIC, "panels", "workspace.html"), "utf8");
    const shell = fs.readFileSync(path.join(STATIC, "Dashboard.html"), "utf8");
    assert.ok(!html.includes('data-view="notes"') && !html.includes('data-view="reminders"'), "no tabs for them");
    assert.match(shell, /id="menuNotes" data-view="notes"/);
    assert.match(shell, /id="menuReminders" data-view="reminders"/);

    const p = page();
    for (const id of ["wsFaceA", "wsFaceB", "wsHolder", "wsFlip", "notesView", "remindersView", "calendarView", "menuButton"]) p.node(id);
    const notes = p.node("menuNotes"), reminders = p.node("menuReminders");
    notes.dataset.view = "notes"; reminders.dataset.view = "reminders";
    notes.classList.add("menu-view"); reminders.classList.add("menu-view");
    p.run("initWorkspace()");
    notes.fire("click");
    assert.equal(p.run("wsView"), "notes");
    assert.equal(notes.attrs["aria-pressed"], "true");
    assert.equal(reminders.attrs["aria-pressed"], "false");
    p.run("wsSettle()");
    p.run(`showWorkspaceView("calendar")`);
    assert.equal(notes.attrs["aria-pressed"], "false");
});
