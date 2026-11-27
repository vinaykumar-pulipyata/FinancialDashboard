const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Tags on transactions: the pills in the list, finding by tag, the CSV, and the tag box of the form. */
function listPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/period-filter.js", "panels/transaction-tags.js", "panels/transactions.js");
    p.run(`var shown = []; function showWorkspaceView(v) { shown.push(v); }
        function calendarFollowPeriod() {} function renderPeriod() { renderTransactions(); }
        function openTransaction() {} function isLedgerFunding() { return false; } function ledgerFundingDelete() { return el("span"); } function isInvestment() { return false; } function investmentDelete() { return el("span"); } function isRdDeposit() { return false; } function rdDepositDelete() { return el("span"); }
        accounts = [{ id: 1, name: "Savings Bank" }]; allCategories = [{ id: 10, name: "Food", parentId: null }];
        loans = { receivablesAccountId: null, unnamed: 0, borrowers: [] };
        transactions = [
            { id: 1, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 2400, categoryId: 10, note: "Dinner", tags: ["barbeque-nation", "dinner"] },
            { id: 2, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 450, categoryId: 10, note: "Tea", tags: ["tea"] },
            { id: 3, date: "2026-10-04", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 90, categoryId: 10, note: "Old one" }];`);
    return p;
}
const listed = p => p.run("visibleTransactions().map(t => t.id)");

test("normalizeTxnTag gives the form the server stores", () => {
    const p = createPage().load("panels/transaction-tags.js");
    assert.equal(p.run(`normalizeTxnTag("  #Barbeque Nation ")`), "barbeque-nation");
    assert.equal(p.run(`normalizeTxnTag("##Trip")`), "trip");
});

