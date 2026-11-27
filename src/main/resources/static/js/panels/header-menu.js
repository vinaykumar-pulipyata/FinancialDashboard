/* The side menu is a collapsible sidebar: the button at its top folds it to a rail of icons or opens it again with the labels. The
   choice is kept in this browser; until one is made it starts open, or folded on a narrow window. Each action keeps its own id and
   handler (theme.js, app.js, the lock and password scripts); this file only folds and opens the bar, by setting data-sidebar on <html>
   (css/header-menu.css makes the room). Choosing an item does not fold it. The items are nested under their group (Organiser, Categories,
   Themes, Data, Account), and a group's items appear beside it when it is pointed at. */

import { $ } from "../core/util.js";

const SIDEBAR_KEY = "sidebar";
const NARROW_PX = 900;   // below this the sidebar starts folded

function savedSidebar() {
    try {
        const saved = localStorage.getItem(SIDEBAR_KEY);
        return saved === "open" || saved === "collapsed" ? saved : null;
    } catch (e) {
        return null;   // storage can be blocked; the bar then simply starts by the window's width
    }
}

/* A group's name opens a flyout with its items when the pointer is on it or the keyboard is in it (the CSS does that). Clicking the
   name pins the flyout open, which is how a touch screen opens it; clicking elsewhere, or Escape, closes the pinned one. */
function initMenuGroups() {
    const toggles = [...document.querySelectorAll(".menu-group-toggle")];
    const flyouts = toggles.map(toggle => $(toggle.getAttribute("aria-controls")));
    const unpinAll = () => {
        toggles.forEach((toggle, i) => { flyouts[i].classList.remove("pinned"); toggle.setAttribute("aria-expanded", "false"); });
    };
    toggles.forEach((toggle, i) => {
        toggle.addEventListener("click", e => {
            e.stopPropagation();
            const open = !flyouts[i].classList.contains("pinned");
            unpinAll();
            flyouts[i].classList.toggle("pinned", open);
            toggle.setAttribute("aria-expanded", String(open));
        });
    });
    document.addEventListener("click", e => { if (!(e.target.closest && e.target.closest(".menu-sub"))) unpinAll(); });
    // Choosing an item closes its flyout at once: the pointer is still over it and the button keeps the focus, so without this it
    // would stay open until the next click elsewhere. It comes back the next time the group is pointed at.
    for (const group of document.querySelectorAll(".menu-group")) {
        group.addEventListener("click", e => {
            const item = e.target.closest && e.target.closest(".menu-sub .side-item");
            if (!item) return;
            unpinAll();
            if (item.blur) item.blur();
            group.classList.add("dismissed");
        });
        group.addEventListener("pointerleave", () => group.classList.remove("dismissed"));
    }
    document.addEventListener("keydown", e => { if (e.key === "Escape") unpinAll(); });
}

export function initHeaderMenu() {
    initMenuGroups();
    const button = $("menuButton");
    const setCollapsed = (collapsed, remember) => {
        document.documentElement.dataset.sidebar = collapsed ? "collapsed" : "open";
        button.setAttribute("aria-expanded", String(!collapsed));
        button.setAttribute("aria-label", collapsed ? "Expand menu" : "Collapse menu");
        button.title = collapsed ? "Expand menu" : "Collapse menu";
        if (!remember) return;
        try {
            localStorage.setItem(SIDEBAR_KEY, collapsed ? "collapsed" : "open");
        } catch (e) {
            // not saved: it lasts until the page is reloaded
        }
    };
    const saved = savedSidebar();
    setCollapsed(saved ? saved === "collapsed" : window.innerWidth < NARROW_PX, false);
    button.addEventListener("click", () => setCollapsed(document.documentElement.dataset.sidebar !== "collapsed", true));
}
