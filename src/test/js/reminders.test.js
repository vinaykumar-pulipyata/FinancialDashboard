const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* "Coming up": card bills and Ledger payments due within a week. Every test pins "now" to Monday 12 October 2026. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/insights-calc.js", "panels/budget-calc.js", "panels/reminders.js");
    p.run(`var NOW = new Date(2026, 9, 12, 15, 30);
           var calMonth = null, calSelected = null, wsView = "stats", views = [], rendered = 0;
           function setCalMonth(v) { calMonth = v; } function setCalSelected(v) { calSelected = v; }
           function showWorkspaceView(v) { views.push(v); wsView = v; }
           function renderCalendar() { rendered++; }
           function cardStatementAmount(card, iso) { return iso === "2026-10-12" ? 7777 : -1; }   // the real one is tested with the Transactions list`);
    return p;
}
const dues = p => p.run("upcomingDues(NOW)");

test("a bill due on a day of the month falls on this month's day if it has not passed, else on next month's", () => {
    const p = page();
    const next = (day, from) => p.run(`isoDay(nextDueDate(${day}, new Date(${from})))`);
    assert.equal(next(21, "2026, 9, 12"), "2026-10-21");
    assert.equal(next(12, "2026, 9, 12"), "2026-10-12", "today counts");
    assert.equal(next(11, "2026, 9, 12"), "2026-11-11", "yesterday has passed");
    assert.equal(next(5, "2026, 11, 20"), "2027-01-05", "over a year end");
});

test("a short month uses its last day, and a leap year is respected", () => {
    const p = page();
    const next = (day, from) => p.run(`isoDay(nextDueDate(${day}, new Date(${from})))`);
    assert.equal(next(31, "2026, 1, 10"), "2026-02-28");
    assert.equal(next(31, "2028, 1, 10"), "2028-02-29");
    assert.equal(next(30, "2026, 3, 10"), "2026-04-30");
    assert.equal(next(31, "2026, 1, 28"), "2026-02-28", "the last day of February is still today");
    assert.equal(next(31, "2026, 2, 1"), "2026-03-31");
});

test("a card is listed when it has a due day, owes something and the day is within a week", () => {
    const p = page();
    p.run(`accounts = [
        { id: 1, name: "Travel Card", type: "CREDIT_CARD", active: true, balance: 23450 },
        { id: 2, name: "Far Card", type: "CREDIT_CARD", active: true, balance: 500 },
        { id: 3, name: "Paid-off Card", type: "CREDIT_CARD", active: true, balance: 0 },
        { id: 4, name: "No-day Card", type: "CREDIT_CARD", active: true, balance: 900 },
        { id: 5, name: "Old Card", type: "CREDIT_CARD", active: false, balance: 900 },
        { id: 6, name: "Bank", type: "BANK", active: true, balance: 900 }];
        cardDueDays = new Map([[1, 15], [2, 25], [3, 14], [5, 14], [6, 14]])`);
    const list = dues(p);
    assert.deepEqual(list.map(i => [i.name, i.date, i.days, i.amount]), [["Travel Card", "2026-10-15", 3, 23450]]);
});

