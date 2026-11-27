const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Your own reminders: the Coming up bar, the Calendar and the form. Dates are built from the real clock where the bar uses it. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/net-worth.js", "panels/period-filter.js",
        "panels/insights-calc.js", "panels/budget-calc.js", "panels/my-reminders.js", "panels/reminders.js", "panels/calendar.js");
    p.run(`var calls = [], refreshed = 0, wsView = "calendar";
        function showWorkspaceView() {}
        function txnRowElement(t) { return el("div", { className: "txn-row", textContent: t.note || "" }); }
        function openNote() {}  function noteCard(n) { return el("div", { className: "note-card", textContent: n.body }); }
        async function refreshData() { refreshed++; }
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({ notes: [] }); }
        var iso = n => { const t = new Date(); return isoDay(new Date(t.getFullYear(), t.getMonth(), t.getDate() + n)); };
        accounts = []; ledger = { configured: false };
        calMonth = new Date(2031, 2, 1); calSelected = null;`);
    return p;
}
const reminder = (id, date, title, extra = "") => `{ id: ${id}, date: ${JSON.stringify(date)}, title: ${JSON.stringify(title)}, done: false, doneOn: null${extra} }`;
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("open reminders are due: today, soon and late for as long as it takes; later ones, and done ones, are not", () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2020-01-01", "Years late")}, ${reminder(2, "x", "Now")}, ${reminder(3, "x", "In seven")},
        ${reminder(4, "x", "In eight")}, { id: 5, date: "2020-02-02", title: "Done long ago", done: true, doneOn: "2020-02-03" }];
        userReminders[1].date = iso(0); userReminders[2].date = iso(7); userReminders[3].date = iso(8)`);
    const items = p.run("upcomingDues()");
    assert.deepEqual(items.map(i => i.name), ["Years late", "Now", "In seven"]);
    assert.deepEqual(items.map(i => i.kind), ["reminder", "reminder", "reminder"]);
    assert.ok(items[0].days < -30, "a late reminder does not drop off after 30 days, unlike a Ledger bill");
    p.run(`ledger = { configured: true, buckets: [{ name: "Old bill", buffer: false, nextPaymentDate: "2020-01-01", paymentAmount: 5 }] }`);
    assert.ok(!p.run("upcomingDues()").some(i => i.name === "Old bill"), "the Ledger rule is unchanged");
});

test("a reminder reads with a bell, its day and when", () => {
    const p = page();
    assert.match(p.run(`reminderText({ kind: "reminder", name: "Call the CA", date: "2026-10-14", days: 2 })`), /^🔔 Call the CA \(Wed,? 14 Oct, in 2 days\)$/);
    assert.match(p.run(`reminderText({ kind: "reminder", name: "Call the CA", date: "2026-10-09", days: -3 })`), /3 days late\)$/);
});

test("the bar shows a tick box for a reminder, and Tomorrow only when it is late", () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "x", "Late one")}, ${reminder(2, "x", "Today one")}, ${reminder(3, "x", "Soon one")}];
        userReminders[0].date = iso(-2); userReminders[1].date = iso(0); userReminders[2].date = iso(3); renderReminders()`);
    const bar = p.node("remindersBar");
    assert.equal(bar.find(n => n.className === "reminder-line").length, 3);
    assert.equal(bar.find(n => n.tag === "input").length, 3);
    const moves = bar.find(n => n.className === "reminder-move");
    assert.equal(moves.length, 1, "only the late one");
    assert.equal(moves[0].textContent, "Tomorrow");
    assert.deepEqual(bar.find(n => n.tag === "button" && n.className.startsWith("reminder-item")).map(b => b.className),
        ["reminder-item late", "reminder-item today", "reminder-item"]);
});

test("Tomorrow moves the late reminder to tomorrow and keeps its title", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(7, "x", "Late one")}]; userReminders[0].date = iso(-2); renderReminders()`);
    p.node("remindersBar").find(n => n.className === "reminder-move")[0].fire("click");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/7", method: "PUT", body: { date: p.run("tomorrowIso()"), title: "Late one" } }]);
    assert.equal(p.run("tomorrowIso() === iso(1)"), true);
    assert.equal(p.run("refreshed"), 1, "everything is redrawn from the server afterwards");
});

test("ticking in the bar marks it done on the server; a failed save puts the tick back", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(4, "x", "Pay milk bill")}]; userReminders[0].date = iso(0); renderReminders()`);
    const box = p.node("remindersBar").find(n => n.tag === "input")[0];
    box.checked = true;
    box.fire("change");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/4/done", method: "PUT", body: { done: true } }]);
    assert.equal(p.run("refreshed"), 1);

    p.run(`calls = []; userReminders = [{ id: 4, date: "x", title: "Pay milk bill", done: true, doneOn: "x" }]`);
    const again = p.raw(`reminderCheckbox(userReminders[0])`);
    again.checked = false;
    again.fire("change");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/4/done", method: "PUT", body: { done: false } }], "unticking reopens it");

    p.run(`calls = []; function api() { return Promise.reject(new Error("boom")); }; var alerted = null; alert = m => { alerted = m; }`);
    box.checked = true;
    box.fire("change");
    await flush();
    assert.equal(p.run("alerted"), "boom");
    assert.equal(box.checked, false, "the tick is undone");
});

