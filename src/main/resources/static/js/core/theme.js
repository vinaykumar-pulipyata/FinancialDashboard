/* Three themes: Light, Dark and Color. This file is loaded in the <head>, before anything is drawn, so the right theme is on
   the page from the first moment (no white flash). The choice is kept in this browser; until one is made the computer's own
   light / dark setting decides (Color is only ever chosen). The colours themselves are CSS variables (css/base.css,
   css/charts.css, css/color-theme.css) under :root[data-theme="dark"] and :root[data-theme="color"]. */

const THEME_KEY = "theme";
const THEMES = ["light", "dark", "color"];

function systemTheme() {
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function savedTheme() {
    try {
        const saved = localStorage.getItem(THEME_KEY);
        return THEMES.includes(saved) ? saved : null;
    } catch (e) {
        return null;   // storage can be blocked; the theme then simply follows the computer
    }
}

function currentTheme() {
    const theme = document.documentElement.dataset.theme;
    return THEMES.includes(theme) ? theme : "light";
}

/* The three buttons in the menu: the one for the theme on show is pressed. */
function paintThemeButtons() {
    for (const theme of THEMES) {
        const button = document.getElementById("theme-" + theme);
        if (button) button.setAttribute("aria-pressed", String(currentTheme() === theme));
    }
}

function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    paintThemeButtons();
}

function chooseTheme(theme) {
    try {
        localStorage.setItem(THEME_KEY, theme);
    } catch (e) {
        // not saved: it lasts until the page is reloaded
    }
    applyTheme(theme);
}

/* Wires the menu buttons, and follows the computer's setting while no choice has been made. */
function initTheme() {
    paintThemeButtons();
    for (const theme of THEMES) {
        document.getElementById("theme-" + theme).addEventListener("click", () => chooseTheme(theme));
    }
    if (window.matchMedia) {
        window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
            if (!savedTheme()) applyTheme(systemTheme());
        });
    }
}

applyTheme(savedTheme() || systemTheme());
