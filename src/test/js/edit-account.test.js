const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Edit account window: Deactivate or Activate, Reset balance (asks what happened to the money), and a deactivated account that
   allows nothing until it is activated again. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js",
        "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js", "panels/ledger.js", "panels/edit-account.js", "panels/person.js");
    p.run(`function renderAccounts() {} function clientLog() {} var calls = [];
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({}); }
        function refreshData() { return Promise.resolve(); }
        var bank = { id: 5, name: "Old bank", type: "BANK", level: 1, parentId: null, active: true, balance: 1500, openingBalance: 1000, currency: "INR", targetAmount: null, notes: null };`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const shown = (p, id) => !p.node(id).classList.contains("hidden");

test("an active account offers Save, Deactivate and Reset balance, with the fields on", () => {
    const p = page();
    p.run("openEditAccount(bank)");
    assert.equal(p.node("editAccountToggle").textContent, "Deactivate");
    assert.ok(shown(p, "editAccountSave") && shown(p, "editAccountReset"));
    assert.equal(p.node("editAccountName").disabled, false);
    assert.equal(p.node("editOpening").disabled, false);
});

test("Reset balance is not offered when the balance is already zero", () => {
    const p = page();
    p.run("openEditAccount({ ...bank, balance: 0 })");
    assert.ok(!shown(p, "editAccountReset"));
});

test("a deactivated account has its fields and Save off, no Reset balance, and Activate", () => {
    const p = page();
    p.run("openEditAccount({ ...bank, active: false })");
    assert.equal(p.node("editAccountToggle").textContent, "Activate");
    assert.ok(!shown(p, "editAccountSave") && !shown(p, "editAccountReset"));
    assert.equal(p.node("editAccountName").disabled, true);
    assert.equal(p.node("editOpening").disabled, true);
    assert.match(p.node("editAccountHint").textContent, /deactivated/);
});

test("Deactivate asks, then switches the account off; Activate switches it on without asking", async () => {
    const p = page();
    p.run("initEditAccount(); openEditAccount(bank)");
    p.node("editAccountToggle").fire("click");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/accounts/5/deactivate", method: "POST"}]);

    const q = page();
    q.run("initEditAccount(); confirm = () => { throw new Error('should not ask'); }; openEditAccount({ ...bank, active: false })");
    q.node("editAccountToggle").fire("click");
    await flush();
    assert.deepEqual(q.run("calls"), [{ path: "/api/accounts/5/activate", method: "POST"}]);
});

test("Reset balance opens a window that says what will happen and needs a note, then sends it", async () => {
    const p = page();
    p.run("initEditAccount(); openEditAccount(bank)");
    p.node("editAccountReset").fire("click");
    assert.equal(p.node("ledgerTitle").textContent, "Reset balance · Old bank");
    assert.ok(p.node("editAccountOverlay").classList.contains("hidden"), "the Edit window is closed");
    assert.match(p.node("ledgerBody").allText, /It holds ₹1,500/);
    assert.match(p.node("ledgerBody").allText, /Balance Adjustment/);

    p.run("dlg.onPrimary()");   // no note yet
    assert.match(p.node("ledgerError").textContent, /what happened to the money/);
    assert.deepEqual(p.run("calls"), []);

    p.node("ledgerBody").find(n => n.tag === "input")[0].value = "  Withdrawn in cash and closed  ";
    p.run("dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/accounts/5/reset-balance", method: "POST", body: { note: "Withdrawn in cash and closed" } }]);
});

test("for a loan or card the window says what it owes", () => {
    const p = page();
    p.run("initEditAccount(); openEditAccount({ ...bank, type: 'DEBT', balance: 700 })");
    p.node("editAccountReset").fire("click");
    assert.match(p.node("ledgerBody").allText, /It owes ₹700/);
});

test("Write off on a debt you owe asks why, then sends the same Balance Adjustment as Reset balance", async () => {
    const p = page();
    p.run("openWriteOffDebt({ ...bank, type: 'DEBT', name: 'Car loan', balance: 70000 })");
    assert.equal(p.node("ledgerTitle").textContent, "Write off · Car loan");
    assert.equal(p.node("ledgerPrimary").textContent, "Write off");
    assert.match(p.node("ledgerBody").allText, /It owes ₹70,000\. Writing it off lets all of it go/);
    p.run("dlg.onPrimary()");
    assert.match(p.node("ledgerError").textContent, /why it is being let go/);
    p.node("ledgerBody").find(n => n.tag === "input")[0].value = "The bank waived it";
    p.run("dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/accounts/5/reset-balance", method: "POST", body: { note: "The bank waived it" } }]);
});

test("Write off on a person lets go of what they owe you, with a note", async () => {
    const p = page();
    p.run("openWriteOffPerson({ id: 3, name: 'Ravi', owed: 4500 })");
    assert.equal(p.node("ledgerTitle").textContent, "Write off · Ravi");
    assert.match(p.node("ledgerBody").allText, /Ravi owes you ₹4,500/);
    p.run("dlg.onPrimary()");
    assert.match(p.node("ledgerError").textContent, /why you are letting it go/);
    assert.deepEqual(p.run("calls"), []);
    p.node("ledgerBody").find(n => n.tag === "input")[0].value = "  He lost his job  ";
    p.run("dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/borrowers/3/write-off", method: "POST", body: { note: "He lost his job" } }]);
});

test("Write off is a button in a person's Edit window, shown only while they owe money, and it opens the write-off dialog", () => {
    const p = page();
    p.run("openPerson({ id: 3, name: 'Ravi', owed: 4500, openingAmount: 0 })");
    assert.equal(p.node("personWriteOff").classList.contains("hidden"), false);
    p.run("writeOffEditedPerson()");
    assert.equal(p.node("personOverlay").classList.contains("hidden"), true);
    assert.equal(p.node("ledgerTitle").textContent, "Write off · Ravi");
    p.run("openPerson({ id: 4, name: 'Sita', owed: 0, openingAmount: 0 })");
    assert.equal(p.node("personWriteOff").classList.contains("hidden"), true, "nothing owed, nothing to let go");
    p.run("openPerson(null)");
    assert.equal(p.node("personWriteOff").classList.contains("hidden"), true);
});

test("Write off also shows for a person you owe, and says so", () => {
    const p = page();
    p.run("openPerson({ id: 5, name: 'Sam', owed: -0.5, openingAmount: 0 })");
    assert.equal(p.node("personWriteOff").classList.contains("hidden"), false);
    p.run("writeOffEditedPerson()");
    assert.match(p.node("ledgerBody").allText, /You owe Sam ₹0\.5/);
});
