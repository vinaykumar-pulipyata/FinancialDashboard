const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createPage, STATIC } = require("./harness");

/* Categories is a page of the view card (opened from the menu), not a popup: a card per category with its sub categories and how many
   transactions use each. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "panels/categories.js");
    p.run(`var calls = [], refreshes = 0;
        function clientLog() {} function describeError(e) { return String(e && e.message); }
        function refreshData() { refreshes++; return Promise.resolve(); }
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({}); }
        confirm = () => true;
        allCategories = [
            { id: 1, name: "Food", kind: "EXPENSE", parentId: null, active: true, systemKey: null },
            { id: 2, name: "Vegetables", kind: "EXPENSE", parentId: 1, active: true, systemKey: null },
            { id: 3, name: "Fruits", kind: "EXPENSE", parentId: 1, active: false, systemKey: null },
            { id: 4, name: "Housing", kind: "EXPENSE", parentId: null, active: true, systemKey: null },
            { id: 5, name: "Liability Payments", kind: "EXPENSE", parentId: null, active: true, systemKey: "LIABILITY_PAYMENT" },
            { id: 6, name: "Salary", kind: "INCOME", parentId: null, active: true, systemKey: null },
            { id: 7, name: "Loans Collected", kind: "INCOME", parentId: null, active: true, systemKey: "LOANS_COLLECTED" }];
        transactions = [{ id: 1, categoryId: 2 }, { id: 2, categoryId: 2 }, { id: 3, categoryId: 3 }, { id: 4, categoryId: 1 }, { id: 5, categoryId: 6 }];`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("Categories is a menu item that turns the card to its own page, and the popup is gone", () => {
    const shell = fs.readFileSync(path.join(STATIC, "Dashboard.html"), "utf8");
    const workspace = fs.readFileSync(path.join(STATIC, "panels", "workspace.html"), "utf8");
    const fragment = fs.readFileSync(path.join(STATIC, "panels", "categories.html"), "utf8");
    assert.match(shell, /class="btn btn-secondary side-item menu-view" id="editCategoriesButton" data-view="categories"/);
    assert.ok(!shell.includes('data-include="panels/categories.html"'), "no longer a popup of the shell");
    assert.ok(workspace.includes('<div id="categoriesView"><div data-include="panels/categories.html"></div></div>'), "a view in the holder");
    assert.ok(!fragment.includes("modal-overlay") && !fragment.includes("categoriesOverlay"), "no modal");
});

test("the page opens on Expense with a card per category: its sub categories inside, and the tabs count each kind", () => {
    const p = page();
    p.run("renderCategories()");
    assert.deepEqual(p.node("catTabs").find(n => n.tag === "button").map(n => n.textContent), ["Expense (3)", "Income (2)"]);
    const cards = p.node("catList").find(n => String(n.className).startsWith("cat-card"));
    assert.equal(cards.length, 3, "Food, Housing and the fixed Liability Payments");
    const food = cards[0];
    assert.deepEqual(food.find(n => n.className === "cat-name").map(n => n.textContent), ["Food (4)", "Fruits (1)", "Vegetables (2)"], "by name, subs under their category");
});

test("each name shows how many transactions use it, a category counting its sub categories too", () => {
    const p = page();
    p.run("renderCategories()");
    const food = p.node("catList").find(n => String(n.className).startsWith("cat-card"))[0];
    const tags = food.find(n => String(n.className).includes("cat-tag")).map(n => n.textContent);
    // Food (4): its own 1, Fruits 1, Vegetables 2, the count right after the name; Fruits is inactive
    assert.deepEqual(food.find(n => n.className === "cat-name").map(n => n.textContent), ["Food (4)", "Fruits (1)", "Vegetables (2)"]);
    assert.deepEqual(tags, ["inactive"]);
});

test("the Income tab lists the income categories; the fixed ones are locked and have no buttons", () => {
    const p = page();
    p.run("renderCategories()");
    p.node("catTabs").find(n => n.tag === "button")[1].fire("click");
    const names = p.node("catList").find(n => n.className === "cat-name").map(n => n.textContent);
    assert.deepEqual(names, ["Loans Collected (0)", "Salary (1)"]);
    const fixed = p.node("catList").find(n => String(n.className).startsWith("cat-card"))[0];
    assert.ok(fixed.allText.includes("🔒 Fixed · people are managed under Receivables"));
    assert.equal(fixed.find(n => n.tag === "button").length, 0, "nothing to rename, deactivate or delete");
    p.node("catTabs").find(n => n.tag === "button")[0].fire("click");
    const liability = p.node("catList").find(n => String(n.className).startsWith("cat-card"))[2];
    assert.ok(liability.allText.includes("Made with the Pay button") || liability.allText.includes("made with the Pay button"), liability.allText);
});

test("a category is added from the bar at the top, to the kind on show", async () => {
    const p = page();
    p.run("renderCategories()");
    p.node("catNewName").value = "  Dairy ";
    p.run("addTopCategory()");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/categories", method: "POST", body: { name: "Dairy", kind: "EXPENSE", parentId: null } }]);
    assert.equal(p.node("catNewName").value, "", "the box is cleared");
    assert.ok(p.run("refreshes") >= 1, "everything is reloaded so the forms see it");
});

test("a server refusal is shown on the page as it is", async () => {
    const p = page();
    p.run(`api = () => Promise.reject(new Error("A category named 'Food' already exists"))`);
    p.run("renderCategories()");
    p.node("catNewName").value = "Food";
    p.run("addTopCategory()");
    await flush();
    assert.equal(p.node("categoriesError").textContent, "A category named 'Food' already exists");
});

test("showCategories starts a clean list: nothing is being renamed and the old error is gone", () => {
    const p = page();
    p.run(`catView.editing = 2; catView.addingSubFor = 1`);
    p.node("categoriesError").textContent = "old error";
    p.run("showCategories()");
    assert.equal(p.run("catView.editing"), null);
    assert.equal(p.run("catView.addingSubFor"), null);
    assert.equal(p.node("categoriesError").textContent, "");
});

test("deleting asks first and then calls the server; a sub category form opens under its category", async () => {
    const p = page();
    p.run("renderCategories()");
    const card = () => p.node("catList").find(n => String(n.className).startsWith("cat-card"))[0];
    const button = label => card().find(n => n.tag === "button" && n.textContent === label)[0];
    button("+ Sub category").fire("click");
    assert.ok(card().find(n => n.tag === "input").length >= 1, "a box for the new sub category");
    p.run(`catView.addingSubFor = null`);
    p.run("renderCategories()");
    button("Delete").fire("click");
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/categories/1", method: "DELETE" }]);
});
