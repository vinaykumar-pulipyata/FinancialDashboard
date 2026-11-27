/* The view card, the main part of the page under the header. Seven tab buttons (Calendar, Transactions, Budgets, Spending Review, Stats, Accounts, Net worth) and three in the sidebar (Reminders and Notes in the Organiser group, Categories in the Categories group; a chosen level is a view under Accounts) choose what it shows, and
   every change turns the card over. It has two faces: the view on show is on one, the next view is moved onto the other
   before the turn, and the old one goes back to the holder once the turn has finished. Calendar is on show first. */

import { $, replay } from "../core/util.js";
import { renderNetWorth } from "./net-worth.js";
import { showCategories } from "./categories.js";
import { showImport } from "./import.js";
import { loadNotes } from "./notes.js";

const WS_VIEW_IDS = { import: "importView", calendar: "calendarView", transactions: "txnView", stats: "statsView", insights: "insightsView", budgets: "budgetsView", accounts: "accountsView", level: "levelView", networth: "networthView", notes: "notesView", reminders: "remindersView", categories: "categoriesView" };
const WS_NO_RANGE = new Set(["accounts", "level", "notes", "reminders", "stats", "insights", "budgets", "categories", "import"]);   // views that hide the Today / From / To row
const WS_NO_ADD = new Set(["networth", "stats", "reminders", "insights", "budgets", "categories", "import"]);                          // views that hide the red + (new transaction)
const FLIP_MS = 650;   // a little longer than the .6s turn in flip.css
export let wsView = "calendar";
let wsTimer = null;

/* Sends the face that is no longer showing back to the holder. */
function wsSettle() {
    clearTimeout(wsTimer);
    const hiddenFace = $("wsFlip").classList.contains("flipped") ? $("wsFaceA") : $("wsFaceB");
    while (hiddenFace.firstElementChild) $("wsHolder").append(hiddenFace.firstElementChild);
    hiddenFace.classList.add("collapsed");
}

export function showWorkspaceView(view) {
    if (view === wsView) return;
    wsSettle();   // finish any turn still under way
    const flip = $("wsFlip");
    const nextFace = flip.classList.contains("flipped") ? $("wsFaceA") : $("wsFaceB");
    nextFace.append($(WS_VIEW_IDS[view]));
    replay($(WS_VIEW_IDS[view]), "view-in");   // the content settles in as the card comes round
    nextFace.classList.remove("collapsed");
    flip.classList.toggle("flipped");
    wsView = view;
    if (view === "stats" || view === "networth") document.querySelectorAll(".donut").forEach(ring => replay(ring, "sweep"));   // the rings draw again each time Stats or Net worth is opened
    if (view === "categories") showCategories();   // a clean list each time the page is opened
    if (view === "import") showImport();   // a clean page each time
    if (view === "notes") loadNotes().catch(e => alert(e.message));
    if (view === "networth") renderNetWorth();   // the graph is as wide as its panel, which is only known once it is on show
    for (const tab of document.querySelectorAll(".ws-tab")) {
        const on = tab.dataset.view === (view === "level" ? "accounts" : view);   // a level belongs to the Accounts button
        tab.classList.toggle("active", on);
        tab.setAttribute("aria-pressed", String(on));
    }
    $("wsRange").classList.toggle("hidden", WS_NO_RANGE.has(view));   // Stats has its own period, the others are not scoped by dates
    document.querySelector("#wsRange .cal-add-wrap").classList.toggle("hidden", WS_NO_ADD.has(view));
    // Notes and Reminders have no tab: they are in the sidebar's Organiser group, where the one on show is pressed
    for (const item of document.querySelectorAll(".menu-view")) item.setAttribute("aria-pressed", String(item.dataset.view === view));
    wsTimer = setTimeout(wsSettle, FLIP_MS);
}

export function initWorkspace() {
    $("levelBack").addEventListener("click", () => showWorkspaceView("accounts"));
    for (const tab of [...document.querySelectorAll(".ws-tab"), ...document.querySelectorAll(".menu-view")]) {
        tab.addEventListener("click", () => showWorkspaceView(tab.dataset.view));
    }
}
