/* Backups: a warning bar when the data is not safe, and a popup with the details, the second folder and Back up now. */

import { clientLog } from "../core/client-log.js";
import { $, api, el, formatDate } from "../core/util.js";

let backupStatus = null;

/* The "only on this computer" warning can be hidden with its ✕; the choice is kept in this browser. Real problems (a failed or
   missing backup) can never be hidden. Choosing a second folder forgets the choice, so the warning comes back if the folder
   is ever taken away. */
const HIDE_KEY = "backupWarningHidden";
function warningHidden() {
    try { return localStorage.getItem(HIDE_KEY) === "1"; } catch { return false; }
}
function setWarningHidden(hidden) {
    try { localStorage.setItem(HIDE_KEY, hidden ? "1" : "0"); } catch { /* storage blocked: it just comes back next time */ }
}

export async function loadBackupStatus() {
    backupStatus = await api("/api/backups");
    renderBackup();
}

function renderBackup() {
    const s = backupStatus;
    if (!s) return;
    const banner = $("backupBanner");
    if (s.copyDir) setWarningHidden(false);
    const dismissable = s.level === "WARNING";
    banner.className = "backup-banner" + (s.level === "NONE" || (dismissable && warningHidden()) ? " hidden" : " " + s.level.toLowerCase());
    banner.innerHTML = "";
    banner.append(el("span", { className: "backup-banner-text", textContent: s.banner || "" }));
    if (dismissable) {
        const hide = el("button", { className: "backup-dismiss", type: "button", textContent: "✕", title: "Hide this warning" });
        hide.setAttribute("aria-label", "Hide this warning");
        hide.addEventListener("click", e => {
            e.stopPropagation();   // the bar itself opens the Backups popup
            setWarningHidden(true);
            clientLog("INFO", "backup.warning-hidden");
            renderBackup();
        });
        banner.append(hide);
    }

    const state = $("backupState");
    state.innerHTML = "";
    state.append(
        el("div", { className: s.ok ? "good" : "bad", textContent: s.ok ? "Your data is backed up." : "The last backup failed." }),
        el("div", { textContent: s.lastSuccess ? "Last good backup: " + formatDateTime(s.lastSuccess) : "There is no good backup yet." }),
        el("div", { textContent: s.lastMessage || "" }),
        el("div", { textContent: "Saved in: " + s.directory }),
        el("div", { textContent: s.copyDir ? "Second copy in: " + s.copyDir : "There is no second folder yet." }));
    if (document.activeElement !== $("backupCopyDir")) $("backupCopyDir").value = s.copyDir || "";

    const files = $("backupFiles");
    files.innerHTML = "";
    if (s.files.length === 0) files.append(el("div", { className: "empty-state", textContent: "No backups yet." }));
    for (const f of s.files) {
        files.append(el("div", { className: "backup-row" },
            el("span", { textContent: formatDateTime(f.made) + " · " + f.name }),
            el("span", { className: "size", textContent: Math.max(1, Math.round(f.sizeBytes / 1024)) + " KB" })));
    }
}

/* "12 Oct 2026, 18:30" from an ISO date-time. */
function formatDateTime(iso) {
    return formatDate(iso.slice(0, 10)) + ", " + iso.slice(11, 16);
}

function openBackup() {
    $("backupError").textContent = "";
    $("backupOverlay").classList.remove("hidden");
    renderBackup();
}

function closeBackup() {
    $("backupOverlay").classList.add("hidden");
}

async function backupAction(button, run) {
    $("backupError").textContent = "";
    button.disabled = true;
    try {
        backupStatus = await run();
        renderBackup();
    } catch (e) {
        $("backupError").textContent = e.message;
    } finally {
        button.disabled = false;
    }
}

export function initBackup() {
    $("backupButton").addEventListener("click", openBackup);
    $("backupBanner").addEventListener("click", openBackup);
    $("backupClose").addEventListener("click", closeBackup);
    $("backupDone").addEventListener("click", closeBackup);
    $("backupNow").addEventListener("click", () => backupAction($("backupNow"), () => api("/api/backups", { method: "POST" })));
    $("backupSaveFolder").addEventListener("click", () => backupAction($("backupSaveFolder"),
        () => api("/api/backups/settings", { method: "PUT", body: { copyDir: $("backupCopyDir").value } })));
    setInterval(() => loadBackupStatus().catch(() => {}), 10 * 60 * 1000);   // the bar stays honest if the app is left open
}
