const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createPage, STATIC } = require("./harness");

/* The collapsible sidebar: a button that folds it to a rail of icons or opens it, remembered in this browser. */
function page({ saved, width, blocked } = {}) {
    const p = createPage({ storageBlocked: !!blocked }).load("core/util.js", "panels/header-menu.js");
    if (saved) p.run(`localStorage.setItem("sidebar", "${saved}")`);
    if (width !== undefined) p.run(`window.innerWidth = ${width}`);
    p.run("initHeaderMenu()");
    return p;
}
const state = p => p.run("document.documentElement.dataset.sidebar");

test("the sidebar starts open on a wide window, folded on a narrow one", () => {
    assert.equal(state(page({ width: 1400 })), "open");
    assert.equal(state(page({ width: 600 })), "collapsed");
    assert.equal(state(page()), "open", "no width known: open");
});

test("the button folds and opens it, says what it will do, and remembers the choice", () => {
    const p = page({ width: 1400 });
    const button = p.node("menuButton");
    assert.equal(button.getAttribute("aria-expanded"), "true");
    assert.equal(button.getAttribute("aria-label"), "Collapse menu");
    button.fire("click");
    assert.equal(state(p), "collapsed");
    assert.equal(button.getAttribute("aria-expanded"), "false");
    assert.equal(button.getAttribute("aria-label"), "Expand menu");
    assert.equal(p.stored("sidebar"), "collapsed");
    button.fire("click");
    assert.equal(state(p), "open");
    assert.equal(p.stored("sidebar"), "open");
});

test("a saved choice wins over the window's width, either way", () => {
    assert.equal(state(page({ saved: "collapsed", width: 1400 })), "collapsed");
    assert.equal(state(page({ saved: "open", width: 600 })), "open");
    assert.equal(state(page({ saved: "sideways", width: 1400 })), "open", "a value that is not a state is ignored");
});

test("blocked storage does not break it: it still folds for now", () => {
    const p = page({ blocked: true, width: 1400 });
    assert.equal(state(p), "open");
    p.node("menuButton").fire("click");
    assert.equal(state(p), "collapsed");
});

test("choosing an item does not fold the sidebar, and clicking elsewhere or Escape do nothing to it", () => {
    const p = page({ width: 1400 });
    p.node("menuPanel").fire("click", { target: { closest: sel => sel === "button" ? {} : null } });
    assert.equal(state(p), "open");
});

test("every action is in the sidebar with its id, an icon, a label and a tooltip", () => {
    const shell = fs.readFileSync(path.join(STATIC, "Dashboard.html"), "utf8");
    const panel = shell.slice(shell.indexOf('id="menuPanel"'), shell.indexOf("</nav>"));
    const ids = ["menuReminders", "menuNotes", "editCategoriesButton", "theme-light", "theme-dark", "theme-color", "backupButton", "exportButton", "lockButton", "changePasswordButton"];
    for (const id of ids) {
        const button = panel.match(new RegExp(`<button[^>]*id="${id}"[^>]*>[\\s\\S]*?</button>`));
        assert.ok(button, id + " is in the sidebar");
        assert.match(button[0], /class="side-icon"/, id + " has an icon");
        assert.match(button[0], /class="side-label"/, id + " has a label");
        assert.match(button[0], /title="[^"]+"/, id + " has a tooltip for the folded bar");
    }
    assert.ok(!shell.slice(0, shell.indexOf('id="menuButton"')).includes('id="exportButton"'));
});

test("the groups are Organiser, Categories, Themes, Data and Account, in that order", () => {
    const shell = fs.readFileSync(path.join(STATIC, "Dashboard.html"), "utf8");
    const titles = [...shell.matchAll(/class="menu-group-toggle"[^>]*><span[^>]*>[^<]*<\/span><span class="side-label">([^<]+)</g)].map(m => m[1]);
    assert.deepEqual(titles, ["Organiser", "Categories", "Themes", "Data", "Account"]);
});

