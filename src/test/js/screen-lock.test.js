const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The dashboard opens locked; a refresh of an unlocked tab does not lock it again. */
function page({ blocked = false } = {}) {
    const p = createPage();
    if (blocked) p.run(`sessionStorage = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); }, removeItem() { throw new Error("blocked"); } }`);
    p.load("core/constants.js", "core/util.js", "panels/password.js");
    p.run(`var calls = [], refused = false, reloaded = 0;
        function api(path, options) { calls.push(path); return refused ? Promise.reject(new Error("Wrong password")) : Promise.resolve(null); }
        location.reload = () => { reloaded++; };`);
    p.node("lockOverlay").classList.add("hidden");   // the page starts with the cover hidden
    return p;
}
const locked = p => !p.node("lockOverlay").classList.contains("hidden");
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("a new tab opens locked", () => {
    const p = page();
    p.run("startScreenLock()");
    assert.equal(locked(p), true);
});

test("typing the password unlocks it, and a refresh of that tab stays unlocked", async () => {
    const p = page();
    p.run("startScreenLock()");
    p.node("lockPassword").value = "secret";
    p.run("unlockScreen()");
    await flush();
    assert.equal(locked(p), false);
    assert.equal(p.run("wasUnlocked()"), true);
    p.run("screenLockEnabled = false; startScreenLock()");   // a refresh: the script starts again, the tab's storage stays
    assert.equal(locked(p), false, "a refresh of an unlocked tab does not lock it");
});

test("a wrong password leaves it locked and a refresh of a locked tab is still locked", async () => {
    const p = page();
    p.run("startScreenLock()");
    p.run("refused = true");
    p.run("unlockScreen()");
    await flush();
    assert.equal(locked(p), true);
    p.run(`document.getElementById("lockOverlay").classList.add("hidden"); screenLockEnabled = false; startScreenLock()`);
    assert.equal(locked(p), true, "the flag was never set, so the refresh locks again");
});

test("the lock button and the idle timer lock it again and forget that it was unlocked", async () => {
    const p = page();
    p.run(`rememberUnlocked(true); screenLockEnabled = true`);
    p.run("lockScreen()");
    assert.equal(locked(p), true);
    assert.equal(p.run("wasUnlocked()"), false);
});

test("nothing locks before a password exists", () => {
    const p = page();
    p.run("lockScreen()");   // screenLockEnabled is false until startScreenLock() runs after the password is known
    assert.equal(locked(p), false);
});

test("setting or changing the password counts as unlocked, so the reload does not ask again", async () => {
    const p = page();
    p.run(`openPassword("change")`);
    p.node("passwordNew").value = "longenough1";
    p.node("passwordRepeat").value = "longenough1";
    p.node("passwordCurrent").value = "old";
    p.run("savePassword()");
    await flush();
    assert.equal(p.run("reloaded"), 1);
    assert.equal(p.run("wasUnlocked()"), true);
});

test("when the browser blocks storage the page just opens locked every time", () => {
    const p = page({ blocked: true });
    assert.doesNotThrow(() => p.run("rememberUnlocked(true)"));
    p.run("startScreenLock()");
    assert.equal(locked(p), true);
});
