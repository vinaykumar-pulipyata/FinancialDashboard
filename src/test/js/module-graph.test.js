/* The scripts are ES modules: app.js imports every panel, and each panel imports what it uses. These checks catch a missing
   import or export before a browser does (a name used but never imported is only a ReferenceError when that code runs).
   The last test loads the real module graph in Node, with a stand-in for the browser. */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { STATIC } = require("./harness");

const list = dir => fs.readdirSync(path.join(STATIC, dir), { withFileTypes: true }).flatMap(e =>
    e.isDirectory() ? list(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const PLAIN_SCRIPTS = new Set(["js/core/theme.js", "js/core/boot-log.js"]);   // loaded by a plain <script> in the head, not a module
const files = list("js").map(f => f.split(path.sep).join("/")).filter(f => !PLAIN_SCRIPTS.has(f));
const read = f => fs.readFileSync(path.join(STATIC, f), "utf8");
const esc = n => n.replace(/\$/g, "\\$&");
const blank = m => m.replace(/[^\n]/g, " ");
/** Source with comments and the insides of string literals blanked out, so names are only found where they are used as code. */
const code = f => read(f).replace(/\/\*[\s\S]*?\*\//g, blank).replace(/(^|[^:"'`\\])\/\/.*$/gm, (m, a) => a + blank(m.slice(a.length)))
    .replace(/"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/g, m => '"' + blank(m.slice(2)) + '"')
    .replace(/`(?:[^`\\]|\\.)*`/g, m => m.split(/(\$\{[^}]*\})/).map((p, i) => (i % 2 ? p : blank(p))).join(""));   // template text, not its ${code}

/** True when the code declares `n` itself: a variable, a function or a destructured name; with `params`, also a parameter
 *  (which also matches a bare argument in a call, so it is only for deciding whether a use could be a local). */
const declaresLocally = (body, n, params = true) => new RegExp(
    `(?:const|let|var|function)\\s+${esc(n)}(?![\\w$])|(?:const|let|var)\\s*[\\[{][^\\]}=]*(?<![\\w$.])${esc(n)}(?![\\w$])[^\\]}=]*[\\]}]\\s*=`
    + (params ? `|[(,]\\s*${esc(n)}\\s*[,)=]` : "")).test(body);

const parse = f => {
    const text = read(f);
    const imports = [];
    for (const m of text.matchAll(/^import\s+(?:\{([^}]*)\}\s+from\s+)?"([^"]+)";/gm)) {
        const target = path.posix.normalize(path.posix.join(path.posix.dirname(f), m[2]));
        imports.push({ target, names: (m[1] || "").split(",").map(n => n.trim()).filter(Boolean) });
    }
    const exports = new Set();
    for (const m of text.matchAll(/^export\s+(?:async\s+)?(?:function|class|const|let|var)\s+([\w$]+)/gm)) exports.add(m[1]);
    for (const m of text.matchAll(/^export const ([\w$]+ = \d+(?:, [\w$]+ = \d+)+);/gm)) for (const n of m[1].matchAll(/([\w$]+) = \d+/g)) exports.add(n[1]);
    return { imports, exports };
};
const modules = new Map(files.map(f => [f, parse(f)]));

test("every import points at a file that exists and names something that file exports", () => {
    const problems = [];
    for (const [f, { imports }] of modules) for (const { target, names } of imports) {
        if (!modules.has(target)) { problems.push(`${f} imports ${target}, which is not a module`); continue; }
        for (const n of names) if (!modules.get(target).exports.has(n)) problems.push(`${f} imports ${n} from ${target}, which does not export it`);
    }
    assert.deepEqual(problems, []);
});

test("every file is reached from app.js, and nothing imported goes unused", () => {
    const seen = new Set();
    const visit = f => { if (!seen.has(f)) { seen.add(f); modules.get(f).imports.forEach(i => visit(i.target)); } };
    visit("js/app.js");
    assert.deepEqual(files.filter(f => !seen.has(f)), [], "files nothing imports are never loaded");
    const unused = [];
    for (const [f, { imports }] of modules) {
        const body = code(f).replace(/^import[\s\S]*?;$/gm, m => blank(m));
        for (const { names } of imports) for (const n of names) if (!new RegExp(`(?<![\\w$.])${esc(n)}(?![\\w$])`).test(body)) unused.push(`${f}: ${n}`);
    }
    assert.deepEqual(unused, []);
});

test("nothing imports app.js (it is the entry point), so the panels do not depend on the file that imports them", () => {
    const importers = [...modules].filter(([, { imports }]) => imports.some(i => i.target === "js/app.js")).map(([f]) => f);
    assert.deepEqual(importers, []);
});

test("an imported name is not also declared inside the file (the import would be unused and the local hides it)", () => {
    const shadowed = [];
    for (const [f, { imports }] of modules) {
        const body = code(f).replace(/^import[\s\S]*?;$/gm, blank);
        for (const { names } of imports) for (const n of names) {
            if (declaresLocally(body, n, false)) shadowed.push(`${f}: ${n}`);
        }
    }
    assert.deepEqual(shadowed, []);
});

test("a name another file exports is never used without an import", () => {
    const exportedBy = new Map();
    for (const [f, { exports }] of modules) for (const n of exports) exportedBy.set(n, f);
    const missing = [];
    for (const [f, { imports }] of modules) {
        const body = code(f).replace(/^import[\s\S]*?;$/gm, blank);
        const imported = new Set(imports.flatMap(i => i.names));
        for (const [n, owner] of exportedBy) {
            if (owner === f || imported.has(n)) continue;
            // (?!\{) so the "${" that opens a template placeholder is not taken for a call to $
            if (new RegExp(`(?<![\\w$.])${esc(n)}(?![\\w$])(?!\\s*:)(?!\\{)`).test(body) && !declaresLocally(body, n))
                missing.push(`${f} uses ${n} (from ${owner}) without importing it`);
        }
    }
    assert.deepEqual(missing, []);
});

test("the real module graph links and evaluates in Node (strict mode, live bindings, no load-order slip)", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dashboard-modules-"));
    try {
        fs.cpSync(path.join(STATIC, "js"), path.join(dir, "js"), { recursive: true });
        fs.writeFileSync(path.join(dir, "package.json"), '{"type":"module"}');          // what makes Node read the .js files as modules
        fs.writeFileSync(path.join(dir, "run.mjs"), `
            import vm from "node:vm";
            import fs from "node:fs";
            const stub = new Proxy(function () {}, {
                get: (t, k) => (k === "then" ? undefined : k === Symbol.toPrimitive ? () => "" : stub),
                apply: () => stub, construct: () => stub });
            for (const k of ["document", "window", "localStorage", "sessionStorage", "location", "navigator"])
                Object.defineProperty(globalThis, k, { value: stub, configurable: true, writable: true });
            globalThis.fetch = () => Promise.reject(new Error("no network in this check"));
            vm.runInThisContext(fs.readFileSync("js/core/theme.js", "utf8"));
            vm.runInThisContext(fs.readFileSync("js/core/boot-log.js", "utf8"));
            vm.runInThisContext(fs.readFileSync("js/core/boot-log.js", "utf8"));
            process.on("unhandledRejection", () => {});
            await import("./js/app.js");
            console.log("LINKED");
            process.exit(0);   // the app starts timers (the screen lock), which would keep this process alive`);
        const out = execFileSync(process.execPath, ["run.mjs"], { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 30000 });
        assert.match(out, /LINKED/);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
});