test("moving a reminder to the day it is already on does nothing; an unknown one does nothing", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2031-03-10", "Stay")}]`);
    await p.raw(`moveReminder(1, "2031-03-10")`);
    await p.raw(`moveReminder(99, "2031-03-11")`);
    assert.deepEqual(p.run("calls"), []);
    await p.raw(`moveReminder(1, "2031-03-11")`);
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/1", method: "PUT", body: { date: "2031-03-11", title: "Stay" } }]);
});

test("the Calendar marks days with a reminder still to do, not days where they are all done", () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2031-03-10", "A")}, ${reminder(2, "2031-03-10", "B")}, ${reminder(3, "2031-03-12", "C")},
        { id: 4, date: "2031-03-15", title: "D", done: true, doneOn: "2031-03-15" }]; renderCalendar()`);
    const marks = p.node("calGrid").find(n => n.className === "cal-remind");
    assert.deepEqual(marks.map(m => m.title), ["2 reminders to do", "1 reminder to do"]);
    assert.deepEqual(p.run("[...reminderDaysIn(2031, 2)]"), [["2031-03-10", 2], ["2031-03-12", 1]]);
    assert.deepEqual(p.run("[...reminderDaysIn(2031, 3)]"), [], "other months have none");
});

test("a chosen day lists its reminders with ticks; done ones are struck through and cannot be dragged", () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2031-03-10", "Open one")}, { id: 2, date: "2031-03-10", title: "Finished", done: true, doneOn: "2031-03-11" },
        ${reminder(3, "2031-03-11", "Other day")}]; calSelected = "2031-03-10"; renderCalendar()`);
    const day = p.node("calDay");
    const rows = day.find(n => n.className.startsWith("reminder-row"));
    assert.equal(rows.length, 2);
    assert.deepEqual(rows.map(r => r.className), ["reminder-row", "reminder-row done"]);
    assert.deepEqual(rows.map(r => Boolean(r.draggable)), [true, false]);
    assert.match(rows[1].allText, /done .*11/);
    assert.ok(day.allText.includes("+ Add reminder"));
    assert.ok(!day.allText.includes("Other day"));
    assert.ok(!day.allText.includes("Nothing recorded"));
});

test("a day with nothing but a reminder is not 'nothing recorded'; an empty day still is", () => {
    const p = page();
    p.run(`userReminders = []; calSelected = "2031-03-10"; renderCalendar()`);
    assert.ok(p.node("calDay").allText.includes("Nothing recorded on this day."));
    p.run(`userReminders = [${reminder(1, "2031-03-10", "Something")}]; renderCalendar()`);
    assert.ok(!p.node("calDay").allText.includes("Nothing recorded"));
});