test("a card due today or tomorrow is listed with those words", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "A", type: "CREDIT_CARD", active: true, balance: 100 }, { id: 2, name: "B", type: "CREDIT_CARD", active: true, balance: 100 }];
           cardDueDays = new Map([[1, 12], [2, 13]])`);
    assert.deepEqual(dues(p).map(i => [i.name, i.days]), [["A", 0], ["B", 1]]);
    assert.match(p.run(`reminderText(upcomingDues(NOW)[0])`), /A: ₹100 owed, bill due Mon, 12 Oct \(today\)|A: ₹100 owed, bill due Mon 12 Oct \(today\)/);
    assert.match(p.run(`reminderText(upcomingDues(NOW)[1])`), /\(tomorrow\)/);
});

test("Ledger payments are listed from a month late up to a week ahead, but not the Buffer or buckets without a date", () => {
    const p = page();
    p.run(`ledger = { configured: true, buckets: [
        { name: "Wifi", buffer: false, nextPaymentDate: "2026-10-14", paymentAmount: 1100 },
        { name: "Insurance", buffer: false, nextPaymentDate: "2026-10-25", paymentAmount: 24000 },
        { name: "Late bill", buffer: false, nextPaymentDate: "2026-10-05", paymentAmount: 300 },
        { name: "Ancient", buffer: false, nextPaymentDate: "2026-08-01", paymentAmount: 9 },
        { name: "No amount", buffer: false, nextPaymentDate: "2026-10-16", paymentAmount: null },
        { name: "No date", buffer: false, nextPaymentDate: null, paymentAmount: 5 },
        { name: "Buffer", buffer: true, nextPaymentDate: "2026-10-13" }] }`);
    assert.deepEqual(dues(p).map(i => [i.name, i.days, i.amount]), [["Late bill", -7, 300], ["Wifi", 2, 1100], ["No amount", 4, null]]);
    p.run(`ledger = { configured: false }`);
    assert.deepEqual(dues(p), []);
});

test("everything is sorted soonest first, late ones on top, then by name", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Zed Card", type: "CREDIT_CARD", active: true, balance: 10 }]; cardDueDays = new Map([[1, 14]]);
           ledger = { configured: true, buckets: [
             { name: "Alpha", buffer: false, nextPaymentDate: "2026-10-14", paymentAmount: 1 },
             { name: "Beta", buffer: false, nextPaymentDate: "2026-10-10", paymentAmount: 1 }] }`);
    assert.deepEqual(dues(p).map(i => i.name), ["Beta", "Alpha", "Zed Card"]);
});

test("the wording: late, today, tomorrow, in N days", () => {
    const p = page();
    assert.deepEqual([-3, -1, 0, 1, 5].map(d => p.run(`whenText(${d})`)), ["3 days late", "1 day late", "today", "tomorrow", "in 5 days"]);
    p.run(`ledger = { configured: true, buckets: [{ name: "Wifi", buffer: false, nextPaymentDate: "2026-10-09", paymentAmount: 1100 }] }`);
    assert.match(p.run(`reminderText(upcomingDues(NOW)[0])`), /^📦 Wifi: ₹1,100 due Fri,? 9 Oct \(3 days late\)$/);
    p.run(`ledger = { configured: true, buckets: [{ name: "Wifi", buffer: false, nextPaymentDate: "2026-10-14", paymentAmount: null }] }`);
    assert.match(p.run(`reminderText(upcomingDues(NOW)[0])`), /^📦 Wifi due /, "no amount, no colon");
});

test("the bar is hidden when nothing is due, and lists each item otherwise, late and today in bold", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/insights-calc.js", "panels/budget-calc.js", "panels/reminders.js");
    p.run(`var wsView = "calendar", calMonth = null, calSelected = null; function setCalMonth(v) { calMonth = v; } function setCalSelected(v) { calSelected = v; } function showWorkspaceView() {} function renderCalendar() {}`);
    // the real clock is used by renderReminders(), so build the data relative to today
    p.run(`accounts = []; ledger = { configured: false }; renderReminders()`);
    assert.ok(p.node("remindersBar").classList.contains("hidden"));
    p.run(`var t = new Date(); var iso = n => isoDay(new Date(t.getFullYear(), t.getMonth(), t.getDate() + n));
           ledger = { configured: true, buckets: [
             { name: "Late", buffer: false, nextPaymentDate: iso(-2), paymentAmount: 10 },
             { name: "Now", buffer: false, nextPaymentDate: iso(0), paymentAmount: 20 },
             { name: "Soon", buffer: false, nextPaymentDate: iso(3), paymentAmount: 30 },
             { name: "Later", buffer: false, nextPaymentDate: iso(20), paymentAmount: 40 }] };
           renderReminders()`);
    const bar = p.node("remindersBar");
    assert.ok(!bar.classList.contains("hidden"));
    const rows = bar.find(n => n.tag === "button");
    assert.equal(rows.length, 3, "the one 20 days away is not listed");
    assert.deepEqual(rows.map(r => r.className), ["reminder-item late", "reminder-item today", "reminder-item"]);
    assert.match(bar.allText, /^Bills3/, "one panel, Bills, with a count of 3");
});

