/* The dashboard password. First run: a dialog that cannot be closed asks for one. Later: "Change password" in the
   header. The browser's own sign-in prompt does the logging in (HTTP Basic), so there is no login page here. */

import { $, api } from "../core/util.js";

let passwordMode = "change";   // "set" on first run, "change" afterwards
let passwordDone = null;       // resolves when the first password is saved

function openPassword(mode) {
    passwordMode = mode;
    const first = mode === "set";
    $("passwordTitle").textContent = first ? "Set a password" : "Change password";
    $("passwordHint").textContent = first
        ? "This protects your dashboard. The browser will then ask you to sign in as admin with this password. If you ever forget it, start the app once with --reset-password."
        : "After saving, the browser will ask you to sign in again as admin with the new password.";
    $("passwordCurrentField").classList.toggle("hidden", first);
    $("passwordClose").classList.toggle("hidden", first);
    $("passwordCancel").classList.toggle("hidden", first);
    for (const id of ["passwordCurrent", "passwordNew", "passwordRepeat"]) $(id).value = "";
    $("passwordError").textContent = "";
    $("passwordSave").disabled = false;
    $("passwordOverlay").classList.remove("hidden");
    (first ? $("passwordNew") : $("passwordCurrent")).focus();
}

function closePassword() {
    if (passwordMode === "set") return;   // the first password cannot be skipped
    $("passwordOverlay").classList.add("hidden");
}

/* Resolves only once the first password is saved. */
export function askForFirstPassword() {
    openPassword("set");
    return new Promise(resolve => { passwordDone = resolve; });
}

async function savePassword() {
    const error = $("passwordError");
    const password = $("passwordNew").value;
    if (password.length < 8) { error.textContent = "Use at least 8 characters."; return; }
    if (password !== $("passwordRepeat").value) { error.textContent = "The two passwords do not match."; return; }
    $("passwordSave").disabled = true;
    try {
        if (passwordMode === "set") {
            await api("/api/auth/password", { method: "POST", body: { password } });
            rememberUnlocked(true);   // they have just typed it: the page after the reload does not ask again
            if (passwordDone) passwordDone();
        } else {
            await api("/api/auth/password", { method: "PUT", body: { currentPassword: $("passwordCurrent").value, newPassword: password } });
            rememberUnlocked(true);
        }
        // The password now exists, so reload: the browser asks to sign in and the page starts again.
        location.reload();
    } catch (e) {
        error.textContent = e.message;
        $("passwordSave").disabled = false;
    }
}

/* Screen lock. The page opens locked, and locks again from the header button and after 15 minutes without a click, key
   press or scroll. Typing the password unlocks it. It only covers the screen; the browser stays signed in.
   A refresh of a tab that is unlocked stays unlocked (the tab remembers, see rememberUnlocked); a new tab, a restarted
   browser, or a tab that was locked all open locked. */
const IDLE_LOCK_MS = 15 * 60 * 1000;
const UNLOCKED_KEY = "screenUnlocked";
let screenLockEnabled = false;   // false until a password exists (nothing to unlock with before that)
let lastActivity = Date.now();

/* Whether this tab was unlocked when the page was last used. sessionStorage lives as long as the tab. */
function rememberUnlocked(on) {
    try { if (on) sessionStorage.setItem(UNLOCKED_KEY, "1"); else sessionStorage.removeItem(UNLOCKED_KEY); }
    catch (e) { /* storage blocked: the page simply opens locked every time */ }
}

function wasUnlocked() {
    try { return sessionStorage.getItem(UNLOCKED_KEY) === "1"; } catch (e) { return false; }
}

function isScreenLocked() {
    return !$("lockOverlay").classList.contains("hidden");
}

function lockScreen() {
    if (!screenLockEnabled || isScreenLocked()) return;
    rememberUnlocked(false);
    $("lockPassword").value = "";
    $("lockError").textContent = "";
    $("lockOverlay").classList.remove("hidden");
    $("lockPassword").focus();
}

async function unlockScreen() {
    $("lockUnlock").disabled = true;
    try {
        await api("/api/auth/verify", { method: "POST", body: { password: $("lockPassword").value } });
        $("lockOverlay").classList.add("hidden");
        $("lockPassword").value = "";
        rememberUnlocked(true);
        lastActivity = Date.now();
    } catch (e) {
        $("lockError").textContent = e.message;
        $("lockPassword").select();
    } finally {
        $("lockUnlock").disabled = false;
    }
}

/* Called once the status is known and a password exists: the page opens locked unless this tab was already unlocked. */
export function startScreenLock() {
    screenLockEnabled = true;
    lastActivity = Date.now();
    if (!wasUnlocked()) lockScreen();
}

export function initPassword() {
    $("lockButton").addEventListener("click", lockScreen);
    for (const type of ["mousedown", "mousemove", "keydown", "scroll", "touchstart"]) {
        document.addEventListener(type, () => { lastActivity = Date.now(); }, { passive: true, capture: true });
    }
    setInterval(() => { if (Date.now() - lastActivity >= IDLE_LOCK_MS) lockScreen(); }, 30 * 1000);
    $("lockUnlock").addEventListener("click", unlockScreen);
    $("lockPassword").addEventListener("keydown", e => { if (e.key === "Enter") unlockScreen(); });

    $("passwordSave").addEventListener("click", savePassword);
    $("passwordCancel").addEventListener("click", closePassword);
    $("passwordClose").addEventListener("click", closePassword);
    $("passwordRepeat").addEventListener("keydown", e => { if (e.key === "Enter") savePassword(); });
    $("changePasswordButton").addEventListener("click", () => openPassword("change"));
}