test("dragging an open reminder onto a day moves it there", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(5, "2031-03-10", "Move me")}]; calSelected = "2031-03-10"; renderCalendar()`);
    const row = p.node("calDay").find(n => n.className === "reminder-row")[0];
    const store = {};
    row.fire("dragstart", { dataTransfer: { setData: (k, v) => { store[k] = v; } } });
    assert.equal(store["application/x-financial-reminder"], "5");
    assert.equal(store["text/plain"], undefined, "plain text is not used, so a text drop cannot be mistaken for it");
    const cell = p.node("calGrid").find(n => n.className.startsWith("cal-cell") && n.allText.startsWith("20"))[0];   // day 20
    let prevented = false;
    cell.fire("dragover", { preventDefault() { prevented = true; }, dataTransfer: { types: ["application/x-financial-reminder"] } });
    assert.ok(prevented && cell.classList.contains("drop"), "the cell shows it will accept the drop");
    cell.fire("dragleave");
    assert.ok(!cell.classList.contains("drop"));
    cell.fire("drop", { dataTransfer: { types: ["application/x-financial-reminder"], getData: type => type === "application/x-financial-reminder" ? "5" : "" } });
    await flush();
    assert.deepEqual(p.run("calls.filter(c => c.method === 'PUT')"), [{ path: "/api/reminders/5", method: "PUT", body: { date: "2031-03-20", title: "Move me" } }]);
});

test("the form saves a new reminder on the chosen day and refuses an empty one", async () => {
    const p = page();
    p.run(`openReminderForm(null, "2031-03-10")`);
    assert.equal(p.node("reminderDate").value, "2031-03-10");
    assert.equal(p.node("reminderHeading").textContent, "New reminder");
    assert.ok(p.node("reminderDelete").classList.contains("hidden"));
    assert.ok(!p.node("reminderOverlay").classList.contains("hidden"));
    p.node("reminderText").value = "   ";
    await p.raw("saveReminder()");
    assert.equal(p.node("reminderError").textContent, "Write what to remember.");
    assert.deepEqual(p.run("calls"), []);
    p.node("reminderText").value = "Renew insurance";
    await p.raw("saveReminder()");
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders", method: "POST", body: { date: "2031-03-10", title: "Renew insurance", repeat: "NONE" } }]);
    assert.ok(p.node("reminderOverlay").classList.contains("hidden"), "closes after saving");
    assert.equal(p.run("refreshed"), 1);
});

test("editing fills the form, saves with PUT, and Delete asks first", async () => {
    const p = page();
    p.run(`openReminderForm(${reminder(9, "2031-03-10", "Call the CA")})`);
    assert.equal(p.node("reminderText").value, "Call the CA");
    assert.equal(p.node("reminderHeading").textContent, "Edit reminder");
    assert.ok(!p.node("reminderDelete").classList.contains("hidden"));
    p.node("reminderText").value = "Call the CA about GST";
    await p.raw("saveReminder()");
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/9", method: "PUT", body: { date: "2031-03-10", title: "Call the CA about GST", repeat: "NONE" } }]);

    p.run(`calls = []; openReminderForm(${reminder(9, "2031-03-10", "Call the CA")}); confirm = () => false`);
    await p.raw("deleteReminder()");
    assert.deepEqual(p.run("calls"), [], "declined: nothing deleted");
    p.run("confirm = () => true");
    await p.raw("deleteReminder()");
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/9", method: "DELETE" }]);
});

test("the server's refusal is shown in the form and the form stays open", async () => {
    const p = page();
    p.run(`openReminderForm(null, "2031-03-10"); function api() { return Promise.reject(new Error("A reminder can be at most 200 characters")); }`);
    p.node("reminderText").value = "x";
    await p.raw("saveReminder()");
    assert.equal(p.node("reminderError").textContent, "A reminder can be at most 200 characters");
    assert.ok(!p.node("reminderOverlay").classList.contains("hidden"));
    assert.equal(p.node("reminderSave").disabled, false, "can try again");
});

test("a day with notes and a reminder shows the two marks side by side in one group, notes first", () => {
    const p = page();
    p.run(`noteDays = new Map([["2031-03-10", 2]]); userReminders = [${reminder(1, "2031-03-10", "A")}, ${reminder(2, "2031-03-12", "B")}]; renderCalendar()`);
    const groups = p.node("calGrid").find(n => n.className === "cal-marks");
    assert.equal(groups.length, 2, "one group per day that has a mark; none for plain days");
    assert.deepEqual(groups[0].children.map(c => c.className), ["cal-note", "cal-remind"]);
    assert.deepEqual(groups[1].children.map(c => c.className), ["cal-remind"]);
});

test("each reminder in a day has a Delete button that asks first, then deletes and redraws", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2031-03-10", "Wrong entry")}, { id: 2, date: "2031-03-10", title: "Finished", done: true, doneOn: "2031-03-11" }];
        calSelected = "2031-03-10"; renderCalendar()`);
    const buttons = p.node("calDay").find(n => n.className === "reminder-delete");
    assert.equal(buttons.length, 2, "open and done ones can both be deleted");
    p.run("confirm = () => false");
    buttons[0].fire("click");
    await flush();
    assert.deepEqual(p.run("calls.filter(c => c.method === 'DELETE')"), [], "declined: nothing deleted");
    p.run("confirm = () => true");
    buttons[0].fire("click");
    await flush();
    assert.deepEqual(p.run("calls.filter(c => c.method === 'DELETE')"), [{ path: "/api/reminders/1", method: "DELETE" }]);
    assert.equal(p.run("refreshed"), 1);
});