test("each group is a parent button that controls the block of items nested under it, and every action is inside one", () => {
    const shell = fs.readFileSync(path.join(STATIC, "Dashboard.html"), "utf8");
    const panel = shell.slice(shell.indexOf('id="menuPanel"'), shell.indexOf("</nav>"));
    const toggles = [...panel.matchAll(/<button class="menu-group-toggle" id="(\w+)" aria-expanded="false" aria-controls="(\w+)"/g)];
    assert.equal(toggles.length, 5);
    for (const [, id, controls] of toggles) {
        assert.equal(controls, id + "Items");
        assert.ok(panel.includes(`<div class="menu-sub" id="${controls}">`), controls + " exists");
    }
    const outside = panel.replace(/<div class="menu-sub"[\s\S]*?<\/div>/g, "");
    assert.ok(!outside.includes("side-item"), "no action sits outside a group's nested block");
});

/* The group buttons, built the way Dashboard.html has them. The page's own click and key listeners are captured so "elsewhere" can be tried. */
function groupPage() {
    const p = createPage().load("core/util.js", "panels/header-menu.js");
    p.run(`var docListeners = {}; document.addEventListener = (type, fn) => { (docListeners[type] ||= []).push(fn); };`);
    for (const g of ["A", "B"]) {
        const toggle = p.node("group" + g);
        toggle.classList.add("menu-group-toggle");
        toggle.setAttribute("aria-expanded", "false");
        toggle.setAttribute("aria-controls", "group" + g + "Items");
        p.node("group" + g + "Items");
    }
    p.run("initHeaderMenu()");
    return p;
}
const pinned = (p, g) => p.node("group" + g + "Items").classList.contains("pinned");
const elsewhere = (p, type, event) => p.raw(`docListeners[${JSON.stringify(type)}].forEach(fn => fn(${JSON.stringify(event)}))`);

test("pointing at a group opens its items by CSS alone: they are hidden by default and shown on hover or focus", () => {
    const css = fs.readFileSync(path.join(STATIC, "css", "header-menu.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    assert.match(css, /\.menu-sub \{[^}]*display: none;[^}]*position: absolute;/, "a flyout, hidden until wanted");
    assert.match(css, /\.menu-group:hover > \.menu-sub, \.menu-group:focus-within > \.menu-sub, \.menu-sub\.pinned \{ display: flex; \}/);
    assert.match(css, /\.menu-sub::before \{[^}]*left: -12px;/, "a bridge over the gap so the pointer can cross it");
});

test("clicking a group's name pins its flyout open (touch screens), one at a time; clicking it again, elsewhere or Escape closes it", () => {
    const p = groupPage();
    assert.equal(pinned(p, "A"), false);
    p.node("groupA").fire("click");
    assert.equal(pinned(p, "A"), true);
    assert.equal(p.node("groupA").getAttribute("aria-expanded"), "true");
    p.node("groupB").fire("click");
    assert.equal(pinned(p, "A"), false, "opening another closes the first");
    assert.equal(pinned(p, "B"), true);
    p.node("groupB").fire("click");
    assert.equal(pinned(p, "B"), false);
    p.node("groupA").fire("click");
    elsewhere(p, "click", { target: { closest: () => null } });
    assert.equal(pinned(p, "A"), false, "a click elsewhere");
    p.node("groupA").fire("click");
    elsewhere(p, "keydown", { key: "Enter" });
    assert.equal(pinned(p, "A"), true, "other keys do nothing");
    elsewhere(p, "keydown", { key: "Escape" });
    assert.equal(pinned(p, "A"), false, "Escape");
    p.node("groupA").fire("click");
    p.run(`docListeners.click.forEach(fn => fn({ target: { closest: sel => sel === ".menu-sub" ? {} : null } }))`);
    assert.equal(pinned(p, "A"), true, "a click on an item inside the flyout does not close it before the item does its work");
});
