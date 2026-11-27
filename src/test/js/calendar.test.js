const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/net-worth.js", "panels/period-filter.js", "core/tooltip.js", "panels/calendar-calc.js", "panels/my-reminders.js", "panels/calendar.js");
    p.run(`function txnRowElement(t) { return el("div", { className: "txn-row", textContent: t.note || "" }); }
           function openNote() {}  function noteCard(n) { return el("div", { className: "note-card", textContent: n.body }); }
           function api() { return Promise.resolve({ notes: [] }); }`);
    p.run("calMonth = new Date(2026, 9, 1); calSelected = null");   // October 2026 starts on a Thursday
    return p;
}
const cells = p => p.node("calGrid").children.filter(c => c.className.startsWith("cal-cell"));
const days = p => cells(p).filter(c => !c.className.includes("blank"));

test("month arithmetic keeps the day where the month is long enough, and clamps where it is not", () => {
    const p = page();
    assert.equal(p.run(`isoDay(addMonthsClamped(new Date(2026, 0, 31), 1))`), "2026-02-28");
    assert.equal(p.run(`isoDay(addMonthsClamped(new Date(2026, 0, 31), 3))`), "2026-04-30");
    assert.equal(p.run(`isoDay(addMonthsClamped(new Date(2026, 10, 15), 3))`), "2027-02-15");
    assert.equal(p.run(`isoDay(addMonthsClamped(new Date(2026, 4, 31), 12))`), "2027-05-31");
});

test("a bucket's payments repeat from the next payment date until its end date; the buffer has none", () => {
    const p = page();
    p.run(`ledger = { configured: true, buckets: [
        { name: "Insurance", buffer: false, nextPaymentDate: "2026-10-31", frequencyMonths: 3, paymentAmount: 5000, endDate: null },
        { name: "Tax", buffer: false, nextPaymentDate: "2026-08-31", frequencyMonths: 1, paymentAmount: 100, endDate: "2026-11-30" },
        { name: "One", buffer: false, nextPaymentDate: "2026-11-05", frequencyMonths: null, paymentAmount: 9 },
        { name: "Buffer", buffer: true, nextPaymentDate: "2026-11-05" }] }`);
    const due = (y, m) => p.run(`calendarDue(new Date(${y}, ${m}, 1), new Date(${y}, ${m + 1}, 0))`);
    assert.deepEqual(Object.keys(due(2026, 9)), ["2026-10-31"]);
    assert.deepEqual(due(2026, 9)["2026-10-31"].map(x => x.name), ["Insurance", "Tax"]);
    assert.deepEqual(Object.keys(due(2026, 10)).sort(), ["2026-11-05", "2026-11-30"]);
    assert.deepEqual(Object.keys(due(2026, 11)), [], "Tax ended on 30 Nov");
    assert.deepEqual(Object.keys(due(2027, 0)), ["2027-01-31"], "Insurance every 3 months");
    p.run(`ledger = { configured: false }`);
    assert.deepEqual(due(2026, 9), {});
});

test("October 2026 draws three blank cells, 31 days, today marked and the weeks starting on Monday", () => {
    const p = page();
    p.run("renderCalendar()");
    assert.equal(p.node("calMonthLabel").textContent, "October 2026");
    const dow = p.node("calGrid").children.filter(c => c.className === "cal-dow").map(c => c.textContent);
    assert.deepEqual(dow, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]);
    assert.equal(cells(p).filter(c => c.className.includes("blank")).length, 3);
    assert.equal(days(p).length, 31);
    assert.equal(days(p)[0].children[0].textContent, "1");
});

