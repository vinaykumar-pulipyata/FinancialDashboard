/* Import from Excel (menu > Data > Import from Excel): a page that takes a file made by Export to Excel, checks it without saving anything,
   then loads it into the new, empty database. The server does the work (POST /api/import, the file as the request body); this page sends the
   file, shows how far it has got while it waits, then either opens the Calendar (nothing went wrong) or stays and shows what did. */

import { clientLog, describeError } from "../core/client-log.js";
import { refreshData } from "../core/refresh.js";
import { $, api, el, setBarWidth } from "../core/util.js";
import { showWorkspaceView } from "./workspace.js";

let checkedFile = null;   // the file the last check passed for, so Import loads exactly what was checked

let progressTimer = null;

/* While a check or an import runs: a bar and what it is doing, from the server's own count (GET /api/import/progress, polled twice a second). */
function showProgress(on, verb) {
    clearInterval(progressTimer);
    progressTimer = null;
    $("importProgress").classList.toggle("hidden", !on);
    if (!on) return;
    paintProgress(verb, { doing: "Sending the file", percent: 0 });
    progressTimer = setInterval(async () => {
        try {
            paintProgress(verb, await api("/api/import/progress"));
        } catch (e) {
            // the answer to the import itself will say what went wrong
        }
    }, 500);
}

function paintProgress(verb, p) {
    const percent = Math.max(0, Math.min(100, Number(p.percent) || 0));
    setBarWidth($("importProgressFill"), "import-progress", percent);
    $("importProgressText").textContent = `${verb}: ${p.doing || "working"} (${percent}%)`;
}

/* A clean page: no file chosen, no report. */
export function showImport() {
    showProgress(false);
    $("importFile").value = "";
    checkedFile = null;
    $("importError").textContent = "";
    $("importReport").innerHTML = "";
    $("importCheck").disabled = true;
    $("importApply").disabled = true;
}

/* Sends the file as it is. (api() in util.js sends JSON, so this has its own fetch with the same header.) */
async function uploadWorkbook(file, apply) {
    const res = await fetch("/api/import?apply=" + apply, {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/octet-stream", "X-Requested-With": "FinancialDashboard" },
        body: file
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new Error((data && data.error) || "Something went wrong (" + res.status + ")");
    return data;
}

/* The steps as a table (loaded and skipped), what each said, and what is not loaded yet. */
function drawReport(report) {
    const box = $("importReport");
    box.innerHTML = "";
    box.append(el("div", { className: "import-done", textContent: report.applied ? "Imported." : "Checked: nothing was saved." }));
    const table = el("table", { className: "import-table" },
        el("thead", {}, el("tr", {}, el("th", { textContent: "Step" }), el("th", { className: "num", textContent: "Loaded" }), el("th", { className: "num", textContent: "Skipped" }))));
    const body = el("tbody");
    for (const step of report.steps) {
        body.append(el("tr", {}, el("td", { textContent: step.name }), el("td", { className: "num", textContent: String(step.imported) }),
            el("td", { className: "num", textContent: String(step.skipped) })));
    }
    table.append(body);
    box.append(table);
    for (const step of report.steps) {
        if (!step.messages.length) continue;
        box.append(el("div", { textContent: step.name + ":" }),
            el("ul", { className: "import-messages" }, ...step.messages.map(m => el("li", { textContent: m }))));
    }
    if (report.notLoaded.length) {
        box.append(el("div", { textContent: "Left out of the import: " + report.notLoaded.join(", ") + "." }));
    }
}

async function run(apply) {
    const file = $("importFile").files[0];
    if (!file) return;
    $("importError").textContent = "";
    $("importReport").innerHTML = "";
    $("importCheck").disabled = true;
    $("importApply").disabled = true;
    showProgress(true, apply ? "Importing" : "Checking");
    try {
        const report = await uploadWorkbook(file, apply);
        const problems = report.steps.reduce((n, step) => n + step.messages.length, 0);
        clientLog("INFO", apply ? "import.done" : "import.checked", `steps=${report.steps.length} problems=${problems}`);
        if (apply) {
            checkedFile = null;
            await refreshData();
            if (problems === 0) {
                showProgress(false);
                showWorkspaceView("calendar");   // all loaded: on to the dashboard
                return;
            }
        } else {
            checkedFile = file;
            $("importApply").disabled = false;
        }
        drawReport(report);
        if (apply) offerCalendar(problems);
    } catch (e) {
        $("importError").textContent = e.message;   // what went wrong stays on this page
        clientLog("WARN", "import.failed", describeError(e));
    } finally {
        showProgress(false);
        $("importCheck").disabled = !$("importFile").files[0];
    }
}

/* An import that loaded with problems stays here, so they can be read; the calendar is one click away. */
function offerCalendar(problems) {
    const open = el("button", { className: "btn btn-primary import-open", type: "button", textContent: "Open the calendar" });
    open.addEventListener("click", () => showWorkspaceView("calendar"));
    $("importReport").prepend(el("div", { className: "import-error", textContent: `Imported, but ${problems} ${problems === 1 ? "row needs" : "rows need"} a look: the lines below say which.` }), open);
}

export function initImport() {
    $("importFile").addEventListener("change", () => {
        checkedFile = null;
        $("importReport").innerHTML = "";
        $("importError").textContent = "";
        $("importCheck").disabled = !$("importFile").files[0];
        $("importApply").disabled = true;
    });
    $("importCheck").addEventListener("click", () => run(false));
    $("importApply").addEventListener("click", () => {
        if (checkedFile && checkedFile === $("importFile").files[0] && confirm("Load this file into the database now?")) run(true);
    });
}
