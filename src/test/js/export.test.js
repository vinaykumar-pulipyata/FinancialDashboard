const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Export to Excel asks for the password and sends it with the request; nothing is downloaded without it. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/export.js");
    p.run(`var fetched = [], saved = [], downloads = 0, reply = { ok: true, status: 200, headers: { get: () => 'attachment; filename="financial-dashboard-2026-11-20.xlsx"' }, blob: async () => ({ size: 3 }), json: async () => null };
        var dlg = { onPrimary: null }; var errors = []; function ledgerFail(m) { errors.push(m); }
        function openLedgerDialog(title, label, build) { saved.push(title + "|" + label); build($("ledgerBody")); }
        function field(label, input) { return el("div", {}, input); } function clientLog() {} function describeError() { return ""; }
        var URL = { createObjectURL: () => "blob:x", revokeObjectURL() {} };
        async function fetch(url, options) { fetched.push({ url, method: options.method, client: options.headers["X-Requested-With"], body: options.body }); return reply; }`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("the file name comes from the server's header, or a plain one", () => {
    const p = page();
    assert.equal(p.run(`exportFileName('attachment; filename="financial-dashboard-2026-11-20.xlsx"')`), "financial-dashboard-2026-11-20.xlsx");
    assert.equal(p.run(`exportFileName(null)`), "financial-dashboard.xlsx");
});

test("pressing Export opens a password dialog and sends nothing yet", () => {
    const p = page();
    p.run("initExport()");
    p.node("exportButton").fire("click");
    assert.deepEqual(p.run("saved"), ["Export to Excel|Export"]);
    assert.deepEqual(p.run("fetched"), []);
});

test("with no password typed it asks for one; with one it posts it to the server", async () => {
    const p = page();
    p.run("askToExport(); dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("errors"), ["Type your password."]);
    assert.deepEqual(p.run("fetched"), []);
    p.node("ledgerBody").find(n => n.tag === "input")[0].value = "my-password";
    p.run("dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("fetched.map(f => [f.url, f.method, f.client, f.body])"), [["/api/export", "POST", "FinancialDashboard", '{"password":"my-password"}']]);
});

test("a refused password is shown in the dialog, which stays open", async () => {
    const p = page();
    p.run(`reply = { ok: false, status: 400, headers: { get: () => null }, json: async () => ({ error: "That password is not right" }) }; askToExport()`);
    p.node("ledgerBody").find(n => n.tag === "input")[0].value = "wrong";
    p.run("dlg.onPrimary()");
    await flush();
    assert.deepEqual(p.run("errors"), ["That password is not right"]);
    assert.equal(p.node("ledgerPrimary").disabled, false, "it can be tried again");
});
