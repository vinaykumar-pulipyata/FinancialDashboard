const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

const page = (opts) => createPage(opts).load("core/constants.js", "core/util.js");

test("rupees formats Indian digit grouping, two decimals at most, and tolerates nothing", () => {
    const p = page();
    assert.equal(p.run("rupees(2148215)"), "₹21,48,215");
    assert.equal(p.run("rupees(1234.567)"), "₹1,234.57");
    assert.equal(p.run("rupees('250')"), "₹250");
    assert.equal(p.run("rupees(null)"), "₹0");
    assert.equal(p.run("rupees(undefined)"), "₹0");
});

test("rupees2 always shows two decimals, so figures line up in a column", () => {
    const p = createPage().load("core/util.js");
    assert.equal(p.run("rupees2(1000)"), "₹1,000.00");
    assert.equal(p.run("rupees2(2148215)"), "₹21,48,215.00");
    assert.equal(p.run("rupees2(1234.5)"), "₹1,234.50");
    assert.equal(p.run("rupees2(1234.567)"), "₹1,234.57");
    assert.equal(p.run("rupees2('250')"), "₹250.00");
    assert.equal(p.run("rupees2(null)"), "₹0.00");
    assert.equal(p.run("rupees2(-45)"), "₹-45.00");
});

test("isoDay gives yyyy-mm-dd in local time and formatDate turns it back into words", () => {
    const p = page();
    assert.equal(p.run("isoDay(new Date(2026, 0, 5))"), "2026-01-05");
    assert.equal(p.run("isoDay(new Date(2026, 11, 31))"), "2026-12-31");
    assert.match(p.run("formatDate('2026-10-12')"), /^12 Oct 2026$/);
});

test("el builds an element with properties and children", () => {
    const p = page();
    p.run(`var made = el("div", { className: "a b", title: "t" }, "text", el("span", { className: "inner" }))`);
    assert.equal(p.run("made.className"), "a b");
    assert.equal(p.run("made.title"), "t");
    assert.equal(p.run("made.children.length"), 2);
    assert.equal(p.run("made.children[0]"), "text");
    assert.equal(p.run("made.children[1].className"), "inner");
});

test("api sends JSON with the dashboard header, so a change is accepted by the server", async () => {
    const calls = [];
    const p = page({ fetch: async (path, options) => { calls.push({ path, options }); return { status: 200, ok: true, json: async () => ({ ok: 1 }) }; } });
    const answer = await p.raw(`api("/api/notes", { method: "POST", body: { body: "hi" } })`);
    assert.deepEqual(JSON.parse(JSON.stringify(answer)), { ok: 1 });
    assert.equal(calls[0].path, "/api/notes");
    assert.equal(calls[0].options.method, "POST");
    assert.equal(calls[0].options.headers["X-Requested-With"], "FinancialDashboard");
    assert.equal(calls[0].options.headers["Content-Type"], "application/json");
    assert.equal(calls[0].options.body, JSON.stringify({ body: "hi" }));
    assert.equal(calls[0].options.cache, "no-store");
});

test("api shows the server's own message when a call fails, and a plain one when there is none", async () => {
    const failing = (status, body) => page({ fetch: async () => ({ status, ok: false, json: async () => { if (body === undefined) throw new Error("no json"); return body; } }) });
    await assert.rejects(failing(400, { error: "Name is required" }).raw(`api("/api/x")`), { message: "Name is required" });
    await assert.rejects(failing(500).raw(`api("/api/x")`), { message: "Something went wrong (500)" });
});

test("api returns null for an answer with no content", async () => {
    const p = page({ fetch: async () => ({ status: 204, ok: true, json: async () => { throw new Error("must not be read"); } }) });
    assert.equal(await p.raw(`api("/api/x", { method: "DELETE" })`), null);
});

test("growsOnce is true only the first time for a key", () => {
    const p = page();
    assert.equal(p.run(`growsOnce("a")`), true);
    assert.equal(p.run(`growsOnce("a")`), false);
    assert.equal(p.run(`growsOnce("b")`), true);
});

test("a card's due day is empty, or a whole day from 1 to 31; anything else is refused", () => {
    const p = page();
    assert.deepEqual(["", "  ", "1", "21", "31"].map(v => p.run(`parseDueDay(${JSON.stringify(v)})`)), [null, null, 1, 21, 31]);
    for (const bad of ["0", "32", "-3", "1.5", "abc", "40"]) assert.equal(p.run(`parseDueDay(${JSON.stringify(bad)})`), undefined, bad);
});

test("ordinals read as in English, including the teens", () => {
    const p = page();
    assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 30, 31, 111].map(n => p.run(`ordinal(${n})`)),
        ["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "30th", "31st", "111th"]);
});


