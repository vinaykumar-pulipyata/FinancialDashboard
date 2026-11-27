const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Jewellery card: items grouped by kind, exact amounts, and the Add, Sell, Edit and Remove dialogs. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js", "panels/jewellery.js", "panels/recurring-deposits.js");
    p.run(`function renderAccounts() {} function clientLog() {} var calls = [];
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({}); }
        accounts = [{ id: 1, name: "Savings Bank", type: "BANK", active: true, balance: 50000 }, { id: 2, name: "Goal", type: "GOAL_FUND", active: true, balance: 5 }];
        jewellery = { configured: true, accountId: 9, name: "Jewellery", boughtPrice: 313500.5, metals: [
            { metal: "Gold", weight: 41.5, boughtPrice: 257500.5, items: [
                { id: 1, metal: "Gold", name: "1 g coin", form: "COIN", weight: 1, boughtPrice: 7500.5 },
                { id: 2, metal: "Gold", name: "Wedding necklace", form: "ORNAMENT", weight: 40.5, boughtPrice: 250000 }] },
            { metal: "Silver", weight: 200, boughtPrice: 18000, items: [{ id: 3, metal: "Silver", name: "Pooja plate", form: "ORNAMENT", weight: 200, boughtPrice: 18000 }] },
            { metal: "Platinum", weight: 5, boughtPrice: 38000, items: [{ id: 4, metal: "Platinum", name: "Ring", form: "OTHER", weight: 5, boughtPrice: 38000 }] }] };`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("the account is recognised, so ordinary forms leave it out", () => {
    const p = page();
    assert.equal(p.run(`isJewelleryAccount(9)`), true);
    assert.equal(p.run(`isJewelleryAccount(1)`), false);
    p.run(`jewellery = { configured: false, metals: [] }`);
    assert.equal(p.run(`isJewelleryAccount(9)`), false);
});

test("closed, the card shows the total cost and the weight of each kind; open, each item with its type, weight, price per gram and exact cost", () => {
    const p = page();
    const card = () => p.raw(`jewelleryCard()`);
    assert.ok(card().allText.includes("Bought for ₹3,13,500.50"), card().allText);
    assert.ok(card().allText.includes("Gold 41.5 g") && card().allText.includes("Silver 200 g") && card().allText.includes("Platinum 5 g"));
    assert.equal(card().find(n => n.className === "jw-item").length, 0, "the list starts closed");
    p.run(`jewelleryExpanded = true`);
    const open = card();
    assert.deepEqual(open.find(n => n.className === "jw-metal").map(n => n.textContent), ["Gold", "Silver", "Platinum"]);
    assert.deepEqual(open.find(n => n.className === "lg-name").map(n => n.textContent), ["1 g coin", "Wedding necklace", "Pooja plate", "Ring"]);
    assert.ok(open.allText.includes("Ornament · 40.5 g · ₹6,172.84/g"), open.allText);
    assert.ok(open.allText.includes("₹7,500.50"), "exact, not rounded");
    assert.ok(!/current value|profit|loss/i.test(open.find(n => n.className === "jw-item").map(n => n.allText).join(" ")), "no current value or profit while held");
});

test("before set up the card offers Set up; with a set-up account and no items, Add item", () => {
    const p = page();
    p.run(`jewellery = { configured: false, metals: [] }`);
    assert.equal(p.raw(`jewelleryCard()`).find(n => n.tag === "button")[0].textContent, "Set up");
    p.run(`jewellery = { configured: true, accountId: 9, boughtPrice: 0, metals: [] }`);
    assert.equal(p.raw(`jewelleryCard()`).find(n => n.tag === "button")[0].textContent, "Add item");
});

test("the add dialog sends a new item, paid from a bank account or already owned, and a custom kind is typed in", async () => {
    const p = page();
    p.run(`jewelleryExpanded = true; var f = {}; openItemDialog(null)`);
    const body = p.node("ledgerBody");
    const inputs = body.find(x => x.tag === "input");
    const [kind, form, paid] = body.find(x => x.tag === "select");
    assert.deepEqual(kind.children.map(o => o.textContent), ["Gold", "Silver", "Platinum", "Diamond", "Another kind…"]);
    assert.deepEqual(paid.children.map(o => o.textContent.split(" · ")[0]), ["Already owned (no money moves)", "Savings Bank"], "bank and cash only");
    kind.value = ""; kind.fire("change");
    inputs[0].value = "Palladium"; inputs[1].value = "Coin one"; inputs[2].value = "2.5"; inputs[3].value = "12000.75";
    form.value = "COIN";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/jewellery/items", method: "POST", body: { metal: "Palladium", name: "Coin one", form: "COIN", weight: 2.5, boughtPrice: 12000.75, paidFromAccountId: null } }]);
});

test("the sell dialog sends the weight, the price and the account, and refuses more than the weight held", async () => {
    const p = page();
    p.run(`openSellDialog(jewellery.metals[0].items[1])`);
    const body = p.node("ledgerBody");
    const inputs = body.find(x => x.tag === "input");
    assert.equal(Number(inputs[0].value), 40.5, "the full weight to start");
    body.find(x => x.tag === "select")[0].value = "1";
    inputs[0].value = "50"; inputs[1].value = "300000";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /up to 40.5 g/);
    inputs[0].value = "10.5";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/jewellery/items/2/sell", method: "POST", body: { toAccountId: 1, salePrice: 300000, weight: 10.5 } }]);
});

test("gold, silver, platinum and diamond each get their own colour class, other kinds the plain one, and each open group is wrapped in it", () => {
    const p = page();
    assert.deepEqual(p.run(`["Gold", "silver ", "Platinum", "Diamond", "Palladium"].map(kindClass)`), ["jw-gold", "jw-silver", "jw-platinum", "jw-diamond", "jw-other"]);
    p.run(`jewelleryExpanded = true`);
    const kinds = p.raw(`jewelleryCard()`).find(n => String(n.className).startsWith("jw-kind")).map(n => n.className);
    assert.deepEqual(kinds, ["jw-kind jw-gold", "jw-kind jw-silver", "jw-kind jw-platinum"]);
});

test("Diamond is offered in the Kind list of a new item, before kinds already in use", () => {
    const p = page();
    p.run(`jewellery.metals.push({ metal: "Palladium", weight: 1, boughtPrice: 1, items: [] })`);
    p.run(`var f = {}; openItemDialog(null)`);
    const select = p.node("ledgerBody").find(n => n.tag === "select")[0];
    assert.deepEqual(select.children.map(o => o.textContent), ["Gold", "Silver", "Platinum", "Diamond", "Palladium", "Another kind…"]);
});
