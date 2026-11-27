const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Expense form can share the bill with people from the Receivables list; the reports then count only the user's own share. */
function openedForm() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        var logged = []; var saved = []; function clientLog(level, event, detail) { logged.push(event + " " + detail); }  function describeError() { return ""; }
        function refreshData() { return Promise.resolve(); } function closeTransaction() {}
        accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }];
        loans = { borrowers: [{ id: 7, name: "Teja", owed: 0 }, { id: 8, name: "Ravi", owed: 0 }] };
        function api(path, options) {
            if (options && options.method) { saved.push({ path, options }); return Promise.resolve({ id: 5 }); }
            return Promise.resolve([{ id: 11, name: "Food", kind: "EXPENSE", systemKey: null, active: true, parentId: null },
                { id: 12, name: "Loans Given", kind: "EXPENSE", systemKey: "LOANS_GIVEN", active: true, parentId: null }]);
        }
        initTransactionForm();`);
    return p;
}

test("an Expense form has a Split box, off by default, and an Income or Transfer form does not", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const body = p.node("txnBody");
    assert.ok(body.allText.includes("Split this expense with people"));
    assert.equal(body.find(n => n.className.includes("split-panel"))[0].classList.contains("hidden"), true);
    await p.raw(`openTransaction("INCOME")`);
    assert.ok(!p.node("txnBody").allText.includes("Split this expense"));
});

test("ticking Split lists the people, and an equal split shows each share and your own", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const body = p.node("txnBody");
    p.run(`txn.fields.amount.value = "1000"`);
    const toggle = body.find(n => n.tag === "input" && n.type === "checkbox")[0];
    toggle.checked = true;
    toggle.fire("change");
    assert.match(body.allText, /Teja/);
    assert.match(body.allText, /Ravi/);
    const tick = person => body.find(n => n.tag === "input" && n.type === "checkbox")[person];   // 0 is the Split box; 1 Teja; 2 Ravi
    tick(1).checked = true; tick(1).fire("change");
    tick(2).checked = true; tick(2).fire("change");
    const r = p.run(`txn.fields.split.result()`);
    assert.equal(r.error, null);
    assert.deepEqual(r.shares, { 7: 333.33, 8: 333.33 });
    assert.match(p.node("txnBody").find(n => n.className.includes("split-mine"))[0].textContent, /333\.34/);
});

test("saving a split expense sends the shares of the others and keeps the full amount; an unsplit one sends none", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const body = p.node("txnBody");
    p.run(`txn.fields.amount.value = "590"; txn.fields.account.value = "1"; txn.fields.category.value = "11"`);
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    assert.equal(p.run(`saved[0].options.body.splits`), null);
    p.run(`saved.length = 0`);
    const toggle = body.find(n => n.tag === "input" && n.type === "checkbox")[0];
    toggle.checked = true; toggle.fire("change");
    const teja = body.find(n => n.tag === "input" && n.type === "checkbox")[1];
    teja.checked = true; teja.fire("change");
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    const sent = p.run(`saved[0].options.body`);
    assert.equal(sent.amount, 590);
    assert.deepEqual(sent.splits, { 7: 295 });
});

test("an equal split that cannot work stops the save with a sentence", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const body = p.node("txnBody");
    p.run(`txn.fields.amount.value = "100"; txn.fields.account.value = "1"; txn.fields.category.value = "11"`);
    const toggle = body.find(n => n.tag === "input" && n.type === "checkbox")[0];
    toggle.checked = true; toggle.fire("change");
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    assert.match(p.node("txnError").textContent, /at least one person/);
    assert.equal(p.run(`saved.length`), 0);
});

test("Loans Given hides the Split box and sends no shares", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    p.run(`txn.fields.category.value = "12"`);
    p.run(`txn.fields.category.fire("change")`);
    assert.equal(p.run(`txn.fields.split.node.classList.contains("hidden")`), true);
});

test("an expense being edited opens with its split shown by amounts", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE", { id: 3, date: "2026-11-01", kind: "EXPENSE", amount: 590, fromAccountId: 1, categoryId: 11, note: "Dinner", splits: { 7: 147.5 }, tags: [] })`);
    const r = p.run(`txn.fields.split.result()`);
    assert.deepEqual(r.shares, { 7: 147.5 });
});

test("Stats, Budgets and the like count an expense split with people at the user's own share", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js");
    p.run(`accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }];
        transactions = [{ id: 1, date: "2026-11-01", kind: "EXPENSE", fromAccountId: 1, amount: 590, splits: { 7: 147.5, 8: 147.5 } },
                        { id: 2, date: "2026-11-02", kind: "EXPENSE", fromAccountId: 1, amount: 100 }]`);
    assert.deepEqual(p.run(`rupeeTransactions().map(t => t.amount)`), [295, 100]);
    assert.equal(p.run(`transactions[0].amount`), 590, "the transaction itself keeps its full amount");
});

