const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Note field offers what was typed before in the chosen category or sub category, not everything at once. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; } function clientLog() {}
        txn.categories = [{ id: 1, parentId: null }, { id: 2, parentId: 1 }, { id: 3, parentId: 1 }, { id: 9, parentId: null }];
        const e = (id, categoryId, note, kind = "EXPENSE") => ({ id, kind, categoryId, note });
        transactions = [e(1, 2, "Milk"), e(2, 2, "Milk"), e(3, 2, " Curd "), e(4, 3, "Paneer"), e(5, 1, "Weekly shop"), e(6, 9, "Taxi"), e(7, 2, "Salary note", "INCOME"), e(8, 2, null),
            { id: 9, kind: "TRANSFER", categoryId: null, note: "To savings" }];`);
    return p;
}

test("a sub category offers only its own notes, most used first", () => {
    assert.deepEqual(page().run(`previousNotes("EXPENSE", "1", "2")`), ["Milk", "Curd"]);
});

test("a category alone offers the notes of the category and all its sub categories", () => {
    const names = page().run(`previousNotes("EXPENSE", "1", "")`);
    assert.equal(names[0], "Milk");
    assert.deepEqual([...names].sort(), ["Curd", "Milk", "Paneer", "Weekly shop"]);
});

test("nothing is offered until a category is chosen, and other kinds are left out", () => {
    const p = page();
    assert.deepEqual(p.run(`previousNotes("EXPENSE", "", "")`), []);
    assert.deepEqual(p.run(`previousNotes("INCOME", "1", "")`), ["Salary note"]);
    assert.deepEqual(p.run(`previousNotes("TRANSFER", "", "")`), ["To savings"], "a transfer has no category");
});

test("at most NOTE_SUGGESTIONS notes are offered", () => {
    const p = page();
    p.run(`transactions = Array.from({ length: 40 }, (_, i) => ({ id: i, kind: "EXPENSE", categoryId: 9, note: "note " + i }))`);
    assert.equal(p.run(`previousNotes("EXPENSE", "9", "").length`), 15);
});
