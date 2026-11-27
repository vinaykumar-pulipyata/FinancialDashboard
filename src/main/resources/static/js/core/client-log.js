/* The page's own log. Script errors and the steps of a few flows are sent to the server (POST /api/client-log), which
   writes them to ./logs/general/<year-month>/<date>.<n>.log under "client", next to the server's own lines, so a problem that only
   happens in the browser can be read there. Send only codes, ids, counts and function names: never amounts, notes,
   category names or search text. clientLog never throws, and stops after MAX_CLIENT_LOGS lines per page load (the
   server also drops anything past 120 a minute). Levels: ERROR, WARN, INFO and DEBUG (DEBUG shows when the server's
   logging.level.client=DEBUG). */

import { api } from "./util.js";

const MAX_CLIENT_LOGS = 200;
let clientLogsSent = 0;

export function clientLog(level, event, detail = "") {
    if (clientLogsSent >= MAX_CLIENT_LOGS) return;
    clientLogsSent++;
    try {
        api("/api/client-log", { method: "POST", body: { level, event, detail: String(detail) } }).catch(() => {});   // a failed log line is not worth a message
    } catch (e) { /* logging must never break the page */ }
}

/* What may be logged about an error: its kind, where it happened (function and line, never data), and its message only
   for the browser's own errors (TypeError and the like). A plain Error is what api() throws with the server's message,
   and that can hold an amount. */
export function describeError(error) {
    const name = error && error.name ? String(error.name) : typeof error;
    const own = /^(Type|Reference|Range|Syntax|Eval|URI)Error$/.test(name);
    const where = String(error && error.stack || "").split("\n").filter(line => /^\s*at |@/.test(line))
        .slice(0, 4).map(line => line.trim().replace(/https?:\/\/[^/\s)]+\//g, "")).join(" | ");
    return name + (own && error.message ? ": " + String(error.message).slice(0, 200) : "") + (where ? ` [${where}]` : "");
}

/* Errors nothing caught, including a promise nobody waited for. */
window.addEventListener("error", e => clientLog("ERROR", "script.error",
    e.error ? describeError(e.error) : `${e.filename ? String(e.filename).replace(/^https?:\/\/[^/]+\//, "") : "?"}:${e.lineno || 0}`));
window.addEventListener("unhandledrejection", e => clientLog("ERROR", "promise.rejected", describeError(e.reason)));
