const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

const page = options => createPage(options).load("core/theme.js");
const theme = p => p.node && p.run("document.documentElement.dataset.theme");

test("with no saved choice the page follows the computer's setting", () => {
    assert.equal(theme(page({ darkSystem: false })), "light");
    assert.equal(theme(page({ darkSystem: true })), "dark");
});

test("a saved choice wins over the computer's setting, either way", () => {
    assert.equal(theme(page({ darkSystem: true, savedTheme: "light" })), "light");
    assert.equal(theme(page({ darkSystem: false, savedTheme: "dark" })), "dark");
});

test("a saved value that is not a theme is ignored", () => {
    assert.equal(theme(page({ darkSystem: true, savedTheme: "purple" })), "dark");
});

test("blocked storage does not break the page: it follows the computer and the switch still works for now", () => {
    const p = page({ darkSystem: true, storageBlocked: true });
    assert.equal(theme(p), "dark");
    p.run("initTheme()");
    p.node("theme-light").fire("click");
    assert.equal(theme(p), "light", "switched even though it cannot be saved");
});

test("there are three buttons, each picks its theme, saves it and shows as pressed while on", () => {
    const p = page({ darkSystem: false });
    p.run("initTheme()");
    const pressed = () => ["light", "dark", "color"].filter(t => p.node("theme-" + t).attrs["aria-pressed"] === "true");
    assert.deepEqual(pressed(), ["light"]);
    p.node("theme-dark").fire("click");
    assert.equal(theme(p), "dark");
    assert.deepEqual(pressed(), ["dark"]);
    assert.equal(p.stored("theme"), "dark", "remembered in this browser");
    p.node("theme-color").fire("click");
    assert.equal(theme(p), "color");
    assert.deepEqual(pressed(), ["color"]);
    assert.equal(p.stored("theme"), "color");
    p.node("theme-light").fire("click");
    assert.equal(theme(p), "light");
    assert.equal(p.stored("theme"), "light");
});

test("a saved Color theme is restored at load, and it is never picked from the computer's setting", () => {
    assert.equal(theme(page({ darkSystem: true, savedTheme: "color" })), "color");
    assert.equal(theme(page({ darkSystem: false })), "light");
});

test("while nothing is saved a change of the computer's setting is followed; once chosen it is not", () => {
    const p = page({ darkSystem: false });
    p.run("initTheme()");
    p.changeSystemTheme(true);
    assert.equal(theme(p), "dark");
    p.changeSystemTheme(false);
    assert.equal(theme(p), "light");
    p.node("theme-dark").fire("click");              // the user picks dark
    p.changeSystemTheme(false);
    assert.equal(theme(p), "dark", "a saved choice is left alone");
});

test("the theme can be applied before the button exists (it is loaded in the head)", () => {
    const p = createPage({ darkSystem: true });
    assert.doesNotThrow(() => p.load("core/theme.js"));
    assert.equal(p.run("currentTheme()"), "dark");
});