test("a day shows money in and out, notes and Ledger payments; transfers are left out of the month's totals", () => {
    const p = page();
    p.run(`transactions = [
        { date: "2026-10-01", kind: "INCOME", amount: 130000 },
        { date: "2026-10-02", kind: "EXPENSE", amount: 28000 },
        { date: "2026-10-02", kind: "EXPENSE", amount: 2000 },
        { date: "2026-10-03", kind: "TRANSFER", amount: 99999 },
        { date: "2026-09-30", kind: "EXPENSE", amount: 777 }];
        noteDays = new Map([["2026-10-02", 2]]);
        ledger = { configured: true, buckets: [{ name: "Insurance", buffer: false, nextPaymentDate: "2026-10-15", frequencyMonths: 12, paymentAmount: 6000 }] };
        renderCalendar()`);
    const totals = p.node("calTotals").children[0].allText;
    assert.match(totals, /October 2026/);
    assert.match(totals, /In ₹1,30,000/);
    assert.match(totals, /Out ₹30,000/);
    assert.match(totals, /Net ₹1,00,000/);
    const d = n => days(p)[n - 1];
    assert.match(d(1).allText, /\+₹1,30,000/);
    assert.match(d(2).allText, /−₹30,000/);
    assert.match(d(2).allText, /📝/);
    assert.equal(d(3).allText, "3", "a transfer alone leaves a day empty");
    assert.match(d(15).allText, /Pay Insurance/);
    assert.equal(p.node("calTotals").children.length, 1, "no second line without a From / To range");
});

test("a From / To range highlights its days and adds a second totals line; Today does not", () => {
    const p = page();
    p.run(`transactions = [{ date: "2026-10-05", kind: "INCOME", amount: 1000 }, { date: "2026-10-20", kind: "EXPENSE", amount: 400 }]`);
    p.run(`periodRange = "CUSTOM"; periodFrom = "2026-10-04"; periodTo = "2026-10-10"; renderCalendar()`);
    const inRange = days(p).filter(c => c.className.includes("in-range")).map(c => c.children[0].textContent);
    assert.deepEqual(inRange, ["4", "5", "6", "7", "8", "9", "10"]);
    assert.equal(p.node("calTotals").children.length, 2);
    assert.match(p.node("calTotals").children[1].allText, /In ₹1,000.*Out ₹0/);
    p.run(`periodRange = "TODAY"; renderCalendar()`);
    assert.equal(p.node("calTotals").children.length, 1);
});

test("a range jumps the calendar to its month, and Today opens that day", () => {
    const p = page();
    p.run(`periodRange = "CUSTOM"; periodFrom = "2026-03-14"; periodTo = "2026-03-20"; calendarFollowPeriod()`);
    assert.equal(p.run(`isoDay(calMonth)`), "2026-03-01");
    assert.equal(p.run("calSelected"), null);
    p.run(`periodRange = "TODAY"; periodFrom = periodTo = "2026-06-09"; calendarFollowPeriod()`);
    assert.equal(p.run(`isoDay(calMonth)`), "2026-06-01");
    assert.equal(p.run("calSelected"), "2026-06-09");
    p.run(`periodRange = "ALL"; periodFrom = periodTo = ""; calendarFollowPeriod()`);
    assert.equal(p.run(`isoDay(calMonth)`), "2026-06-01", "clearing the range leaves the month where it was");
    assert.equal(p.run("calSelected"), null);
});

test("clicking a day lists its transactions and Ledger payment, with a button to add a note", () => {
    const p = page();
    p.run(`transactions = [{ date: "2026-10-12", kind: "EXPENSE", amount: 5, note: "Coffee" }];
           ledger = { configured: true, buckets: [{ name: "Wifi", buffer: false, nextPaymentDate: "2026-10-12", frequencyMonths: 1, paymentAmount: 500 }] };
           noteDays = new Map(); renderCalendar()`);
    days(p)[11].fire("click");
    assert.equal(p.run("calSelected"), "2026-10-12");
    const box = p.node("calDay");
    assert.match(box.allText, /Monday, 12 October 2026/);
    assert.match(box.allText, /Ledger payment due: Wifi/);
    assert.match(box.allText, /Coffee/);
    assert.ok(box.find(n => n.textContent === "+ Add note").length === 1);
    days(p)[11].fire("click");
    assert.equal(p.run("calSelected"), null, "a second click closes the day");
    assert.equal(p.node("calDay").children.length, 0);
});

test("an empty day says so", () => {
    const p = page();
    p.run(`transactions = []; ledger = { configured: false }; noteDays = new Map(); calSelected = "2026-10-20"; renderCalendar()`);
    assert.match(p.node("calDay").allText, /Nothing recorded on this day/);
});

