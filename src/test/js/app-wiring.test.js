/* app.js decides what is redrawn after every change. A panel whose render function is never called from there simply goes
   stale, and nothing else would notice. */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { STATIC } = require("./harness");

const app = fs.readFileSync(path.join(STATIC, "js", "app.js"), "utf8");
const body = name => {
    const start = app.indexOf(`function ${name}(`);
    assert.ok(start >= 0, `app.js has no ${name}()`);
    return app.slice(start, app.indexOf("\n}\n", start));
};

test("render() redraws the figures, the reminders and everything that follows the date range", () => {
    const render = body("render");
    for (const call of ["renderSummary()", "renderReminders()", "renderPeriod()"]) assert.ok(render.includes(call), `render() must call ${call}`);
});

test("startApp() wires every panel that has an init function", () => {
    const start = body("startApp");
    const inits = ["initSummary", "initWorkspace", "initPeriodFilter", "initCalendar", "initTheme", "initTransactionSearch", "initBackup", "initNotes", "initReminderForm", "initHeaderMenu", "initPassword"];
    for (const init of inits) assert.ok(start.includes(`step(${init})`), `startApp() must run ${init} through step()`);
});

test("loadAllData() (what refreshData() runs) fetches the card due days and the note counts the panels draw from", () => {
    const refresh = body("loadAllData");
    assert.ok(refresh.includes("setCardDueDays("), "the card due days");
    assert.ok(refresh.includes("/api/notes/days"), "the note marks for the calendar");
    assert.ok(refresh.includes("/api/credit-cards"));
    assert.ok(refresh.includes("/api/reminders") && refresh.includes("setUserReminders("), "the user's reminders");
});

test("a start-up failure is logged before anything is painted, and a failing init names itself", () => {
    const start = body("startApp");
    assert.ok(start.includes("window.dashboardStarted = true"), "boot-log.js stops watching once the app has started");
    assert.ok(start.includes("start.ready"));
    assert.ok(app.slice(app.indexOf("startApp().catch")).indexOf("start.failed") < app.slice(app.indexOf("startApp().catch")).indexOf("showDashboard()"),
        "start.failed must be logged first, in case showDashboard() throws too");
    assert.ok(body("step").includes("start.step-failed"));
});

test("a start-up failure is logged before anything is painted, and a failing init names itself", () => {
    const start = body("startApp");
    assert.ok(start.includes("window.dashboardStarted = true"), "boot-log.js stops watching once the app has started");
    assert.ok(start.includes("start.ready"));
    const tail = app.slice(app.indexOf("startApp().catch"));
    assert.ok(tail.indexOf("start.failed") < tail.indexOf("showDashboard()"), "start.failed must be logged first, in case showDashboard() throws too");
    assert.ok(body("step").includes("start.step-failed"));
});

test("app.js hands refreshData() its meaning at start-up, so the panels never import app.js", () => {
    assert.ok(body("startApp").includes("setRefreshHandler(loadAllData)"));
});

test("the budgets are fetched and set with the rest of the data, and the Budgets panel is started", () => {
    assert.ok(body("loadAllData").includes("/api/budgets") && body("loadAllData").includes("setBudgets("), "the budget limits");
    assert.ok(body("startApp").includes("step(initBudgets)"));
    assert.ok(body("render").includes("renderBudgets()"));
});
