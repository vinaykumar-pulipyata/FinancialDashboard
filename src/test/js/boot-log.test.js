const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { STATIC } = require("./harness");

/* boot-log.js is the log for a page whose modules never ran: it must report a failed script and a stalled start, and stop
   once the app says it has started. */
function boot() {
    const sent = [], listeners = {}, timers = [];
    const window = { addEventListener: (type, fn, capture) => { listeners[type] = { fn, capture }; } };
    const sandbox = { window, JSON, String, Promise,
        fetch: (url, options) => { sent.push({ url, method: options.method, header: options.headers["X-Requested-With"], body: JSON.parse(options.body) }); return Promise.resolve(); },
        setTimeout: (fn, ms) => timers.push({ fn, ms }) };
    vm.runInNewContext(fs.readFileSync(path.join(STATIC, "js/core/boot-log.js"), "utf8"), sandbox);
    return { sent, window, listeners, timers };
}

test("a script that fails to load is reported with its file name, from the capture phase", () => {
    const b = boot();
    assert.equal(b.listeners.error.capture, true);
    b.listeners.error.fn({ target: { tagName: "SCRIPT", src: "http://localhost:8080/js/panels/x.js" } });
    assert.deepEqual(b.sent.map(s => [s.url, s.method, s.header, s.body]),
        [["/api/client-log", "POST", "FinancialDashboard", { level: "ERROR", event: "boot.script-failed", detail: "js/panels/x.js" }]]);
});

test("an error before the app starts is reported with the browser's message and place; after it starts, client-log.js does that", () => {
    const b = boot();
    b.listeners.error.fn({ message: "SyntaxError: no export named y", filename: "http://localhost:8080/js/app.js", lineno: 3, colno: 9 });
    assert.deepEqual(b.sent[0].body, { level: "ERROR", event: "boot.error", detail: "SyntaxError: no export named y [js/app.js:3:9]" });
    b.window.dashboardStarted = true;
    b.listeners.error.fn({ message: "later", filename: "", lineno: 0 });
    assert.equal(b.sent.length, 1);
});

test("10 seconds without the app starting is reported; an app that started is not", () => {
    const stalled = boot();
    assert.equal(stalled.timers[0].ms, 10000);
    stalled.timers[0].fn();
    assert.equal(stalled.sent[0].body.event, "boot.stalled");
    const fine = boot();
    fine.window.dashboardStarted = true;
    fine.timers[0].fn();
    assert.equal(fine.sent.length, 0);
});

test("it stops after 10 lines, so a looping page cannot flood the server", () => {
    const b = boot();
    for (let i = 0; i < 30; i++) b.listeners.error.fn({ target: { tagName: "SCRIPT", src: "x.js" } });
    assert.equal(b.sent.length, 10);
});