test("a card's bill day is marked in every month, with what is owed only from today on", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Travel Card", type: "CREDIT_CARD", active: true, balance: 23450 },
                       { id: 2, name: "Closed", type: "CREDIT_CARD", active: false, balance: 5 },
                       { id: 3, name: "No day", type: "CREDIT_CARD", active: true, balance: 5 }];
           cardDueDays = new Map([[1, 31], [2, 10], [3, 0]]); ledger = { configured: false }`);
    const due = (y, m) => p.run(`calendarDue(new Date(${y}, ${m}, 1), new Date(${y}, ${m + 1}, 0))`);
    assert.deepEqual(Object.keys(due(2026, 9)), ["2026-10-31"]);
    assert.deepEqual(Object.keys(due(2027, 1)), ["2027-02-28"], "31 in a short month is the last day");
    assert.deepEqual(Object.keys(due(2028, 1)), ["2028-02-29"]);
    assert.equal(due(2020, 4)["2020-05-31"][0].amount, null, "a past bill day has no amount to show");
    const future = due(2099, 4)["2099-05-31"][0];
    assert.deepEqual([future.kind, future.name, future.amount], ["card", "Travel Card", 23450]);
});

test("with a statement day the bill amount on the Calendar is the statement's amount, not everything owed", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Travel Card", type: "CREDIT_CARD", active: true, balance: 23450 }];
           cardDueDays = new Map([[1, 31]]); cardStatementDays = new Map([[1, 10]]); ledger = { configured: false };
           var billOf = { amount: 9000, statement: "2099-05-10" };
           function statementDue() { return billOf; }          // the real one is tested with the Coming up bar`);
    const bill = () => p.run(`calendarDue(new Date(2099, 4, 1), new Date(2099, 5, 0))["2099-05-31"].find(x => x.kind === "card")`);
    assert.deepEqual([bill().amount, bill().statement], [9000, "2099-05-10"], "the statement's amount, not 23,450");
    p.run(`billOf = null`);
    assert.equal(bill().amount, null, "that statement has not been generated yet, so no amount");
    p.run(`cardStatementDays = new Map()`);
    assert.equal(bill().amount, 23450, "without a statement day it is everything owed, as before");
});

test("a card's bill day shows on the cell and in the day's list, next to a Ledger payment on the same day", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Travel Card", type: "CREDIT_CARD", active: true, balance: 23450 }]; cardDueDays = new Map([[1, 31]]);
           ledger = { configured: true, buckets: [{ name: "Wifi", buffer: false, nextPaymentDate: "2099-05-31", frequencyMonths: 1, paymentAmount: 500 }] };
           transactions = []; noteDays = new Map(); calMonth = new Date(2099, 4, 1); renderCalendar()`);   // far ahead, so "today" does not matter
    const cell = days(p)[30];
    assert.match(cell.allText, /2 payments due/);
    p.run(`ledger = { configured: false }; calSelected = "2099-05-31"; renderCalendar()`);
    assert.match(days(p)[30].allText, /Travel Card bill due/);
    assert.match(p.node("calDay").allText, /Card bill due: Travel Card · ₹23,450 owed now/);
});

test("a day with only foreign-currency expenses shows their rupee sum, at the rate of that day, and the month total includes it", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Bank", type: "BANK", currency: "INR" }, { id: 2, name: "Hanoi", type: "CASH", currency: "VND" }];
        exchangeRates = [{ currency: "VND", date: "2026-10-01", rate: 0.0036 }];
        transactions = [{ id: 1, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, toAccountId: null, amount: 100000, categoryId: null, note: "Pho" },
                        { id: 2, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, toAccountId: null, amount: 50000, categoryId: null, note: "Coffee" },
                        { id: 3, date: "2026-10-04", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 200, categoryId: null, note: "Tea" }];
        renderCalendar();`);
    const text = c => c.allText;
    const third = days(p)[2], fourth = days(p)[3];
    assert.ok(text(third).includes("−₹540"), text(third));   // 150000 Dong x 0.0036
    assert.ok(text(fourth).includes("−₹200"), text(fourth));
    assert.ok(p.node("calTotals").allText.includes("₹740"), p.node("calTotals").allText);
});