test("choosing a person in Paid by hides the account and the split box, asks for your share, and sends no account", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    p.run(`txn.fields.amount.value = "1200"; txn.fields.category.value = "11"`);
    const box = p.node("txnBody").find(n => n.tag === "input" && n.type === "checkbox")[0];
    box.checked = true; box.fire("change");               // Paid by is offered only with the Split box ticked
    p.run(`txn.fields.paidBy.value = "7"`);
    p.run(`txn.fields.paidBy.fire("change")`);
    assert.equal(p.run(`txn.fields.accountRow.classList.contains("hidden")`), true);
    assert.equal(p.run(`txn.fields.split.node.classList.contains("hidden")`), false, "the Split box itself stays, so it can be unticked");
    assert.equal(p.node("txnBody").find(n => n.className.includes("split-panel"))[0].classList.contains("hidden"), true, "the people are not needed when somebody else paid");
    assert.equal(p.run(`txn.fields.myShareRow.classList.contains("hidden")`), false);
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    assert.match(p.node("txnError").textContent, /your share/i);
    p.run(`txn.fields.myShare.value = "1300"`);
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    assert.match(p.node("txnError").textContent, /more than the bill/);
    p.run(`txn.fields.myShare.value = "400"`);
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    const sent = p.run(`saved[0].options.body`);
    assert.equal(sent.paidByBorrowerId, 7);
    assert.equal(sent.ownShare, 400);
    assert.equal(sent.amount, 1200);
    assert.equal(sent.fromAccountId, null);
    assert.equal(sent.splits, null);
});

test("Me (from my account) is the default and shows the account again after a person was chosen", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    assert.ok(!p.run(`txn.fields.paidBy.value`), "nobody chosen: it is paid from your account");
    assert.equal(p.run(`txn.fields.myShareRow.classList.contains("hidden")`), true);
    p.run(`txn.fields.paidBy.value = "7"`); p.run(`txn.fields.paidBy.fire("change")`);
    p.run(`txn.fields.paidBy.value = ""`); p.run(`txn.fields.paidBy.fire("change")`);
    assert.equal(p.run(`txn.fields.accountRow.classList.contains("hidden")`), false);
});

test("an expense somebody else paid counts at your share in Stats, and a split one still at the rest", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js");
    p.run(`accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }];
        transactions = [{ id: 1, date: "2026-11-01", kind: "EXPENSE", fromAccountId: null, amount: 1200, paidByBorrowerId: 7, ownShare: 400 },
                        { id: 2, date: "2026-11-02", kind: "EXPENSE", fromAccountId: 1, amount: 590, splits: { 8: 295 } }]`);
    assert.deepEqual(p.run(`rupeeTransactions().map(t => t.amount)`), [400, 295]);
});

test("Paid by is hidden until the Split box is ticked, and unticking it makes the expense all yours again", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    assert.equal(p.run(`txn.fields.paidByRow.classList.contains("hidden")`), true, "by default every expense is mine");
    const box = p.node("txnBody").find(n => n.tag === "input" && n.type === "checkbox")[0];
    box.checked = true; box.fire("change");
    assert.equal(p.run(`txn.fields.paidByRow.classList.contains("hidden")`), false);
    p.run(`txn.fields.paidBy.value = "7"`); p.run(`txn.fields.paidBy.fire("change")`);
    assert.equal(p.run(`txn.fields.accountRow.classList.contains("hidden")`), true);
    box.checked = false; box.fire("change");
    assert.equal(p.run(`txn.fields.paidByRow.classList.contains("hidden")`), true);
    assert.equal(p.run(`txn.fields.accountRow.classList.contains("hidden")`), false, "my account is back");
    assert.ok(!p.run(`txn.fields.paidBy.value`), "nobody else paid any more");
    p.run(`txn.fields.amount.value = "500"; txn.fields.category.value = "11"; txn.fields.account.value = "1"`);
    p.node("txnSave").fire("click");
    await new Promise(r => setTimeout(r, 0));
    const sent = p.run(`saved[0].options.body`);
    assert.equal(sent.paidByBorrowerId, null);
    assert.equal(sent.fromAccountId, 1);
    assert.equal(sent.splits, null);
});

test("an expense somebody else paid opens with the Split box ticked and Paid by showing that person", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE", { id: 3, date: "2026-11-01", kind: "EXPENSE", amount: 1200, fromAccountId: null, categoryId: 11, note: "Hotel", paidByBorrowerId: 7, ownShare: 400, tags: [] })`);
    assert.equal(p.run(`txn.fields.split.isOn()`), true);
    assert.equal(p.run(`txn.fields.paidByRow.classList.contains("hidden")`), false);
    assert.equal(String(p.run(`txn.fields.paidBy.value`)), "7");
    assert.equal(p.run(`txn.fields.accountRow.classList.contains("hidden")`), true);
});

test("inside the Split box the order is Paid by, then how it is shared, then the people", async () => {
    const p = openedForm();
    await p.raw(`openTransaction("EXPENSE")`);
    const box = p.node("txnBody").find(n => n.tag === "input" && n.type === "checkbox")[0];
    box.checked = true; box.fire("change");
    const kids = p.raw(`txn.fields.split.node`).children;
    const at = node => kids.indexOf(node);
    assert.ok(at(p.raw(`txn.fields.split.top`)) > 0, "the holder comes right after the tick box");
    assert.ok(at(p.raw(`txn.fields.split.top`)) < kids.findIndex(n => n && n.className && n.className.includes("split-panel")), "and before the sharing panel");
    assert.ok(p.raw(`txn.fields.split.top`).children.includes(p.raw(`txn.fields.paidByRow`)), "Paid by is the first row in it");
    const panelKids = kids.find(n => n && n.className && n.className.includes("split-panel")).children;
    assert.match(panelKids[0].allText, /Share/, "how it is shared comes next");
    assert.match(panelKids[1].allText, /You/, "then the people");
    assert.ok(p.node("txnBody").children.indexOf(p.raw(`txn.fields.paidByRow`)) === -1, "Paid by is not a separate row of the form any more");
});
