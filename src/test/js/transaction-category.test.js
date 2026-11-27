const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Income / Expense form lists categories but does not create them: an (i) says to use Menu > Edit categories. */
function openedForm() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        var logged = []; function clientLog(level, event, detail) { logged.push(event + " " + detail); }  function describeError() { return ""; }
        accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }]; loans = { borrowers: [] };
        function api(path) { return Promise.resolve([
            { id: 11, name: "Food", kind: "EXPENSE", systemKey: null, active: true, parentId: null },
            { id: 13, name: "Snacks", kind: "EXPENSE", systemKey: null, active: true, parentId: 11 }]); }`);
    return p;
}

test("the form has no Add category or Add sub category; only the person (for loans) can still be added on the spot", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const labels = p.node("txnBody").find(n => n.tag === "button").map(n => n.textContent);
    assert.ok(!labels.includes("+ Add category") && !labels.includes("+ Add sub category"), labels.join(" | "));
    assert.ok(labels.includes("+ Add person"), "the loan categories still take a new person");
});

test("an (i) beside Category tells where new categories are made, and opens that text when clicked", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const info = p.node("txnBody").find(n => n.tag === "button" && n.textContent === "ⓘ")[0];
    assert.ok(info, "an (i) button");
    assert.match(info.title, /menu.*Edit \/ Add categories/);
    const hint = p.node("txnBody").find(n => n.className.includes("edit-hint"))[0];
    assert.equal(hint.classList.contains("hidden"), true);
    info.fire("click");
    assert.equal(hint.classList.contains("hidden"), false);
    assert.match(hint.textContent, /Edit \/ Add categories/);
    info.fire("click");
    assert.equal(hint.classList.contains("hidden"), true);
});

test("the menu really has an Edit / Add categories button, as the (i) says", () => {
    const fs = require("node:fs"), path = require("node:path");
    const html = fs.readFileSync(path.join(require("./harness").STATIC, "Dashboard.html"), "utf8");
    assert.match(html, /id="menuPanel"[\s\S]*id="editCategoriesButton"[^>]*>(?:<span[^>]*>[^<]*<\/span>)*<span class="side-label">Edit \/ Add categories</);
});

test("categories and sub categories are still listed, with a sub category under its category", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    assert.deepEqual(p.run("txn.fields.category.children.map(o => o.textContent)"), ["Select category", "Food"]);
    p.run(`txn.fields.category.value = "11"; txn.fields.category.fire("change")`);
    assert.deepEqual(p.run("txn.fields.subCategory.children.map(o => o.textContent)"), ["Select sub category (optional)", "Snacks"]);
});

test("the Transfer form has an (i) saying a sale is recorded as a transfer, and there is no Sell button", async () => {
    const p = openedForm();
    p.run(`accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }, { id: 2, name: "Index Fund", type: "INVESTMENT", active: true }]`);
    await p.raw(`openTransaction("TRANSFER")`);
    const buttons = p.node("txnBody").find(n => n.tag === "button");
    assert.ok(!buttons.some(b => /sell/i.test(b.textContent)), "no Sell button");
    const info = buttons.find(b => b.textContent === "ⓘ");
    assert.ok(info, "an (i) button");
    const hint = p.node("txnBody").find(n => n.className.includes("edit-hint"))[0];
    assert.match(hint.textContent, /Selling an investment or gold\? Record it as a transfer/);
    assert.match(hint.textContent, /Withdraw button/);
    assert.equal(hint.classList.contains("hidden"), true);
    info.fire("click");
    assert.equal(hint.classList.contains("hidden"), false);
});

test("Income and Expense forms do not carry the sale tip", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    assert.ok(!p.node("txnBody").find(n => n.className.includes("edit-hint")).some(h => /Selling an investment/.test(h.textContent)));
});

/* ---- the Income | Expense | Transfer buttons of a new transaction, and the red + that opens an Expense ---- */
async function formWithKinds() {
    const p = openedForm();
    p.run(`accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }, { id: 2, name: "Cash", type: "CASH", active: true }]`);
    const box = p.node("txnKinds");
    for (const kind of ["INCOME", "EXPENSE", "TRANSFER"]) {
        const b = p.raw(`document.createElement("button")`);
        b.dataset.kind = kind;
        b.textContent = kind;
        box.append(b);
    }
    p.run("initTransactionForm()");
    return p;
}
const active = p => p.node("txnKinds").children.filter(b => b.classList.contains("active")).map(b => b.dataset.kind);

test("a new transaction shows Income, Expense and Transfer with the one opened filled, and no Delete", async () => {
    const p = await formWithKinds();
    await p.raw(`openTransaction("EXPENSE")`);
    assert.equal(p.node("txnKinds").classList.contains("hidden"), false);
    assert.deepEqual(active(p), ["EXPENSE"]);
    assert.deepEqual(p.node("txnKinds").children.map(b => b.dataset.kind), ["INCOME", "EXPENSE", "TRANSFER"], "Income left, Transfer right");
    assert.equal(p.node("txnTitle").textContent, "New transaction");
});

test("choosing another kind switches the form and keeps the date and amount typed so far", async () => {
    const p = await formWithKinds();
    await p.raw(`openTransaction("EXPENSE", null, "2026-10-02")`);
    p.run(`txn.fields.amount.value = "450"`);
    p.node("txnKinds").children.find(b => b.dataset.kind === "INCOME").fire("click");
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(p.run("txn.kind"), "INCOME");
    assert.deepEqual(active(p), ["INCOME"]);
    assert.equal(p.run("txn.fields.amount.value"), "450");
    assert.equal(p.run("txn.fields.date.value"), "2026-10-02");
    p.node("txnKinds").children.find(b => b.dataset.kind === "TRANSFER").fire("click");
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(p.run("txn.kind"), "TRANSFER");
    assert.equal(p.run("txn.fields.amount.value"), "450");
    assert.ok(p.run("!!txn.fields.from && !!txn.fields.to"), "the transfer form has From and To");
});

test("editing an existing transaction has no kind buttons", async () => {
    const p = await formWithKinds();
    await p.raw(`openTransaction("EXPENSE", { id: 5, kind: "EXPENSE", date: "2026-10-01", amount: 10, fromAccountId: 1, toAccountId: null, categoryId: null })`);
    assert.equal(p.node("txnKinds").classList.contains("hidden"), true);
    assert.match(p.node("txnTitle").textContent, /^Edit /);
    p.node("txnKinds").children.find(b => b.dataset.kind === "INCOME").fire("click");   // even if clicked, an edit keeps its kind
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(p.run("txn.kind"), "EXPENSE");
});

test("the Pay window has no kind buttons", async () => {
    const p = await formWithKinds();
    p.load("panels/liability-payment.js");
    p.run(`debtEmis = new Map(); allCategories = []`);
    await p.raw(`openLiabilityPayment({ id: 3, name: "Car loan", type: "DEBT", active: true, balance: 100 })`);
    assert.equal(p.node("txnKinds").classList.contains("hidden"), true);
});

test("the red + opens a new Expense on the chosen calendar day, or today's form otherwise", async () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/calendar.js");
    p.run(`var opened = [], wsView = "calendar"; function openTransaction(kind, existing, date) { opened.push([kind, existing, date]); return Promise.resolve(); }
        calSelected = "2026-10-09"; initCalendar()`);
    p.node("calAdd").fire("click");
    p.run(`wsView = "transactions"`);
    p.node("calAdd").fire("click");
    assert.deepEqual(p.run("opened"), [["EXPENSE", null, "2026-10-09"], ["EXPENSE", null, null]]);
});