test("a failed delete from the form keeps the form open and shows the reason", async () => {
    const p = page();
    p.run(`openReminderForm(${reminder(3, "2031-03-10", "x")}); function api() { return Promise.reject(new Error("Reminder 3 not found")); }`);
    await p.raw("deleteReminder()");
    assert.equal(p.node("reminderError").textContent, "Reminder 3 not found");
    assert.ok(!p.node("reminderOverlay").classList.contains("hidden"));
});

const yearly = (id, date, title) => `{ id: ${id}, date: "${date}", title: "${title}", done: false, doneOn: null, repeat: "YEARLY" }`;
const repeating = (id, date, title, repeat) => `{ id: ${id}, date: "${date}", title: "${title}", done: false, doneOn: null, repeat: "${repeat}" }`;

test("a yearly reminder falls on its month and day from its first year on, never before; 29 February moves to the 28th", () => {
    const p = page();
    p.run(`var b = ${yearly(1, "1990-03-14", "Asha birthday")}, leap = ${yearly(2, "2024-02-29", "Leap day")}`);
    assert.equal(p.run(`reminderFallsOn(b, "1990-03-14")`), true, "the first one");
    assert.equal(p.run(`reminderFallsOn(b, "2031-03-14")`), true, "a later year");
    assert.equal(p.run(`reminderFallsOn(b, "1989-03-14")`), false, "before the first year");
    assert.equal(p.run(`reminderFallsOn(b, "2031-03-15")`), false);
    assert.equal(p.run(`yearlyDay(leap, 2028)`), "2028-02-29");
    assert.equal(p.run(`yearlyDay(leap, 2031)`), "2031-02-28", "no 29th that year");
    assert.equal(p.run(`reminderFallsOn({ date: "2031-03-14", title: "x", done: false }, "2032-03-14")`), false, "an ordinary reminder does not repeat");
});

test("the years are counted from the first day: 35 years, 1 year, nothing in the first year", () => {
    const p = page();
    p.run(`var b = ${yearly(1, "1990-03-14", "Asha")}`);
    assert.equal(p.run(`yearsText(b, "2025-03-14")`), " · 35 years");
    assert.equal(p.run(`yearsText(b, "1991-03-14")`), " · 1 year");
    assert.equal(p.run(`yearsText(b, "1990-03-14")`), "");
    assert.equal(p.run(`yearsText({ date: "1990-03-14" }, "2025-03-14")`), "", "ordinary reminders have none");
});

test("the next day of a yearly reminder is this year's if it has not passed (today counts), else next year's", () => {
    const p = page();
    p.run(`var b = ${yearly(1, "1990-03-14", "Asha")}`);
    const next = now => p.run(`nextOccurrence(b, new Date(${now}))`);
    assert.equal(next("2026, 2, 10"), "2026-03-14");
    assert.equal(next("2026, 2, 14, 18, 0"), "2026-03-14", "today, even late in the evening");
    assert.equal(next("2026, 2, 15"), "2027-03-14");
    assert.equal(next("2026, 11, 31"), "2027-03-14");
    p.run(`var future = ${yearly(2, "2040-05-01", "Future")}`);
    assert.equal(p.run(`nextOccurrence(future, new Date(2026, 9, 12))`), "2040-05-01", "not before its first year");
});

test("the Calendar marks a yearly reminder in every later year, and lists it with a tick box for that day but no dragging", () => {
    const p = page();
    p.run(`userReminders = [${yearly(1, "1990-03-14", "Asha birthday")}]; calMonth = new Date(2031, 2, 1); calSelected = "2031-03-14"; renderCalendar()`);
    assert.deepEqual(p.node("calGrid").find(n => n.className === "cal-remind").map(m => m.title), ["1 reminder to do"]);
    const row = p.node("calDay").find(n => n.className.startsWith("reminder-row"))[0];
    assert.equal(row.className, "reminder-row repeating");
    assert.equal(row.find(n => n.tag === "input").length, 1, "a tick box for this day");
    assert.ok(!row.draggable, "cannot be dragged");
    assert.ok(row.allText.includes("Asha birthday · 41 years"), row.allText);
    p.run(`calMonth = new Date(2031, 3, 1); calSelected = null; renderCalendar()`);
    assert.equal(p.node("calGrid").find(n => n.className === "cal-remind").length, 0, "another month");
    p.run(`calMonth = new Date(1989, 2, 1); renderCalendar()`);
    assert.equal(p.node("calGrid").find(n => n.className === "cal-remind").length, 0, "before its first year");
});