test("a Balance Adjustment (a reset balance or a written-off debt) is not money in or out on the calendar", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Bank", type: "BANK", currency: "INR" }];
        allCategories = [{ id: 7, name: "Balance Adjustment", kind: "INCOME", parentId: null, systemKey: "BALANCE_ADJUSTMENT_INCOME" }];
        transactions = [{ id: 1, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 100, categoryId: null, note: "Tea" },
                        { id: 2, date: "2026-10-03", kind: "INCOME", fromAccountId: null, toAccountId: 1, amount: 1400000, categoryId: 7, note: "waived" }];
        renderCalendar();`);
    const totals = p.node("calTotals").allText;
    assert.ok(totals.includes("Out ₹100"), totals);
    assert.ok(totals.includes("In ₹0"), totals);
});


/* ---------- the heatmaps ---------- */

const MONTH = `accounts = [{ id: 1, name: "Bank", type: "BANK", currency: "INR" }];
    allCategories = [{ id: 7, name: "Loans Given", kind: "EXPENSE", parentId: null, systemKey: "LOANS_GIVEN" }];
    transactions = [
        { id: 1, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, amount: 100, categoryId: null },
        { id: 2, date: "2026-10-05", kind: "EXPENSE", fromAccountId: 1, amount: 400, categoryId: null },
        { id: 3, date: "2026-10-05", kind: "EXPENSE", fromAccountId: 1, amount: 100, categoryId: null },
        { id: 4, date: "2026-10-09", kind: "EXPENSE", fromAccountId: 1, amount: 2500, categoryId: null },
        { id: 5, date: "2026-10-09", kind: "EXPENSE", fromAccountId: 1, amount: 99999, categoryId: 7 },
        { id: 6, date: "2026-10-12", kind: "EXPENSE", fromAccountId: 1, amount: 900, categoryId: null },
        { id: 7, date: "2026-10-03", kind: "INCOME", toAccountId: 1, amount: 50000, categoryId: null },
        { id: 8, date: "2026-10-20", kind: "INCOME", toAccountId: 1, amount: 700, categoryId: null },
        { id: 9, date: "2026-10-04", kind: "TRANSFER", fromAccountId: 1, toAccountId: 1, amount: 777 }];`;
const mode = (p, name) => p.node({ calendar: "calModeCalendar", expense: "calModeExpense", income: "calModeIncome" }[name]).fire("click");
const heatClass = c => (c.className.match(/heat-l(\d)/) || [])[1];

test("the mode buttons switch the same grid between Calendar, Expenses and Income, and the month and chosen day stay", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); calSelected = "2026-10-09"; renderCalendar()`);
    assert.equal(days(p).some(c => c.className.includes("heat")), false, "the plain calendar to begin with");
    mode(p, "expense");
    assert.equal(days(p).length, 31);
    assert.ok(days(p).every(c => c.className.includes("heat-expense")));
    assert.match(p.node("calMonthLabel").textContent, /October 2026/);
    assert.ok(days(p)[8].className.includes("selected"), "the chosen day is still chosen");
    mode(p, "income");
    assert.ok(days(p).every(c => c.className.includes("heat-income") && !c.className.includes("heat-expense")));
    mode(p, "calendar");
    assert.equal(days(p).some(c => c.className.includes("heat")), false, "back to the original calendar");
    assert.match(days(p)[1].allText, /−₹100/, "with its in and out amounts again");
    assert.equal(p.node("calHeatSummary").classList.contains("hidden"), true);
});

test("the expense heatmap colours by the day's own spending: none is level 0, the biggest 4, loans and transfers do not count", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); renderCalendar()`);
    mode(p, "expense");
    const level = n => heatClass(days(p)[n - 1]);
    assert.equal(level(1), "0");
    assert.equal(level(4), "0", "a transfer is not spending");
    assert.equal(level(2), "1");     // 100
    assert.equal(level(5), "2");     // 500
    assert.equal(level(12), "2");    // 900: about a third of the biggest day, and in the upper half of the days
    assert.equal(level(9), "4", "2,500: the Loans Given of 99,999 on that day is left out");
    assert.match(days(p)[8].allText, /₹2,500/);
    assert.doesNotMatch(days(p)[8].allText, /99,999/);
});

test("the income heatmap is separate from the expense one", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); renderCalendar()`);
    mode(p, "income");
    assert.equal(heatClass(days(p)[2]), "4");   // 3 October, 50,000
    assert.equal(heatClass(days(p)[19]), "1");  // 20 October, 700: the lower of two income days, and a tiny part of the biggest
    assert.equal(heatClass(days(p)[1]), "0", "a day with spending but no income");
    assert.match(days(p)[2].allText, /₹50,000/);
});

