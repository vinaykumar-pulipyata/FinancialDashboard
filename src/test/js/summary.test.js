const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The net worth figures: total, Assets, Receivables, Debt, hidden until the eye is pressed. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/summary.js");
    p.run(`accounts = [
        { active: true, type: "BANK", balance: 1000 },
        { active: true, type: "RECEIVABLE", balance: 200 },
        { active: true, type: "CREDIT_CARD", balance: 300 },
        { active: true, type: "DEBT", balance: 100 },
        { active: false, type: "BANK", balance: 999999 }]`);
    p.run(`initSummary()`);   // builds the figures into #nwFigures and wires the eye
    return p;
}
const texts = (p, cls) => p.select(".js-nw-" + cls) && p.node("nwFigures").find(n => n.classList.contains("js-nw-" + cls)).map(n => n.textContent);

test("the figures are calculated from the active accounts only: Assets - Debt is the Net worth, and Receivables is shown but not counted", () => {
    const p = page();
    p.run("renderSummary()");
    assert.deepEqual(p.run("nwFigures"), { total: 600, assets: 1000, receivables: 200, debt: 400 });
    assert.equal(p.run("nwFigures.assets - nwFigures.debt"), p.run("nwFigures.total"), "the lines add up");
    assert.notEqual(p.run("nwFigures.assets + nwFigures.receivables - nwFigures.debt"), p.run("nwFigures.total"), "money lent is not in it");
});

test("the figures read as a sum: Assets, - Debt, a rule, the bold total with no label, and Receivables apart below it", () => {
    const p = page();
    const box = p.node("nwFigures");
    const ops = box.find(n => n.className === "nw-op").map(n => n.textContent);
    const labelOf = n => n.textContent ?? n.children.filter(c => typeof c === "string").join("").trim();
    const labels = box.find(n => n.className === "nw-label").map(labelOf);
    assert.deepEqual(ops, ["", "−", ""], "no + and no = sign");
    assert.deepEqual(labels, ["Assets", "Debt", "Receivables (not counted)"]);
    assert.equal(box.find(n => n.className === "nw-rule").length, 1);
    const keys = ["js-nw-assets", "js-nw-debt", "js-nw-total", "js-nw-receivables"];
    const amounts = box.find(n => n.classList.contains("nw-amt"));
    assert.deepEqual(amounts.map(n => keys.find(c => n.classList.contains(c))), keys, "assets, debt, total, then receivables last");
    assert.equal(amounts[2].classList.contains("net-worth"), true, "the total keeps the big accent style");
    assert.equal(amounts[2].attrs["aria-label"], "Net worth", "still named for a screen reader");
});

test("everything starts hidden, and the eye shows and hides all four together", () => {
    const p = page();
    p.run("renderSummary()");
    for (const key of ["total", "assets", "receivables", "debt"]) assert.deepEqual(texts(p, key), ["₹ ••••••••"], key);
    p.node("nwEye").fire("click");
    assert.deepEqual(["total", "assets", "receivables", "debt"].map(k => texts(p, k)[0]), ["₹600.00", "₹1,000.00", "₹200.00", "₹400.00"]);
    assert.equal(p.node("nwEye").textContent, "🙈");
    assert.equal(p.node("nwEye").attrs["aria-pressed"], "true");
    p.node("nwEye").fire("click");
    assert.deepEqual(texts(p, "total"), ["₹ ••••••••"]);
    assert.equal(p.node("nwEye").attrs["aria-pressed"], "false");
});

test("new data keeps the chosen visibility", () => {
    const p = page();
    p.node("nwEye").fire("click");
    p.run(`accounts = [{ active: true, type: "BANK", balance: 5000 }]; renderSummary()`);
    assert.deepEqual(texts(p, "total"), ["₹5,000.00"]);
});

test("each figure carries its explanation", () => {
    const p = page();
    const tips = p.node("nwFigures").find(n => n.classList.contains("info-tooltip")).map(n => n.textContent);
    assert.equal(tips.length, 3);
    assert.ok(tips.some(t => /Everything you own and hold/.test(t)) && tips.some(t => /lent to someone/.test(t)) && tips.some(t => /Everything you owe/.test(t)));
});