test("the bar shows a yearly reminder only in the week before, never late, with a tick box for its day but no Tomorrow", () => {
    const p = page();
    p.run(`var t = new Date(), at = n => { const d = new Date(t.getFullYear() - 30, t.getMonth(), t.getDate() + n); return isoDay(d); };
        userReminders = [${yearly(1, "x", "Soon")}, ${yearly(2, "x", "Today")}, ${yearly(3, "x", "Far")}, ${yearly(4, "x", "Yesterday")}];
        userReminders[0].date = at(3); userReminders[1].date = at(0); userReminders[2].date = at(20); userReminders[3].date = at(-1); renderReminders()`);
    const bar = p.node("remindersBar");
    const rows = bar.find(n => n.tag === "button");
    assert.equal(rows.length, 2, "yesterday's is next year's, and the far one is out of the window");
    assert.deepEqual(rows.map(b => b.className), ["reminder-item today", "reminder-item"]);
    assert.match(rows[0].allText, /^🔁 Today · 30 years/);
    assert.match(rows[0].allText, /today$/);
    assert.match(rows[1].allText, /in 3 days$/);
    assert.equal(bar.find(n => n.tag === "input").length, 2, "a tick box for each day");
    assert.equal(bar.find(n => n.className === "reminder-move").length, 0);
});

test("dragging cannot move a yearly reminder, and the form carries the Repeat setting", async () => {
    const p = page();
    p.run(`userReminders = [${yearly(5, "1990-03-14", "Asha")}]`);
    await p.raw(`moveReminder(5, "2031-03-20")`);
    assert.deepEqual(p.run("calls"), []);
    p.run(`openReminderForm(userReminders[0])`);
    assert.equal(p.node("reminderRepeat").value, "YEARLY");
    await p.raw("saveReminder()");
    assert.deepEqual(p.run("calls"), [{ path: "/api/reminders/5", method: "PUT", body: { date: "1990-03-14", title: "Asha", repeat: "YEARLY" } }]);
    p.run(`calls = []; openReminderForm(null, "2031-03-10")`);
    assert.equal(p.node("reminderRepeat").value, "NONE", "a new reminder does not repeat");
    p.node("reminderRepeat").value = "MONTHLY";
    p.node("reminderText").value = "Wedding anniversary";
    await p.raw("saveReminder()");
    assert.equal(p.run("calls[0].body.repeat"), "MONTHLY");
});

test("a weekly reminder falls on the same weekday every 7 days from its first day, not before", () => {
    const p = page();
    p.run(`var w = ${repeating(1, "2026-10-05", "Review spending", "WEEKLY")}`);       // a Monday
    for (const day of ["2026-10-05", "2026-10-12", "2026-10-19", "2026-11-02", "2027-01-04"]) assert.equal(p.run(`reminderFallsOn(w, "${day}")`), true, day);
    for (const day of ["2026-09-28", "2026-10-06", "2026-10-11", "2026-10-13"]) assert.equal(p.run(`reminderFallsOn(w, "${day}")`), false, day);
    assert.equal(p.run(`nextOccurrence(w, new Date(2026, 9, 5))`), "2026-10-05", "its first day, today");
    assert.equal(p.run(`nextOccurrence(w, new Date(2026, 9, 6))`), "2026-10-12");
    assert.equal(p.run(`nextOccurrence(w, new Date(2026, 9, 12, 22, 0))`), "2026-10-12", "today counts");
    assert.equal(p.run(`nextOccurrence(w, new Date(2026, 9, 1))`), "2026-10-05", "not started yet");
    assert.equal(p.run(`yearsText(w, "2027-01-04")`), "", "no years on a weekly one");
});

test("a monthly reminder falls on its day of the month; a short month uses its last day; never before the first day", () => {
    const p = page();
    p.run(`var m = ${repeating(1, "2026-01-31", "Pay the rent", "MONTHLY")}, d5 = ${repeating(2, "2026-03-05", "SIP", "MONTHLY")}`);
    assert.equal(p.run(`monthlyDay(m, 2026, 1)`), "2026-02-28");
    assert.equal(p.run(`monthlyDay(m, 2028, 1)`), "2028-02-29");
    assert.equal(p.run(`monthlyDay(m, 2026, 3)`), "2026-04-30");
    assert.equal(p.run(`monthlyDay(m, 2026, 4)`), "2026-05-31");
    assert.equal(p.run(`reminderFallsOn(m, "2026-02-28")`), true);
    assert.equal(p.run(`reminderFallsOn(m, "2026-02-27")`), false);
    assert.equal(p.run(`reminderFallsOn(d5, "2026-02-05")`), false, "before the first day");
    assert.equal(p.run(`reminderFallsOn(d5, "2026-03-05")`), true, "the first day");
    assert.equal(p.run(`reminderFallsOn(d5, "2031-07-05")`), true);
    assert.equal(p.run(`nextOccurrence(d5, new Date(2026, 9, 5))`), "2026-10-05", "today counts");
    assert.equal(p.run(`nextOccurrence(d5, new Date(2026, 9, 6))`), "2026-11-05");
    assert.equal(p.run(`nextOccurrence(d5, new Date(2026, 11, 20))`), "2027-01-05", "over a year end");
    assert.equal(p.run(`nextOccurrence(m, new Date(2026, 1, 1))`), "2026-02-28", "the 31st in February");
    assert.equal(p.run(`nextOccurrence(d5, new Date(2026, 1, 1))`), "2026-03-05", "not started yet");
});

