const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The backup bar: the "only on this computer" warning can be hidden with its ✕; real problems cannot. */
function page(opts) {
    const p = createPage(opts).load("core/util.js", "core/client-log.js", "panels/backup.js");
    p.run(`var logged = [];
        function clientLog(level, event) { logged.push(event); }   // the real one would post to the server`);
    return p;
}
const status = (extra = {}) => `{ ok: true, lastSuccess: "2026-10-22T10:00:00", lastMessage: "", directory: "./backups", copyDir: null,
    level: "WARNING", banner: "Your backups are only on this computer.", files: [], ...${JSON.stringify(extra)} }`;
const bar = p => p.node("backupBanner");

test("the warning has a ✕ that hides it and the choice is kept", () => {
    const p = page();
    p.run(`backupStatus = ${status()}; renderBackup()`);
    assert.ok(!bar(p).classList.contains("hidden"));
    const hide = bar(p).find(n => n.className === "backup-dismiss");
    assert.equal(hide.length, 1);
    hide[0].fire("click", { stopPropagation() {} });
    assert.ok(bar(p).classList.contains("hidden"));
    assert.equal(p.stored("backupWarningHidden"), "1");
    assert.deepEqual(p.run("logged"), ["backup.warning-hidden"]);
    p.run(`renderBackup()`);
    assert.ok(bar(p).classList.contains("hidden"), "still hidden on the next redraw");
});

test("an error is never hidden and has no ✕, even after the warning was hidden", () => {
    const p = page();
    p.run(`backupStatus = ${status()}; renderBackup()`);
    bar(p).find(n => n.className === "backup-dismiss")[0].fire("click", { stopPropagation() {} });
    p.run(`backupStatus = ${status({ level: "ERROR", banner: "No backup has been made yet." })}; renderBackup()`);
    assert.ok(!bar(p).classList.contains("hidden"));
    assert.equal(bar(p).find(n => n.className === "backup-dismiss").length, 0);
    assert.match(bar(p).allText, /No backup has been made yet/);
});

test("choosing a second folder forgets the choice, so the warning returns if the folder is removed", () => {
    const p = page();
    p.run(`backupStatus = ${status()}; renderBackup()`);
    bar(p).find(n => n.className === "backup-dismiss")[0].fire("click", { stopPropagation() {} });
    p.run(`backupStatus = ${status({ copyDir: "/Volumes/usb", level: "NONE", banner: null })}; renderBackup()`);
    assert.ok(bar(p).classList.contains("hidden"));
    p.run(`backupStatus = ${status()}; renderBackup()`);
    assert.ok(!bar(p).classList.contains("hidden"), "the folder is gone again: the warning is back");
});

test("blocked browser storage just means the warning comes back next time", () => {
    const p = page({ storageBlocked: true });
    p.run(`backupStatus = ${status()}; renderBackup()`);
    bar(p).find(n => n.className === "backup-dismiss")[0].fire("click", { stopPropagation() {} });
    assert.ok(!bar(p).classList.contains("hidden"));
});
