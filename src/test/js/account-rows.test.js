const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Investment and gold rows carry an (i) saying a sale is recorded as a transfer; other rows do not. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js", "panels/loan-calc.js", "panels/loan-block.js", "panels/accounts.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }  function clientLog() {}
        function openEditAccount() {}  function showTransactionsFor() {}  function openLiabilityPayment() { return Promise.resolve(); }
        const row = a => accountRow(Object.assign({ level: 3, parentId: null, active: true, balance: 100, openingBalance: 100, ringFenced: false }, a), new Map());`);
    return p;
}
const buttons = (p, expr) => p.raw(expr).find(n => n.tag === "button");

test("an Index Fund (investment) row and a Gold row have an (i) that opens how to record a sale", () => {
    for (const [id, name, type] of [[2, "Index Fund", "INVESTMENT"], [3, "Gold", "GOLD"]]) {
        const p = page();
        const node = p.raw(`row({ id: ${id}, name: "${name}", type: "${type}" })`);
        const info = node.find(n => n.tag === "button" && n.textContent === "ⓘ")[0];
        assert.ok(info, `${name} has an (i)`);
        const hint = node.find(n => n.className.includes("edit-hint"))[0];
        assert.match(hint.textContent, /transfer from this account to your bank or cash account/);
        assert.match(hint.textContent, /red \+ and choose Transfer/);
        assert.equal(hint.classList.contains("hidden"), true);
        info.fire("click");
        assert.equal(hint.classList.contains("hidden"), false);
    }
});

test("other accounts, and an inactive investment, have no (i)", () => {
    const p = page();
    for (const spec of [`{ id: 1, name: "HDFC", type: "BANK" }`, `{ id: 4, name: "Flat", type: "PROPERTY" }`,
            `{ id: 5, name: "Old fund", type: "INVESTMENT", active: false }`]) {
        const node = p.raw(`row(${spec})`);
        assert.equal(node.find(n => n.tag === "button" && n.textContent === "ⓘ").length, 0, spec);
    }
});

test("a loan row still has its Pay button and no sale (i)", () => {
    const p = page();
    const node = p.raw(`row({ id: 6, name: "Car loan", type: "DEBT", level: 6 })`);
    assert.ok(node.find(n => n.tag === "button" && n.textContent === "Pay").length === 1);
    assert.equal(node.find(n => n.textContent === "ⓘ").length, 0);
});