test("the Calendar marks every day a weekly or monthly reminder falls on in the month, and lists it with its repeat", () => {
    const p = page();
    p.run(`userReminders = [${repeating(1, "2031-02-03", "Review spending", "WEEKLY")}, ${repeating(2, "2030-11-28", "Rent", "MONTHLY")}]`);
    assert.deepEqual(p.run("[...reminderDaysIn(2031, 2)].map(([d]) => d)"), ["2031-03-03", "2031-03-10", "2031-03-17", "2031-03-24", "2031-03-28", "2031-03-31"]);
    assert.deepEqual(p.run("[...reminderDaysIn(2031, 0)].map(([d]) => d)"), ["2031-01-28"], "weekly has not started, monthly has");
    p.run(`calMonth = new Date(2031, 2, 1); calSelected = "2031-03-10"; renderCalendar()`);
    const rows = p.node("calDay").find(n => n.className.startsWith("reminder-row"));
    assert.equal(rows.length, 1);
    assert.ok(rows[0].allText.includes("Review spending") && rows[0].allText.includes("every week"), rows[0].allText);
    assert.equal(rows[0].find(n => n.tag === "input").length, 1, "a tick box for that day");
});

test("the bar lists a weekly or monthly reminder from 7 days before, with the repeat icon, never late", () => {
    const p = page();
    p.run(`var t = new Date(), at = n => isoDay(new Date(t.getFullYear(), t.getMonth() - 3, t.getDate() + n));
        userReminders = [${repeating(1, "x", "Weekly soon", "WEEKLY")}, ${repeating(2, "x", "Monthly far", "MONTHLY")}, ${repeating(3, "x", "Monthly today", "MONTHLY")}];
        userReminders[0].date = isoDay(new Date(t.getFullYear(), t.getMonth(), t.getDate() - 7 * 4 + 2));   // next one in 2 days
        userReminders[1].date = at(15); userReminders[2].date = at(0); renderReminders()`);
    const rows = p.node("remindersBar").find(n => n.tag === "button");
    assert.equal(rows.length, 2, "the far monthly one is not listed");
    const names = rows.map(b => b.allText);
    assert.ok(names.some(t => t.startsWith("🔁 Weekly soon") && /in 2 days$/.test(t)), names.join("|"));
    assert.ok(names.some(t => t.startsWith("🔁 Monthly today") && /today$/.test(t)), names.join("|"));
    assert.ok(!names.some(t => t.includes("Monthly far")), "15 days away is outside the week");
    assert.ok(names.every(t => !t.includes("late")));
});

test("the Repeat drop-down has the four settings", () => {
    const html = require("node:fs").readFileSync(require("node:path").join(require("./harness").STATIC, "panels", "reminder-form.html"), "utf8");
    assert.deepEqual([...html.matchAll(/<option value="([A-Z]+)"/g)].map(m => m[1]), ["NONE", "WEEKLY", "MONTHLY", "YEARLY"]);
});

