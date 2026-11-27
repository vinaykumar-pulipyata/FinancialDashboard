/* Export to Excel (menu > Data): asks for the password first, so a person who only has the open dashboard cannot download everything. The
   password goes to POST /api/export, which checks it and answers with the workbook; the page turns the answer into a download. */

import { clientLog, describeError } from "../core/client-log.js";
import { $, el } from "../core/util.js";
import { dlg, ledgerFail, openLedgerDialog } from "./ledger.js";
import { field } from "./transaction-form.js";

/* The file name the server chose (financial-dashboard-YYYY-MM-DD.xlsx), or a plain one. */
function exportFileName(disposition) {
    const found = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition || "");
    return found ? decodeURIComponent(found[1]) : "financial-dashboard.xlsx";
}

/* Asks for the password, fetches the workbook and saves it. A refusal (a wrong password, a lock-out) is shown in the dialog. */
async function exportWorkbook(password) {
    const res = await fetch("/api/export", {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Requested-With": "FinancialDashboard" },
        body: JSON.stringify({ password })
    });
    if (!res.ok) {
        const data = await res.json().catch(() => null);
        throw new Error((data && data.error) || "Something went wrong (" + res.status + ")");
    }
    const blob = await res.blob();
    const link = el("a", { href: URL.createObjectURL(blob), download: exportFileName(res.headers.get("Content-Disposition")) });
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
}

function askToExport() {
    const f = {};
    openLedgerDialog("Export to Excel", "Export", body => {
        f.password = el("input", { type: "password", value: "", autocomplete: "current-password" });
        body.append(el("div", { className: "edit-hint", textContent: "The file holds all your data, including your diary, so it asks for your password again. " +
            "Keep the downloaded file private." }), field("Password", f.password));
        f.password.addEventListener("keydown", e => { if (e.key === "Enter") dlg.onPrimary(); });
        f.password.focus();
    });
    dlg.onPrimary = async () => {
        if (!f.password.value) return ledgerFail("Type your password.");
        $("ledgerPrimary").disabled = true;
        try {
            await exportWorkbook(f.password.value);
            $("ledgerOverlay").classList.add("hidden");
            clientLog("INFO", "export.done", "");
        } catch (e) {
            ledgerFail(e.message);   // shown in the dialog, which stays open for another try
            clientLog("WARN", "export.refused", describeError(e));
        } finally {
            $("ledgerPrimary").disabled = false;
        }
    };
}

export function initExport() {
    $("exportButton").addEventListener("click", askToExport);
}