/* ---- what Assets is made of ---- */
function richPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/summary.js");
    p.run(`accounts = [
        { active: true, type: "BANK", balance: 40000 }, { active: true, type: "BANK", balance: 10000 },
        { active: true, type: "CASH", balance: 500 },
        { active: true, type: "GOAL_FUND", balance: 20000 },
        { active: true, type: "INVESTMENT", balance: 300000 },
        { active: true, type: "GOLD", balance: 60000 },
        { active: true, type: "PROPERTY", balance: 4000000 },
        { active: true, type: "VEHICLE", balance: 650000 },
        { active: true, type: "RECEIVABLE", balance: 25000 },
        { active: true, type: "DEBT", balance: 200000 },
        { active: false, type: "OTHER", balance: 999 }]`);
    p.run("initSummary(); renderSummary()");
    return p;
}
const parts = p => p.node("nwFigures").find(n => n.classList.contains("nw-part-label") || n.classList.contains("nw-part-amt")).map(n => n.textContent);

test("under Assets: one line per kind of account, in a fixed order, adding up to Assets and leaving Receivables to its own line", () => {
    const p = richPage();
    assert.deepEqual(p.run("nwParts"), [
        { label: "Cash", amount: 500 }, { label: "Bank accounts", amount: 50000 }, { label: "Goal funds", amount: 20000 },
        { label: "Investments", amount: 300000 }, { label: "Gold", amount: 60000 }, { label: "Property", amount: 4000000 },
        { label: "Vehicles", amount: 650000 }]);
    assert.equal(p.run("nwParts.reduce((sum, part) => sum + part.amount, 0)"), p.run("nwFigures.assets"));
    assert.equal(p.run("nwFigures.assets"), 5080500);
});

test("the breakdown is hidden with the other amounts and shown with the eye", () => {
    const p = richPage();
    assert.deepEqual(parts(p).filter((_, i) => i % 2 === 1), Array(7).fill("₹ ••••••••"));
    p.node("nwEye").fire("click");
    assert.deepEqual(parts(p).slice(0, 4), ["Cash", "₹500.00", "Bank accounts", "₹50,000.00"]);
});

test("the breakdown starts hidden, and the arrow beside Assets opens and closes it", () => {
    const p = richPage();
    const box = p.node("nwFigures").find(n => n.classList.contains("js-nw-parts"))[0];
    const arrow = p.node("nwFigures").find(n => n.classList.contains("js-nw-toggle"))[0];
    assert.equal(box.classList.contains("hidden"), true, "hidden by default");
    assert.equal(arrow.textContent, "▸");
    assert.equal(arrow.attrs["aria-expanded"], "false");
    arrow.fire("click");
    assert.equal(box.classList.contains("hidden"), false);
    assert.equal(arrow.textContent, "▾");
    arrow.fire("click");
    assert.equal(box.classList.contains("hidden"), true);
});

test("with no assets there is no breakdown to show", () => {
    const p = richPage();
    p.run(`accounts = [{ active: true, type: "DEBT", balance: 10 }]; renderSummary()`);
    assert.deepEqual(parts(p), []);
    assert.equal(p.node("nwFigures").find(n => n.classList.contains("js-nw-parts"))[0].classList.contains("hidden"), true);
});

test("an account in a foreign currency counts in rupees (balanceInr), not in its own currency", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/summary.js");
    p.run(`accounts = [
        { active: true, type: "BANK", balance: 1000, balanceInr: 1000, currency: "INR" },
        { active: true, type: "BANK", balance: 100, balanceInr: 8550, currency: "USD" },
        { active: true, type: "CASH", balance: 5000000, balanceInr: 16500, currency: "VND" }]`);
    p.run("renderSummary()");
    assert.deepEqual(p.run("nwFigures"), { total: 26050, assets: 26050, receivables: 0, debt: 0 });
});