test("the section has up to three panels, Bills, Reminders and Recurring, and leaves out an empty one", () => {
    const p = page();
    p.run(`var t = new Date(), iso = n => isoDay(new Date(t.getFullYear(), t.getMonth(), t.getDate() + n));
        accounts = []; ledger = { configured: true, buckets: [{ name: "Wifi", buffer: false, nextPaymentDate: iso(1), paymentAmount: 10 }] };
        userReminders = [{ id: 1, date: iso(-1), title: "Call the CA", done: false, repeat: "NONE" },
                         { id: 2, date: iso(2), title: "Call home", done: false, repeat: "NONE" },
                         { id: 3, date: iso(3), title: "Rent", done: false, repeat: "NONE" }];
        userReminders[2].repeat = "WEEKLY"; userReminders[2].date = isoDay(new Date(t.getFullYear(), t.getMonth(), t.getDate() - 7 * 3 + 3));
        renderReminders()`);
    const bar = p.node("remindersBar");
    const panels = bar.find(n => n.tag === "section");
    assert.deepEqual(panels.map(x => x.find(n => n.className === "reminder-head")[0].allText), ["Bills1", "Reminders2", "Recurring1"]);
    assert.ok(panels[0].allText.includes("Wifi"));
    assert.ok(panels[1].allText.includes("Call the CA") && panels[1].allText.includes("Call home"));
    assert.equal(panels[1].find(n => n.tag === "input").length, 2, "your reminders have tick boxes");
    assert.ok(panels[2].allText.includes("Rent"));
    assert.equal(panels[2].find(n => n.tag === "input").length, 1, "a repeating one can be ticked for its day");
    p.run(`ledger = { configured: false }; userReminders = [userReminders[1]]; renderReminders()`);
    assert.deepEqual(bar.find(n => n.tag === "section").map(x => x.className), ["reminder-panel reminder-panel-reminders"],
        "Bills and Recurring are left out when they have nothing");
});

test("Done recently lists reminders ticked in the last week, newest first, with a ticked box that brings one back", async () => {
    const p = page();
    p.run(`accounts = []; ledger = { configured: false };
        userReminders = [
            { id: 1, date: iso(-9), title: "Old one", done: true, doneOn: iso(-8), repeat: "NONE" },
            { id: 2, date: iso(-3), title: "Pay the CA", done: true, doneOn: iso(-2), repeat: "NONE" },
            { id: 3, date: iso(0), title: "Call home", done: true, doneOn: iso(0), repeat: "NONE" },
            { id: 4, date: iso(2), title: "Still open", done: false, doneOn: null, repeat: "NONE" },
            { id: 5, date: iso(0), title: "Rent", done: true, doneOn: iso(0), repeat: "MONTHLY" }];
        renderReminders()`);
    const panels = p.node("remindersBar").find(n => n.tag === "section");
    assert.deepEqual(panels.map(x => x.className.split(" ")[1]), ["reminder-panel-reminders", "reminder-panel-recurring", "reminder-panel-done"],
        "Rent repeats monthly and falls today, so it is in Recurring and never in Done");
    panels.splice(1, 1);   // the Recurring panel
    const done = panels[1];
    assert.deepEqual(done.find(n => n.className === "ri-main").map(n => n.textContent), ["Call home", "Pay the CA"],
        "newest first; the one done 8 days ago and the repeating one are not listed");
    assert.deepEqual(done.find(n => n.className === "ri-when").map(n => n.textContent), ["done today", "done 2 days ago"]);
    const boxes = done.find(n => n.tag === "input");
    assert.equal(boxes.length, 2);
    assert.ok(boxes.every(b => b.checked === true), "ticked: unticking reopens it");
    p.run(`calls.length = 0`);
    boxes[0].checked = false;
    boxes[0].fire("change");
    await flush();
    assert.deepEqual(p.run("calls").map(c => [c.path, c.method]), [["/api/reminders/3/done", "PUT"]]);
});

test("the section shows even when only ticked reminders are left, and lists at most five with a note for the rest", () => {
    const p = page();
    const many = [1, 2, 3, 4, 5, 6, 7].map(i => `{ id: ${i}, date: iso(-1), title: "Done ${i}", done: true, doneOn: iso(0), repeat: "NONE" }`);
    p.run(`accounts = []; ledger = { configured: false }; userReminders = [${many.join(",")}]; renderReminders()`);
    const bar = p.node("remindersBar");
    assert.ok(!bar.classList.contains("hidden"), "something to undo, so the section stays");
    assert.equal(bar.find(n => n.className === "ri-main").length, 5);
    assert.match(bar.allText, /\+ 2 more: find them under Reminders/);
    p.run(`userReminders = []; renderReminders()`);
    assert.ok(bar.classList.contains("hidden"));
});

test("ticking a day of a repeating reminder ticks that day only; the next day takes over in Recurring and the ticked one is in Done recently", async () => {
    const p = page();
    p.run(`accounts = []; ledger = { configured: false };
        userReminders = [{ id: 7, date: iso(-14), title: "Rent", done: false, doneOn: null, repeat: "WEEKLY", completions: {} }];
        renderReminders()`);
    const first = p.run("upcomingDues()")[0];
    assert.equal(first.date, p.run("iso(0)"), "the weekly one falls today");
    p.run(`calls.length = 0`);
    const box = p.node("remindersBar").find(n => n.tag === "input")[0];
    box.checked = true;
    box.fire("change");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: `/api/reminders/7/occurrences/${first.date}/done`, method: "PUT", body: { done: true } }]);
    p.run(`userReminders[0].completions[iso(0)] = iso(0); renderReminders()`);
    assert.deepEqual(p.run("upcomingDues()").map(i => i.date), [p.run("iso(7)")], "the next one, a week on, takes over");
    const bar = p.node("remindersBar");
    const done = bar.find(n => n.tag === "section").find(x => x.className.includes("reminder-panel-done"));
    assert.ok(done && done.allText.includes("Rent"), "ticked, so in Done recently");
    assert.equal(p.run("[...reminderDaysIn(new Date().getFullYear(), new Date().getMonth())].some(([d]) => d === iso(0))"), false, "no bell on a ticked day");
});