test("a row shows its tags as pills, a row without tags shows none", () => {
    const p = listPage();
    const row = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`);
    assert.deepEqual(row(1).find(n => n.className.includes("tag-pill")).map(n => n.textContent), ["#barbeque-nation", "#dinner"]);
    assert.equal(row(3).find(n => n.className.includes("tag-pill")).length, 0);
});

test("the search finds a transaction by its tag, and a pill opens the list on that tag with the other filters cleared", () => {
    const p = listPage();
    p.run(`txnQuery.text = "barbeque"`);
    assert.deepEqual(listed(p), [1]);
    p.run(`txnFilter = "INCOME"; txnQuery.account = "1"; showTransactionsTagged("tea")`);
    assert.deepEqual(listed(p), [2]);
    assert.equal(p.run("txnQuery.tag"), "tea", "the tag is a filter of its own, the search box stays empty");
    assert.equal(p.run("txnQuery.text"), "");
    assert.equal(p.run("txnFilter"), "ALL");
    assert.deepEqual(p.run("shown"), ["transactions"]);
    const pill = p.raw(`txnRowElement(transactions[0])`).find(n => n.className.includes("tag-pill"))[1];   // #dinner
    pill.fire("click");
    assert.deepEqual(listed(p), [1], "clicking a pill shows the transactions with that tag");
});

test("the CSV has a Tags column", () => {
    const p = listPage();
    const csv = p.run(`transactionsCsv(transactions)`);
    const lines = csv.slice(1).split("\r\n");
    assert.ok(lines[0].includes('"Description","Tags","Currency"'));
    assert.ok(lines[1].includes('"Dinner","","barbeque-nation dinner","INR"'));
    assert.ok(lines[3].includes('"Old one","","","INR"'), "no tags: an empty cell");
});

function fieldPage() {
    return createPage().load("core/util.js", "panels/transaction-tags.js");
}
const pills = field => field.node.find(n => n.className === "tag-pill").map(n => n.textContent.replace("×", ""));

test("the tag box shows its tags, adds typed ones (several with commas), refuses bad ones and removes with the ×", () => {
    const p = fieldPage();
    p.run(`var box = txnTagField(["tea"]);`);
    const input = p.raw(`box.node.find(n => n.tag === "input")[0]`);
    input.value = "Team Dinner, #work,tea";
    p.run(`box.tags()`);
    assert.deepEqual(p.run("box.tags()"), ["tea", "team-dinner", "work"], "cleaned, no duplicate");
    input.value = "no good!";
    p.run(`box.tags()`);
    assert.match(p.raw(`box.node.allText`), /letters, numbers/);
    assert.deepEqual(p.run("box.tags()"), ["tea", "team-dinner", "work"], "the bad one was not added");
    p.raw(`box.node.find(n => n.className === "tag-x")[0]`).fire("click");
    assert.deepEqual(p.run("box.tags()"), ["team-dinner", "work"]);
});

test("a tag is at most 30 characters and a transaction has at most 10", () => {
    const p = fieldPage();
    p.run(`var box = txnTagField([]);`);
    const input = p.raw(`box.node.find(n => n.tag === "input")[0]`);
    input.value = "x".repeat(31);
    p.run("box.tags()");
    assert.match(p.raw("box.node.allText"), /at most 30 characters/);
    input.value = "a,b,c,d,e,f,g,h,i,j,k";
    p.run("box.tags()");
    assert.equal(p.run("box.tags().length"), 10);
    assert.match(p.raw("box.node.allText"), /at most 10 tags/);
});

test("suggestions fill an untouched box, stop once a person has chosen, and the Suggest button replaces them and counts as chosen", () => {
    const p = fieldPage();
    p.run(`var asked = 0; var box = txnTagField([], { onSuggest: () => { asked++; } });`);
    p.run(`box.setSuggested(["cab", "transport"])`);
    assert.deepEqual(p.run("box.tags()"), ["cab", "transport"]);
    assert.equal(p.run("box.isEdited()"), false, "a suggestion is not a choice");
    p.raw(`box.node.find(n => n.className === "tag-x")[0]`).fire("click");     // removes cab
    assert.equal(p.run("box.isEdited()"), true);
    p.run(`box.setSuggested(["something-else"])`);
    assert.deepEqual(p.run("box.tags()"), ["transport"], "once chosen, suggestions leave the box alone");
    p.raw(`box.node.find(n => n.tag === "button" && n.textContent.includes("Suggest"))[0]`).fire("click");
    assert.equal(p.run("asked"), 1);
    p.run(`box.replaceWith(["fresh"])`);
    assert.deepEqual(p.run("box.tags()"), ["fresh"]);
});

test("a box for a saved transaction starts as chosen, so typing a note does not replace its tags", () => {
    const p = fieldPage();
    p.run(`var box = txnTagField(["saved"], { edited: true }); box.setSuggested(["new"])`);
    assert.deepEqual(p.run("box.tags()"), ["saved"]);
});

/* The form: suggestions arrive as the note is typed, and what is sent on save */
function formPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        function clientLog() {} function describeError() { return ""; } function refreshData() { return Promise.resolve(); }
        var calls = []; var suggestion = ["team", "dinner"];
        accounts = [{ id: 1, name: "HDFC", type: "BANK", active: true }]; loans = { borrowers: [] };
        function api(path, options) {
            calls.push({ path, method: options && options.method, body: options && options.body });
            if (path.startsWith("/api/categories")) return Promise.resolve([{ id: 11, name: "Food", kind: "EXPENSE", systemKey: null, active: true, parentId: null }]);
            if (path === "/api/transactions/suggest-tags") return Promise.resolve(suggestion);
            return Promise.resolve({ id: 99 });
        }`);
    return p;
}
const later = ms => new Promise(resolve => setTimeout(resolve, ms));
const saveBody = p => p.run(`calls.filter(c => c.path === "/api/transactions").map(c => c.body)`)[0];

test("typing a note asks the server for tags and fills the box; the save then lets the server decide", async () => {
    const p = formPage();
    await p.raw(`openTransaction("EXPENSE")`);
    p.raw(`txn.fields.note`).value = "Team dinner";
    p.raw(`txn.fields.note`).fire("input");
    p.raw(`txn.fields.note`).fire("input");
    await later(450);
    const asked = p.run(`calls.filter(c => c.path === "/api/transactions/suggest-tags")`);
    assert.equal(asked.length, 1, "keystrokes in a burst make one question");
    assert.deepEqual(asked[0].body, { categoryId: null, note: "Team dinner", description: "" });
    assert.deepEqual(p.run("txn.fields.tags.tags()"), ["team", "dinner"]);

    p.raw(`txn.fields.amount`).value = "250";
    p.raw(`txn.fields.account`).value = "1";
    await p.raw(`saveTransaction()`);
    assert.equal(saveBody(p).tags, null, "not tagged by hand: no list, so the server works them out");
});