test("clicking an item opens that day in the Calendar", () => {
    const p = page();
    p.run(`showReminderDay("2026-11-03")`);
    assert.equal(p.run("isoDay(calMonth)"), "2026-11-01");
    assert.equal(p.run("calSelected"), "2026-11-03");
    assert.deepEqual(p.run("views"), ["calendar"], "it switches to the Calendar from another view");
    assert.equal(p.run("rendered"), 1);
    p.run(`views = []; wsView = "calendar"; showReminderDay("2026-11-04")`);
    assert.deepEqual(p.run("views"), [], "already there: no switch");
    assert.equal(p.run("rendered"), 2);
});

test("the look-ahead is exactly seven days: the seventh is listed, the eighth is not", () => {
    const p = page();
    p.run(`ledger = { configured: true, buckets: [
        { name: "Day 7", buffer: false, nextPaymentDate: "2026-10-19", paymentAmount: 1 },
        { name: "Day 8", buffer: false, nextPaymentDate: "2026-10-20", paymentAmount: 1 },
        { name: "Late 30", buffer: false, nextPaymentDate: "2026-09-12", paymentAmount: 1 },
        { name: "Late 31", buffer: false, nextPaymentDate: "2026-09-11", paymentAmount: 1 }] }`);
    assert.deepEqual(dues(p).map(i => i.name), ["Late 30", "Day 7"], "30 days late is still listed, 31 is not");
});


