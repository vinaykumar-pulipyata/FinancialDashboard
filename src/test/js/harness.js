/* A tiny stand-in for the browser, so the dashboard's scripts can be loaded and driven from tests. The files are ES modules,
   but the tests reach into them by name (page.run("statsRange()")), so the page loads each file as a classic script in strict
   mode (what a module is) after `toClassicScript` has taken out its import and export keywords. Every file therefore shares
   one scope in a test page, which is why the names must stay unique across files. module-graph.test.js loads the real modules.
   Each createPage() is a fresh, isolated page.

   page.load("core/util.js", "panels/stats-period.js")   loads scripts, in that order, into the page
   page.run("statsRange()")                              runs code in the page; returns plain data (objects as JSON copies)
   page.node(id)                                         the fake element with that id
*/
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const STATIC = path.join(__dirname, "..", "..", "main", "resources", "static");

/** The source of a module as a classic script: imports removed and `export` dropped, with line numbers kept. */
function toClassicScript(source) {
    return source
        .replace(/^import\s[^;]*?from\s+"[^"]+";[ \t]*$/gm, m => m.replace(/[^\n]/g, ""))
        .replace(/^import\s+"[^"]+";[ \t]*$/gm, "")
        .replace(/^export\s+(?=(?:async\s+function|function|const|let|var|class)\b)/gm, "");
}

class FakeNode {
    constructor(tag, id) {
        this.tag = tag;
        this.id = id;
        this.children = [];
        this.attrs = {};
        this.listeners = {};
        this.dataset = {};
        this.style = {};
        this._classes = new Set();
        this.classList = {
            add: (...c) => c.forEach(x => this._classes.add(x)),
            remove: (...c) => c.forEach(x => this._classes.delete(x)),
            toggle: (c, on) => { const want = on === undefined ? !this._classes.has(c) : on; want ? this._classes.add(c) : this._classes.delete(c); return want; },
            contains: c => this._classes.has(c),
        };
    }
    get className() { return [...this._classes].join(" "); }
    set className(v) { this._classes = new Set(String(v).split(/\s+/).filter(Boolean)); }
    /** Like the DOM, adding an element that is already somewhere else moves it. */
    adopt(c) {
        c.forEach(child => {
            if (typeof child === "string") return;
            if (child.parent) child.parent.children = child.parent.children.filter(x => x !== child);
            child.parent = this;
        });
    }
    append(...c) { this.adopt(c); this.children.push(...c); }
    prepend(...c) { this.adopt(c); this.children.unshift(...c); }
    replaceChildren(...c) { this.children = []; this.append(...c); }
    setAttribute(k, v) { this.attrs[k] = String(v); }
    getAttribute(k) { return this.attrs[k]; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    /** Fires the listeners of an event, like a click. */
    fire(type, event = {}) { (this.listeners[type] || []).forEach(fn => fn({ target: this, preventDefault() {}, stopPropagation() {}, ...event })); }
    /** Descendants matching a simple selector: ".class" or "[data-x]". */
    querySelectorAll(selector) { return this.find(n => n.matches(selector)); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
    matches(selector) {
        if (selector.startsWith(".")) return this._classes.has(selector.slice(1));
        const attr = selector.match(/^\[data-([a-z-]+)\]$/);
        return attr ? Object.keys(this.dataset).includes(attr[1].replace(/-(\w)/g, (_, c) => c.toUpperCase())) : false;
    }
    closest(selector) { return this.matches(selector) ? this : null; }
    focus() {}
    select() {}
    get firstElementChild() { return this.children.find(c => typeof c !== "string") ?? null; }
    set innerHTML(v) { this.children = []; }
    get innerHTML() { return ""; }
    /** All the text under this element, as it would read on the page. */
    get allText() { return (this.textContent || "") + this.children.map(c => typeof c === "string" ? c : c.allText).join(""); }
    /** Descendants (depth first) that match. */
    find(predicate) {
        const found = [];
        const walk = n => { if (typeof n === "string") return; if (predicate(n)) found.push(n); n.children.forEach(walk); };
        this.children.forEach(walk);
        return found;
    }
}

function createPage({ fetch, darkSystem = false, savedTheme = null, storageBlocked = false } = {}) {
    const nodes = new Map();
    const node = id => { if (!nodes.has(id)) nodes.set(id, new FakeNode("div", id)); return nodes.get(id); };
    const created = [];                    // every element a script built, so querySelectorAll(".class") can find them
    const bySelector = new Map();          // document.querySelector("...") gives the same element every time
    const make = tag => { const n = new FakeNode(tag); created.push(n); return n; };
    const document = {
        createElement: make,
        createElementNS: (ns, tag) => make(tag),
        getElementById: node,
        querySelector: sel => { if (!bySelector.has(sel)) bySelector.set(sel, new FakeNode("div")); return bySelector.get(sel); },
        querySelectorAll: sel => [...nodes.values(), ...created].filter(n => n.matches(sel)),
        addEventListener() {},
        activeElement: null,
        documentElement: new FakeNode("html"),
        body: new FakeNode("body"),
    };
    const storage = new Map();
    if (savedTheme) storage.set("theme", savedTheme);
    const mediaListeners = [];
    const sandbox = {
        document, console, fetch, URLSearchParams, Intl, JSON, Math, Date, Number, String, Array, Object, Map, Set, RegExp, Promise,
        setTimeout, clearTimeout, setInterval: () => 0, clearInterval() {},
        window: {
            addEventListener() {},
            /** The computer's colour setting: darkSystem says what it is, and page.changeSystemTheme() changes it. */
            matchMedia: query => ({ matches: query.includes("dark") && sandbox.__dark, addEventListener: (type, fn) => mediaListeners.push(fn) }),
        },
        __dark: darkSystem,
        localStorage: {
            getItem: k => { if (storageBlocked) throw new Error("blocked"); return storage.get(k) ?? null; },
            setItem: (k, v) => { if (storageBlocked) throw new Error("blocked"); storage.set(k, String(v)); },
        },
        location: { href: "", reload() {} },
        sessionStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, String(v)), removeItem: k => storage.delete(k) },
        alert() {}, confirm: () => true,
    };
    const context = vm.createContext(sandbox);
    const page = {
        context,
        node,
        changeSystemTheme(dark) { sandbox.__dark = dark; mediaListeners.forEach(fn => fn()); },
        stored: key => storage.get(key) ?? null,
        select: sel => document.querySelector(sel),
        load(...files) {
            for (const file of files) vm.runInContext('"use strict";' + toClassicScript(fs.readFileSync(path.join(STATIC, "js", file), "utf8")), context, { filename: file });
            return page;
        },
        /** Runs code in the page and returns the result untouched (use this for promises). */
        raw: code => vm.runInContext(code, context),
        /** Runs code in the page. Objects and arrays come back as plain copies, so deepStrictEqual works on them. */
        run(code) {
            const result = vm.runInContext(code, context);
            return result !== null && typeof result === "object" ? JSON.parse(JSON.stringify(result)) : result;
        },
    };
    return page;
}

module.exports = { createPage, FakeNode, STATIC, toClassicScript };