test("tags changed by hand are sent as the list, and the suggestion no longer replaces them", async () => {
    const p = formPage();
    await p.raw(`openTransaction("EXPENSE")`);
    const input = p.raw(`txn.fields.tags.node.find(n => n.tag === "input")[0]`);
    input.value = "family";
    p.run(`txn.fields.tags.tags()`);
    p.raw(`txn.fields.note`).value = "Dinner";
    p.raw(`txn.fields.note`).fire("input");
    await later(450);
    assert.equal(p.run(`calls.filter(c => c.path === "/api/transactions/suggest-tags").length`), 0, "no question once a person has chosen");
    p.raw(`txn.fields.amount`).value = "250";
    p.raw(`txn.fields.account`).value = "1";
    await p.raw(`saveTransaction()`);
    assert.deepEqual(saveBody(p).tags, ["family"]);
});

test("a saved transaction keeps its tags when edited, and the Suggest button asks again", async () => {
    const p = formPage();
    p.run(`var old = { id: 5, date: "2026-10-01", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 100, categoryId: 11, note: "Cab", description: null, tags: ["commute"] }`);
    await p.raw(`openTransaction("EXPENSE", old)`);
    assert.deepEqual(p.run("txn.fields.tags.tags()"), ["commute"]);
    p.raw(`txn.fields.note`).value = "Cab to the airport";
    p.raw(`txn.fields.note`).fire("input");
    await later(450);
    assert.deepEqual(p.run("txn.fields.tags.tags()"), ["commute"], "typing does not replace saved tags");
    p.raw(`txn.fields.tags.node.find(n => n.tag === "button" && n.textContent.includes("Suggest"))[0]`).fire("click");
    await later(50);
    assert.deepEqual(p.run("txn.fields.tags.tags()"), ["team", "dinner"], "Suggest replaces them with the server's answer");
    await p.raw(`saveTransaction()`);
    const put = p.run(`calls.filter(c => c.path === "/api/transactions/5")[0].body`);
    assert.deepEqual(put.tags, ["team", "dinner"]);
});

test("a row of tags above the list shows each tag with its count, most used first; choosing one narrows the list, choosing it again clears it", () => {
    const p = listPage();
    p.run("renderTransactions()");
    const bar = () => p.node("txnTags");
    const pills = () => bar().find(n => n.className.includes("tag-pill"));
    assert.deepEqual(pills().map(n => n.allText.trim()), ["#barbeque-nation (1)", "#dinner (1)", "#tea (1)"], "equal counts: alphabetical");
    assert.ok(!bar().classList.contains("hidden"));

    pills().find(n => n.allText.includes("#tea")).fire("click");
    assert.deepEqual(listed(p), [2]);
    const active = pills().filter(n => n.className.includes("active"));
    assert.deepEqual(active.map(n => n.allText.trim().slice(0, 4)), ["#tea"]);
    assert.deepEqual(pills().map(n => n.allText.trim()), ["#barbeque-nation (1)", "#dinner (1)", "#tea (1)"], "the other tags stay, as in Notes, so another can be chosen; the counts ignore the chosen tag itself");

    active[0].fire("click");
    assert.deepEqual(listed(p), [1, 2, 3], "clicking the chosen tag again shows everything");
    assert.equal(p.run("txnQuery.tag"), "");
});

test("the counts in the tag row follow the kind chip and the search, and a list with no tags hides the row", () => {
    const p = listPage();
    p.run(`transactions.push({ id: 4, date: "2026-10-05", kind: "INCOME", fromAccountId: null, toAccountId: 1, amount: 9000, categoryId: 10, note: "Pay", tags: ["salary", "tea"] }); txnFilter = "INCOME"; renderTransactions()`);
    assert.deepEqual(p.node("txnTags").find(n => n.className.includes("tag-pill")).map(n => n.allText.trim()), ["#salary (1)", "#tea (1)"]);
    p.run(`txnFilter = "ALL"; transactions.forEach(t => { t.tags = []; }); renderTransactions()`);
    assert.ok(p.node("txnTags").classList.contains("hidden"));
});

test("only the most used tags are listed, with a + N more that shows them all", () => {
    const p = listPage();
    p.run(`transactions = []; for (let i = 0; i < 25; i++) transactions.push({ id: 100 + i, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 10, categoryId: 10, note: "x", tags: ["tag" + String(i).padStart(2, "0")] }); renderTransactions()`);
    assert.equal(p.node("txnTags").find(n => n.className.includes("tag-pill")).length, 20);
    const more = p.node("txnTags").find(n => n.className === "tag-more")[0];
    assert.equal(more.textContent, "+ 5 more");
    more.fire("click");
    assert.equal(p.node("txnTags").find(n => n.className.includes("tag-pill")).length, 25);
});
