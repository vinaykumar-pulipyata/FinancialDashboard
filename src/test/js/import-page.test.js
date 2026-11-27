const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Menu > Data > Import from Excel: choose a file, check it, then import it. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/import.js");
    p.run(`var fetched = [], refreshed = 0, answer = { applied: false, steps: [{ name: "Accounts", imported: 3, skipped: 1, messages: ["Row 4: The category Food is missing"] }], notLoaded: ["Ledger", "Shares"] };
        function clientLog() {} function describeError() { return ""; } async function refreshData() { refreshed++; } function confirm() { return true; }
        var shown = [], progressAnswer = { running: true, doing: "Transactions 40 of 80", percent: 30 }, failWith = null;
        function showWorkspaceView(v) { shown.push(v); }
        async function fetch(url, options) { if (url === "/api/import/progress") return { ok: true, status: 200, json: async () => progressAnswer };
            fetched.push({ url, method: options.method, type: options.headers["Content-Type"], client: options.headers["X-Requested-With"], body: options.body });
            if (failWith) return { ok: false, status: 400, json: async () => ({ error: failWith }) };
            return { ok: true, status: 200, json: async () => answer }; }`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("the report lists each step with what loaded and what was skipped, then what is left out", () => {
    const p = page();
    p.run(`drawReport(answer)`);
    const text = p.node("importReport").allText;
    assert.match(text, /Checked: nothing was saved/);
    assert.match(text, /Accounts31/);
    assert.match(text, /Row 4: The category Food is missing/);
    assert.match(text, /Left out of the import.*Ledger, Shares/);
    p.run(`drawReport({ ...answer, applied: true })`);
    assert.match(p.node("importReport").allText, /^Imported\./);
});

test("Check sends the file as the request body with the dashboard header and keeps nothing; Import comes after a check", async () => {
    const p = page();
    p.node("importFile").files = [{ name: "export.xlsx" }];
    p.run("initImport(); showImport()");
    assert.equal(p.node("importCheck").disabled, true, "nothing chosen yet");
    p.node("importFile").files = [{ name: "export.xlsx" }];
    p.node("importFile").fire("change");
    assert.equal(p.node("importCheck").disabled, false);
    assert.equal(p.node("importApply").disabled, true, "Import waits for a check");
    p.node("importCheck").fire("click");
    await flush();
    assert.deepEqual(p.run("fetched.map(f => [f.url, f.method, f.type, f.client])"), [["/api/import?apply=false", "POST", "application/octet-stream", "FinancialDashboard"]]);
    assert.equal(p.node("importApply").disabled, false);
    assert.equal(p.run("refreshed"), 0, "a check changes nothing, so nothing is reloaded");
    p.run("answer = { ...answer, applied: true, steps: [{ name: 'Accounts', imported: 3, skipped: 0, messages: [] }] }");
    p.node("importApply").fire("click");
    await flush();
    assert.equal(p.run("fetched[1].url"), "/api/import?apply=true");
    assert.equal(p.run("refreshed"), 1, "after the import the dashboard reloads");
    assert.deepEqual(p.run("shown"), ["calendar"], "all loaded: the Calendar opens");
});

test("an import with problems stays on the page, says so, and offers the Calendar", async () => {
    const p = page();
    p.run("initImport(); answer = { ...answer, applied: true }");
    p.node("importFile").files = [{ name: "export.xlsx" }];
    p.node("importFile").fire("change");
    p.node("importCheck").fire("click");
    await flush();
    p.node("importApply").fire("click");
    await flush();
    assert.deepEqual(p.run("shown"), [], "it does not leave the page");
    assert.match(p.node("importReport").allText, /Imported, but 1 row needs a look/);
    assert.match(p.node("importReport").allText, /Open the calendar/);
    p.node("importReport").find(n => n.textContent === "Open the calendar")[0].fire("click");
    assert.deepEqual(p.run("shown"), ["calendar"]);
});

test("an error from the server is shown on the import page, and the bar is put away", async () => {
    const p = page();
    p.run("initImport(); failWith = 'Import only works on an empty database'");
    p.node("importFile").files = [{ name: "export.xlsx" }];
    p.node("importFile").fire("change");
    p.node("importCheck").fire("click");
    await flush();
    assert.match(p.node("importError").textContent, /empty database/);
    assert.equal(p.node("importProgress").classList.contains("hidden"), true);
    assert.equal(p.node("importCheck").disabled, false, "it can be tried again");
});

test("the bar shows what the server is doing and how far it has got", () => {
    const p = page();
    p.run(`paintProgress("Checking", progressAnswer)`);
    assert.equal(p.node("importProgressText").textContent, "Checking: Transactions 40 of 80 (30%)");
    assert.equal(p.node("importProgressFill").style.width, "30%");
    p.run(`paintProgress("Importing", { percent: 250 })`);
    assert.equal(p.node("importProgressFill").style.width, "100%", "never past the end");
});