test("a repeating reminder's tick box can be unticked and an untouched past day is just left alone (never late)", () => {
    const p = page();
    p.run(`accounts = []; ledger = { configured: false };
        userReminders = [{ id: 8, date: iso(-30), title: "Rent", done: false, doneOn: null, repeat: "WEEKLY", completions: {} }]; renderReminders()`);
    assert.ok(p.run("upcomingDues()").every(i => i.days >= 0), "a missed past day is not listed as late");
});

test("text or a file dropped on a day does not move a reminder, and the day does not light up for it", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(5, "2031-03-10", "Stay put")}]; calSelected = null; renderCalendar()`);
    const cell = p.node("calGrid").find(n => n.className.startsWith("cal-cell") && n.allText.startsWith("20"))[0];
    let prevented = false;
    cell.fire("dragover", { preventDefault() { prevented = true; }, dataTransfer: { types: ["text/plain"] } });
    assert.ok(!prevented && !cell.classList.contains("drop"));
    cell.fire("drop", { dataTransfer: { types: ["text/plain"], getData: () => "5" } });
    await flush();
    assert.deepEqual(p.run("calls.filter(c => c.method === 'PUT')"), []);
});

test("a day's cell shows the titles of its open one-off reminders as draggable chips (two, then '+N more'); repeating and done ones are not chips", async () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "2031-03-10", "First")}, ${reminder(2, "2031-03-10", "Second")}, ${reminder(3, "2031-03-10", "Third")},
                           { id: 4, date: "2031-03-10", title: "Rent", done: false, doneOn: null, repeat: "MONTHLY", completions: {} },
                           { id: 5, date: "2031-03-10", title: "Finished", done: true, doneOn: "2031-03-09", repeat: "NONE", completions: {} }];
           calSelected = null; renderCalendar()`);
    const cell = p.node("calGrid").find(n => n.className.startsWith("cal-cell") && n.allText.startsWith("10"))[0];
    const chips = cell.find(n => n.className === "cal-chip");
    assert.deepEqual(chips.map(c => c.textContent), ["First", "Second"]);
    assert.ok(chips.every(c => c.draggable === true));
    assert.ok(cell.allText.includes("+1 more"));
    assert.ok(!cell.allText.includes("Rent") && !cell.allText.includes("Finished"));
    // dragging a chip carries the reminder and dropping it on another day moves it there
    const store = {};
    chips[1].fire("dragstart", { dataTransfer: { setData: (k, v) => { store[k] = v; } } });
    assert.equal(store["application/x-financial-reminder"], "2");
    const other = p.node("calGrid").find(n => n.className.startsWith("cal-cell") && n.allText.startsWith("25"))[0];
    other.fire("drop", { dataTransfer: { types: ["application/x-financial-reminder"], getData: () => store["application/x-financial-reminder"] } });
    await flush();
    assert.deepEqual(p.run("calls.filter(c => c.method === 'PUT')"), [{ path: "/api/reminders/2", method: "PUT", body: { date: "2031-03-25", title: "Second" } }]);
});

test("a one-off reminder in the Coming up bar can be dragged onto a calendar day; a repeating one cannot", () => {
    const p = page();
    p.run(`userReminders = [${reminder(1, "x", "One-off")}, { id: 2, date: iso(1), title: "Rent", done: false, doneOn: null, repeat: "WEEKLY", completions: {} }];
           userReminders[0].date = iso(2); renderReminders()`);
    const items = p.node("remindersBar").find(n => n.tag === "button" && n.className.startsWith("reminder-item"));
    const one = items.find(b => b.allText.includes("One-off")), rent = items.find(b => b.allText.includes("Rent"));
    assert.equal(one.draggable, true);
    assert.ok(!rent.draggable);
    const store = {};
    one.fire("dragstart", { dataTransfer: { setData: (k, v) => { store[k] = v; } } });
    assert.equal(store["application/x-financial-reminder"], "1");
});
