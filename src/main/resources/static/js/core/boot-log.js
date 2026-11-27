/* The log for the moment before the app exists. If a module cannot be loaded or linked (a 404, a syntax error, an import
   the other file does not export) none of the modules run, so client-log.js cannot report it. This plain script, loaded
   in the head, reports it instead: an error or a failed script before app.js starts, and a warning if app.js has not
   started 10 seconds after the page opened. From then on client-log.js takes over. Only the browser's own words and
   file names are sent, never page data. */
(function () {
    const MAX_LINES = 10;
    let sent = 0;

    function bootLog(event, detail) {
        if (sent >= MAX_LINES) return;
        sent++;
        try {
            fetch("/api/client-log", {
                method: "POST",
                headers: { "Content-Type": "application/json", "X-Requested-With": "FinancialDashboard" },
                body: JSON.stringify({ level: "ERROR", event, detail: String(detail).slice(0, 400) }),
            }).catch(() => {});
        } catch (e) { /* logging must never break the page */ }
    }

    const file = url => String(url || "?").replace(/^https?:\/\/[^/]+\//, "");

    window.addEventListener("error", e => {
        if (window.dashboardStarted) return;
        const target = e.target;
        if (target && target.tagName === "SCRIPT") bootLog("boot.script-failed", file(target.src));
        else bootLog("boot.error", `${String(e.message || (e.error && e.error.name) || "error").slice(0, 200)} [${file(e.filename)}:${e.lineno || 0}:${e.colno || 0}]`);
    }, true);   // capture: a script that fails to load fires its error on the script element, which does not bubble

    setTimeout(() => {
        if (!window.dashboardStarted) bootLog("boot.stalled", "app.js did not start within 10 s; look for boot.error or boot.script-failed just before this line");
    }, 10000);
})();