test("addMonthsClamped keeps the day where the month is long enough and uses the last day where it is not, forwards and back", () => {
    const p = page();
    const iso = (y, m0, d, n) => p.run(`isoDay(addMonthsClamped(new Date(${y}, ${m0}, ${d}), ${n}))`);
    assert.equal(iso(2026, 4, 31, -3), "2026-02-28");     // 31 May minus 3 months: not 3 March
    assert.equal(iso(2028, 4, 31, -3), "2028-02-29");     // a leap year
    assert.equal(iso(2026, 0, 31, 1), "2026-02-28");
    assert.equal(iso(2026, 10, 15, 3), "2027-02-15");
    assert.equal(iso(2026, 2, 31, -1), "2026-02-28");
    assert.equal(iso(2026, 9, 15, 0), "2026-10-15");
});

test("tween eases from the start to the end and stays inside them", () => {
    const p = page();
    assert.equal(p.run("tween(100, 500, 0)"), 100);
    assert.equal(p.run("tween(100, 500, 1)"), 500);
    assert.equal(p.run("tween(100, 500, 2)"), 500, "past the end it stays at the end");
    assert.equal(p.run("tween(100, 500, -1)"), 100);
    assert.ok(p.run("tween(0, 100, .5)") > 50, "eased out: more than half way at half the time");
});

test("countTo shows the end value at once without animation frames, and riseOnce marks a new row only the first time", () => {
    const p = page();
    p.run(`const n = el("span"); countTo(n, 0, 1234.5, rupees2); globalThis.shown = n.textContent;`);
    assert.equal(p.run("shown"), "₹1,234.50");
    p.run(`const m = el("span"); stopCount(m, "hidden"); globalThis.hid = m.textContent;`);
    assert.equal(p.run("hid"), "hidden");
    p.run(`globalThis.a = riseOnce(el("div"), "k1").classList.contains("rise"); globalThis.b = riseOnce(el("div"), "k1").classList.contains("rise");`);
    assert.equal(p.run("a"), true);
    assert.equal(p.run("b"), false);
});

test("setBarWidth slides a bar only when the same bar comes back with a different value", () => {
    const p = page();
    p.run(`globalThis.f1 = el("div"); setBarWidth(f1, "bar-a", 40);`);
    assert.equal(p.run("f1.style.width"), "40%");
    assert.equal(p.run("f1.classList.contains('shift')"), false, "the first time there is nothing to slide from");
    p.run(`globalThis.f2 = el("div"); setBarWidth(f2, "bar-a", 40);`);
    assert.equal(p.run("f2.classList.contains('shift')"), false, "same value");
    p.run(`globalThis.f3 = el("div"); setBarWidth(f3, "bar-a", 65);`);
    assert.equal(p.run("f3.style.width"), "65%");
    assert.equal(p.run("f3.classList.contains('shift')"), true);
});

test("a saved row is flashed once; collapseOut and replay behave with no browser motion", async () => {
    const p = page();
    p.run(`flashOnNext("txn-row-9")`);
    assert.equal(p.run(`takeFlash("txn-row-9")`), true);
    assert.equal(p.run(`takeFlash("txn-row-9")`), false, "taken once");
    assert.equal(p.run(`takeFlash("txn-row-10")`), false, "never asked for");
    p.run(`globalThis.n = el("div"); replay(n, "view-in")`);
    assert.equal(p.run(`n.classList.contains("view-in")`), true);
    await p.raw(`collapseOut('[data-txn-id="1"]')`);   // nothing matches: resolves at once
});

test("inCurrency shows an amount in its account's currency: rupees by default, a symbol and the right decimals otherwise", () => {
    const p = page();
    assert.equal(p.run("inCurrency(1234.5)"), "₹1,234.5");
    assert.equal(p.run("inCurrency(1234.5, 'INR')"), "₹1,234.5");
    assert.equal(p.run("inCurrency(1234.567, 'USD')"), "$1,234.57");
    assert.equal(p.run("inCurrency(10, 'EUR')"), "€10");
    assert.equal(p.run("inCurrency(10, 'GBP')"), "£10");
    assert.equal(p.run("inCurrency(5000000, 'VND')"), "₫5,000,000", "a dong amount has no decimals");
    assert.equal(p.run("inCurrency(1234.5, 'XYZ')"), "₹1,234.5", "an unknown code falls back to rupees");
});

test("balanceInr is the balance in rupees, and the balance itself for an account that has none", () => {
    const p = page();
    assert.equal(p.run("balanceInr({ balance: '100', balanceInr: '8550' })"), 8550);
    assert.equal(p.run("balanceInr({ balance: '100' })"), 100);
});
