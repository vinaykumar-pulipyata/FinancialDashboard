const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The page's own log: what it sends to the server, what it leaves out, and that it can never break the page. */
function page() {
    const p = createPage();
    p.run(`var sent = [], handlers = {}, failing = false;
        window.addEventListener = (type, fn) => { handlers[type] = fn; };
        function api(path, options) {
            sent.push({ path, method: options.method, body: options.body });
            return failing ? Promise.reject(new Error("down")) : Promise.resolve(null);
        }`);
    return p.load("core/client-log.js");
}

test("clientLog posts the level, the event and the detail to the server's client log", () => {
    const p = page();
    p.run(`clientLog("INFO", "category.added", "kind=EXPENSE id=12")`);
    assert.deepEqual(p.run("sent"), [{ path: "/api/client-log", method: "POST", body: { level: "INFO", event: "category.added", detail: "kind=EXPENSE id=12" } }]);
});

test("a log line that fails to send is ignored, and nothing is thrown", async () => {
    const p = page();
    p.run("failing = true");
    assert.doesNotThrow(() => p.run(`clientLog("WARN", "x.y")`));
    await new Promise(resolve => setTimeout(resolve, 0));   // the rejected promise was caught inside
    assert.equal(p.run("sent.length"), 1);
});

test("a page that loops cannot flood the server: lines stop after the cap", () => {
    const p = page();
    p.run(`for (let i = 0; i < MAX_CLIENT_LOGS + 50; i++) clientLog("DEBUG", "loop")`);
    assert.equal(p.run("sent.length"), p.run("MAX_CLIENT_LOGS"));
});

test("an error says what kind it was and where, but a plain Error (what api() throws with the server's message) loses its message", () => {
    const p = page();
    const plain = p.run(`describeError(Object.assign(new Error("Only 5000 of your earlier loans is left"), { stack: "Error: Only 5000\\n    at saveTransaction (http://127.0.0.1:8080/js/panels/transaction-form.js:310:15)\\n    at async x (http://127.0.0.1:8080/js/app.js:9:1)" }))`);
    assert.ok(!plain.includes("5000"), plain);
    assert.match(plain, /^Error \[at saveTransaction \(js\/panels\/transaction-form\.js:310:15\) \| at async x \(js\/app\.js:9:1\)\]$/);
    const own = p.run(`describeError(Object.assign(new TypeError("Cannot read properties of null (reading 'filter')"), { stack: "x\\n  fill@http://h/js/panels/transaction-form.js:117:24" }))`);
    assert.match(own, /^TypeError: Cannot read properties of null \(reading 'filter'\) \[fill@js\/panels\/transaction-form\.js:117:24\]$/);
});

test("anything thrown that is not an Error is still described", () => {
    const p = page();
    assert.equal(p.run(`describeError("boom")`), "string");
    assert.equal(p.run(`describeError(undefined)`), "undefined");
});

test("script errors and unhandled promise failures are logged at ERROR", () => {
    const p = page();
    p.run(`handlers.error({ error: new RangeError("bad"), filename: "", lineno: 0 })`);
    p.run(`handlers.error({ error: null, filename: "http://127.0.0.1:8080/js/app.js", lineno: 41 })`);
    p.run(`handlers.unhandledrejection({ reason: new Error("secret 1234") })`);
    const lines = p.run("sent").map(s => s.body);
    assert.deepEqual(lines.map(l => [l.level, l.event]), [["ERROR", "script.error"], ["ERROR", "script.error"], ["ERROR", "promise.rejected"]]);
    assert.match(lines[0].detail, /^RangeError: bad/);
    assert.equal(lines[1].detail, "js/app.js:41");
    assert.ok(!lines[2].detail.includes("1234"), "a rejection from api() keeps the server's words out of the log");
});