test("the summary changes with the mode: total, active days, quiet days, highest day, average per active day", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); renderCalendar()`);
    mode(p, "expense");
    let text = p.node("calHeatSummary").allText;
    assert.match(text, /Total expenses ₹4,000\.00/);
    assert.match(text, /Spending days 4/);
    assert.match(text, /No-spend days 27/);
    assert.match(text, /Highest day 9 October · ₹2,500\.00/);
    assert.match(text, /Average per spending day ₹1,000\.00/);
    mode(p, "income");
    text = p.node("calHeatSummary").allText;
    assert.match(text, /Total income ₹50,700\.00/);
    assert.match(text, /Income days 2/);
    assert.match(text, /No-income days 29/);
});

test("every heatmap day has an accessible label with its date, amounts and level, and a day without activity says so", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); renderCalendar()`);
    mode(p, "expense");
    const busy = days(p)[4].attrs ? days(p)[4].attrs["aria-label"] : days(p)[4].getAttribute("aria-label");
    assert.match(busy, /5 October 2026/);
    assert.match(busy, /Total expenses: ₹500\.00/);
    assert.match(busy, /Transactions: 2/);
    assert.match(busy, /Average transaction: ₹250\.00/);
    assert.match(busy, /Level: low/);
    const quiet = days(p)[0].attrs ? days(p)[0].attrs["aria-label"] : days(p)[0].getAttribute("aria-label");
    assert.match(quiet, /No expenses/);
});

test("a month in the future has neutral days, no levels and no quiet days", () => {
    const p = page();
    p.run(`${MONTH}; calMonth = new Date(2099, 0, 1); initCalendar(); renderCalendar()`);
    mode(p, "expense");
    assert.equal(days(p).length, 31);
    assert.ok(days(p).every(c => c.className.includes("future") && !c.className.includes("heat-l1") && !c.className.includes("heat-l4")));
    assert.match(p.node("calHeatSummary").allText, /No-spend days 0/);
});

test("a heatmap day's list has a link that opens Transactions on that day and kind", () => {
    const p = page();
    p.run(`${MONTH}; var jumped = []; function showTransactionsFor(o) { jumped.push(o); }; initCalendar(); calSelected = "2026-10-09"; renderCalendar()`);
    assert.equal(p.node("calDay").find(n => n.className === "txn-link").length, 0, "none in the plain calendar");
    mode(p, "expense");
    p.node("calDay").find(n => n.className === "txn-link")[0].fire("click");
    mode(p, "income");
    p.node("calDay").find(n => n.className === "txn-link")[0].fire("click");
    assert.deepEqual(p.run("jumped"), [{ kind: "EXPENSE", from: "2026-10-09", to: "2026-10-09" }, { kind: "INCOME", from: "2026-10-09", to: "2026-10-09" }]);
});

test("a foreign-currency expense counts in rupees at the rate of its day, and one with no rate is left out", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Bank", type: "BANK", currency: "INR" }, { id: 2, name: "Hanoi", type: "CASH", currency: "VND" }];
        exchangeRates = [{ currency: "VND", date: "2026-10-01", rate: 0.0036 }];
        allCategories = [];
        transactions = [{ id: 1, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, amount: 100000, categoryId: null },
                        { id: 2, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, amount: 50000, categoryId: null }];
        initCalendar(); renderCalendar()`);
    mode(p, "expense");
    assert.match(days(p)[2].allText, /₹540/);
    p.run(`exchangeRates = []; renderCalendar()`);
    assert.doesNotMatch(days(p)[2].allText, /₹/, "no rate: left out, as everywhere else");
});

test("the arrow keys move between the days", () => {
    const p = page();
    p.run(`${MONTH}; initCalendar(); renderCalendar(); var focused = []; for (const c of $("calGrid").children) if (c.dataset && c.dataset.iso) c.focus = () => focused.push(c.dataset.iso)`);
    const cell = days(p)[9];
    p.node("calGrid").fire("keydown", { key: "ArrowRight", target: cell, preventDefault() {} });
    p.node("calGrid").fire("keydown", { key: "ArrowDown", target: cell, preventDefault() {} });
    p.node("calGrid").fire("keydown", { key: "ArrowLeft", target: cell, preventDefault() {} });
    assert.deepEqual(p.run("focused"), ["2026-10-11", "2026-10-17", "2026-10-09"]);
});
