const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

const page = () => createPage().load("core/constants.js", "core/util.js", "panels/exchange-rates.js");

test("the list of currencies is INR, USD, EUR, GBP and VND, and VND has no decimals", () => {
    const p = page();
    assert.deepEqual(p.run("Object.keys(CURRENCIES)"), ["INR", "USD", "EUR", "GBP", "VND"]);
    assert.equal(p.run("CURRENCIES.VND.decimals"), 0);
    assert.equal(p.run("CURRENCIES.USD.decimals"), 2);
});

test("rupees need no rate, so only the foreign currencies are offered", () => {
    assert.deepEqual(page().run("foreignCurrencies()"), ["USD", "EUR", "GBP", "VND"]);
});

test("a rate is shown without trailing zeros, so a small one keeps its digits", () => {
    const p = page();
    assert.equal(p.run("rateText('0.00330000')"), "0.0033");
    assert.equal(p.run("rateText('85.50000000')"), "85.5");
});

test("only the asset kinds of account can be held in a foreign currency", () => {
    const p = page();
    for (const type of ["BANK", "CASH", "WALLET", "INVESTMENT", "PROPERTY", "VEHICLE", "OTHER"]) assert.equal(p.run(`CURRENCY_TYPES.has("${type}")`), true, type);
    for (const type of ["GOAL_FUND", "JEWELLERY", "RECEIVABLE", "DEBT", "CREDIT_CARD"]) assert.equal(p.run(`CURRENCY_TYPES.has("${type}")`), false, type);
});
