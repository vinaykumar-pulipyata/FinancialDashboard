const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Budgets view: related budgets (a main category and its sub categories) are together in a group that folds. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/insights-calc.js", "panels/budget-calc.js", "panels/budgets.js");
    p.run(`function refreshData() { return Promise.resolve(); }
           var t = new Date(); var day = isoDay(new Date(t.getFullYear(), t.getMonth(), 1));
           allCategories = [{ id: 1, name: "Food", kind: "EXPENSE", parentId: null, active: true }, { id: 2, name: "Dining out", kind: "EXPENSE", parentId: 1, active: true },
                            { id: 3, name: "Travel", kind: "EXPENSE", parentId: null, active: true }];
           transactions = [{ id: 1, date: day, kind: "EXPENSE", amount: "300", categoryId: 2 }, { id: 2, date: day, kind: "EXPENSE", amount: "200", categoryId: 1 }];`);
    return p;
}
const show = (p, list) => { p.run(`budgets = ${JSON.stringify(list)}; renderBudgets()`); return p.node("bgBody"); };
const classes = body => body.find(n => n.className && n.className.includes("bg-")).map(n => n.className);

test("a main category and its sub category budgets are one group; the sub budgets sit in a folded block under the main one", () => {
    const p = page();
    const body = show(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 2, amount: "5000" }, { id: 3, categoryId: 3, amount: "2000" }]);
    assert.equal(body.find(n => n.className === "bg-group").length, 1, "Food and Dining out together; Travel alone is a plain card");
    const children = body.find(n => n.className.startsWith("bg-children"))[0];
    assert.ok(children.classList.contains("hidden"), "nothing needs attention, so it starts folded");
    assert.match(body.allText, /Food/);
    assert.match(body.allText, /1 sub budget/);
    assert.match(children.allText, /Dining out/);
    assert.doesNotMatch(children.allText, /Food › Dining out/, "inside its group a sub category shows just its own name");
});

test("the fold button opens and folds the group, and the choice survives a redraw", () => {
    const p = page();
    let body = show(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 2, amount: "5000" }]);
    const toggle = () => body.find(n => n.className === "bg-toggle")[0];
    assert.equal(toggle().getAttribute("aria-expanded"), "false");
    toggle().fire("click");
    body = p.node("bgBody");
    assert.equal(toggle().getAttribute("aria-expanded"), "true");
    assert.ok(!body.find(n => n.className.startsWith("bg-children"))[0].classList.contains("hidden"));
    body = show(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 2, amount: "5000" }]);
    assert.equal(toggle().getAttribute("aria-expanded"), "true", "a redraw keeps what the user chose");
    toggle().fire("click");
    body = p.node("bgBody");
    assert.equal(toggle().getAttribute("aria-expanded"), "false");
});

test("a group opens by itself while a budget in it needs attention", () => {
    const p = page();
    const body = show(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 2, amount: "100" }]);   // Dining out: 300 of 100
    assert.ok(!body.find(n => n.className.startsWith("bg-children"))[0].classList.contains("hidden"));
});

test("a main category with no budget of its own gets a plain header over its sub budgets", () => {
    const p = page();
    const body = show(p, [{ id: 2, categoryId: 2, amount: "5000" }]);
    const headless = body.find(n => n.className.includes("bg-headless"));
    assert.equal(headless.length, 1);
    assert.match(headless[0].allText, /Food/);
    assert.match(headless[0].allText, /no budget on the whole/);
});
