const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Emergency Fund card: the pencil is on the left, beside the title, and Settings stays on the right. */
function card(extra = "") {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/emergency-fund.js");
    p.run(`var Node = function () {}; function openEditAccount() {} function fenceBanner() { return el("div"); }
        accounts = [{ id: 5, name: "Emergency Fund", type: "GOAL_FUND" }];
        emergencyFund = Object.assign({ configured: true, accountId: 5, name: "Emergency Fund", ringFenced: false, targetReached: false, runwayMonths: null }, ${extra || "{}"});`);
    return p.raw("emergencyCard()");
}

test("the pencil sits in the title block on the left, and Settings is the last thing in the head on the right", () => {
    const head = card().find(n => n.className === "ef-head")[0];
    const [title, settings] = head.children;
    assert.equal(title.className, "ef-title");
    const pencil = title.find(n => n.className.includes("row-edit"))[0];
    assert.ok(pencil, "the pencil is inside the title block");
    assert.equal(pencil.textContent, "✏️");
    assert.equal(title.children[title.children.length - 1], pencil, "after the title and its (i)");
    assert.equal(settings.textContent, "Settings");
    assert.equal(head.find(n => n.className.includes("row-edit")).length, 1, "only one pencil");
});

test("no pencil and no Settings while the fund is ring-fenced; a card not set up has only the Set up button", () => {
    const fenced = card("{ ringFenced: true }").find(n => n.className === "ef-head")[0];
    assert.equal(fenced.find(n => n.className.includes("row-edit")).length, 0);
    const blank = card("{ configured: false }").find(n => n.className === "ef-head")[0];
    assert.equal(blank.find(n => n.className.includes("row-edit")).length, 0);
    assert.equal(blank.children[blank.children.length - 1].textContent, "Set up");
});