/* A card with a statement day: the bill is the statement's amount, not everything owed now. Now is 12 October 2026. */
function cardPage(statementDay, dueDay, txns) {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Visa", type: "CREDIT_CARD", active: true, openingBalance: 0, balance: 13000 }];
        cardDueDays = new Map([[1, ${dueDay}]]); cardStatementDays = new Map([[1, ${statementDay}]]);
        transactions = ${JSON.stringify(txns)}`);
    return p;
}
const spend = (date, amount, fee = null) => ({ date, amount, fee, fromAccountId: 1, toAccountId: null, kind: "EXPENSE" });
const pay = (date, amount) => ({ date, amount, fee: null, fromAccountId: 7, toAccountId: 1, kind: "EXPENSE" });

test("with a statement day the bill is what was owed on the statement date, not purchases made since", () => {
    const p = cardPage(5, 18, [spend("2026-09-20", 10000), spend("2026-10-08", 3000)]);   // 3,000 was spent after the 5th statement
    const [card] = dues(p);
    assert.equal(card.amount, 10000);
    assert.equal(card.statement, "2026-10-05");
    assert.equal(card.date, "2026-10-18", "the due day is a different day from the statement day");
});

test("a payment made after the statement reduces the bill, a full payment removes it, fees count as spending", () => {
    assert.equal(dues(cardPage(5, 18, [spend("2026-09-20", 10000), pay("2026-10-09", 4000)]))[0].amount, 6000);
    assert.deepEqual(dues(cardPage(5, 18, [spend("2026-09-20", 10000), pay("2026-10-09", 10000)])), [], "paid in full: nothing is due");
    assert.equal(dues(cardPage(5, 18, [spend("2026-09-20", 10000, 250)]))[0].amount, 10250, "a fee on a purchase is part of what was owed");
    assert.equal(dues(cardPage(5, 18, [spend("2026-09-20", 10000), pay("2026-10-20", 4000)]))[0].amount, 10000, "a payment dated in the future is not counted yet");
});

test("when the due day is before the statement day the next bill is for a statement that does not exist yet, so nothing is listed", () => {
    // statement on the 20th, due on the 10th of the next month: on 12 October the next due date is 10 November, for the 20 October statement
    const p = cardPage(20, 10, [spend("2026-09-20", 13000)]);
    assert.deepEqual(dues(p), []);
    assert.equal(p.run(`statementDue(accounts[0], 20, new Date(2026, 10, 10), new Date(2026, 9, 12))`), null);
    const after = p.run(`statementDue(accounts[0], 20, new Date(2026, 10, 10), new Date(2026, 9, 22))`);   // on 22 October the statement exists
    assert.deepEqual(after, { amount: 13000, statement: "2026-10-20" });
});

test("a statement day in a short month uses its last day, and the reminder text names the statement", () => {
    const p = cardPage(31, 10, []);
    assert.equal(p.run(`isoDay(statementBefore(31, new Date(2026, 2, 10)))`), "2026-02-28");
    const q = cardPage(5, 18, [spend("2026-09-20", 10000)]);
    assert.match(q.run(`reminderText(upcomingDues(NOW)[0])`), /Visa: ₹10,000 on the statement of 5 Oct, bill due Sun, 18 Oct \(in 6 days\)/);
});

test("a card without a statement day is treated as before: everything owed is due", () => {
    const p = cardPage(5, 18, []);
    p.run(`cardStatementDays = new Map()`);
    const [card] = dues(p);
    assert.equal(card.amount, 13000);
    assert.equal(card.statement, null);
});

test("a card's statement day is a monthly reminder: listed in Bills from a week before, with the amount only on the day, never late", () => {
    const soon = dues(cardPage(14, 25, []));
    assert.deepEqual(soon.map(i => [i.kind, i.date, i.days]), [["statement", "2026-10-14", 2]], "the 25th bill is beyond a week");
    assert.equal(soon[0].amount, 13000, "before the day: what is outstanding so far");
    const today = dues(cardPage(12, 25, []));
    assert.equal(today[0].days, 0);
    assert.equal(today[0].amount, 7777, "on the day: the amount of the statement (owed at the end of that day)");
    assert.deepEqual(dues(cardPage(11, 25, [])), [], "yesterday's has passed: the next one is a month away, and a statement is never late");
    const p = cardPage(14, 25, []);
    const parts = p.run(`rowParts(upcomingDues(NOW)[0])`);
    assert.equal(parts.main, "🧾 Visa statement");
    assert.match(parts.detail, /^₹13,000 outstanding so far · /);
    assert.ok(p.run(`PANELS[0].has(upcomingDues(NOW)[0])`), "it is in the Bills panel");
    assert.match(p.run(`reminderText(upcomingDues(NOW)[0])`), /^🧾 Visa statement is generated Wed,? 14 Oct/);
});

test("a purchase or payment on the statement day itself belongs to the next statement", () => {
    const p = cardPage(5, 18, [spend("2026-09-20", 10000), spend("2026-10-05", 3000), pay("2026-10-05", 500)]);
    const [card] = dues(p);
    assert.equal(card.statement, "2026-10-05");
    assert.equal(card.amount, 10000 - 500, "the 3,000 spent on the 5th is not in the statement; the 500 paid on the 5th counts as paid since");
});

test("a budget of this month that is close to or over its limit is listed in a Budgets panel that opens the Budgets view", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/insights-calc.js", "panels/budget-calc.js", "panels/reminders.js");
    p.run(`var wsView = "calendar", views = []; function setCalMonth() {} function setCalSelected() {} function showWorkspaceView(v) { views.push(v); } function renderCalendar() {}
           accounts = []; ledger = { configured: false }; renderReminders()`);
    assert.ok(p.node("remindersBar").classList.contains("hidden"), "no budgets, nothing coming up: no bar");
    // a day of the month running that is not in the future, so the sums count it whatever today is
    p.run(`allCategories = [{ id: 1, name: "Food", parentId: null }];
           transactions = [{ id: 1, date: isoDay(new Date()), kind: "EXPENSE", amount: "900", categoryId: 1 }];
           budgets = [{ id: 1, categoryId: 1, amount: "1000" }];
           renderReminders()`);
    const bar = p.node("remindersBar");
    assert.ok(!bar.classList.contains("hidden"), "the budget alone shows the bar");
    assert.match(bar.allText, /^Budgets1/);
    const row = bar.find(n => n.tag === "button")[0];
    assert.equal(row.className, "reminder-item close");
    assert.match(row.attrs["aria-label"], /Food: 90% used/);
    row.fire("click");
    assert.deepEqual(p.run("views"), ["budgets"]);
    p.run(`budgets = [{ id: 1, categoryId: 1, amount: "5000" }]; renderReminders()`);
    assert.ok(p.node("remindersBar").classList.contains("hidden"), "plenty left: no alert");
});
