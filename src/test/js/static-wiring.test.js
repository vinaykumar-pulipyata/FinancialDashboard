/* The page is assembled from many files. These checks catch the mistakes that only show up in a browser: a script that
   Dashboard.html does not load, a duplicated name, an id nothing defines. (module-graph.test.js checks the imports.) */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { STATIC, toClassicScript } = require("./harness");

const read = f => fs.readFileSync(path.join(STATIC, f), "utf8");
const list = dir => fs.readdirSync(path.join(STATIC, dir), { withFileTypes: true }).flatMap(e =>
    e.isDirectory() ? list(path.join(dir, e.name)) : [path.join(dir, e.name)]);

const shell = read("Dashboard.html");
const jsFiles = list("js");
const htmlFragments = ["Dashboard.html", ...list("panels")];
const allHtml = htmlFragments.map(read).join("\n");
const stripComments = js => js.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`])\/\/.*$/gm, "$1");

test("every script file parses", () => {
    for (const file of jsFiles) assert.doesNotThrow(() => new vm.Script(toClassicScript(read(file)), { filename: file }), file);
});

test("Dashboard.html loads theme.js and boot-log.js as plain scripts first, and app.js, once, as the one module", () => {
    const plain = [...shell.matchAll(/<script src="([^"]+)"/g)].map(m => m[1]);
    const modules = [...shell.matchAll(/<script type="module" src="([^"]+)"/g)].map(m => m[1]);
    assert.deepEqual(plain, ["js/core/theme.js", "js/core/boot-log.js"], "both must be blocking: the saved theme is on the page before anything is drawn, and a module that fails to load can only be reported by a plain script");
    assert.deepEqual(modules, ["js/app.js"], "app.js imports every other file");
    assert.ok(shell.indexOf("js/core/theme.js") < shell.indexOf("js/app.js"));
});

test("every stylesheet exists and utilities.css is last, so .hidden always wins", () => {
    const links = [...shell.matchAll(/<link rel="stylesheet" href="([^"]+)"/g)].map(m => m[1]);
    for (const href of links) assert.ok(fs.existsSync(path.join(STATIC, href)), href);
    assert.equal(links[links.length - 1], "css/utilities.css");
});

test("every data-include points at a fragment that exists", () => {
    for (const [, target] of allHtml.matchAll(/data-include="([^"]+)"/g)) assert.ok(fs.existsSync(path.join(STATIC, target)), target);
});

test("no id is defined twice across the page and its fragments", () => {
    const ids = [...allHtml.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
    assert.deepEqual(ids.filter((id, i) => ids.indexOf(id) !== i), []);
});

test("every id the scripts look up exists in the page or is created by a script", () => {
    const defined = new Set([...allHtml.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
    const code = jsFiles.map(read).join("\n");
    for (const m of code.matchAll(/\bid: "([^"]+)"/g)) defined.add(m[1]);          // ids a script gives to elements it builds
    const used = new Set([...code.matchAll(/\$\("([A-Za-z0-9_-]+)"\)/g)].map(m => m[1]));
    assert.deepEqual([...used].filter(id => !defined.has(id)), []);
});

test("top-level names are unique across all the files (a test page loads them into one scope; the browser does not need it)", () => {
    const seen = new Map();
    const clashes = [];
    for (const file of jsFiles) {
        const code = stripComments(read(file));
        for (const m of code.matchAll(/^(?:async\s+)?function\s+([A-Za-z0-9_$]+)|^(?:const|let|var)\s+([A-Za-z0-9_$]+)\s*=/gm)) {
            const name = m[1] || m[2];
            if (seen.has(name)) clashes.push(`${name} in ${seen.get(name)} and ${file}`);
            else seen.set(name, file);
        }
    }
    assert.deepEqual(clashes, []);
});

test("nothing builds markup from data: no innerHTML with content, eval or document.write", () => {
    for (const file of jsFiles) {
        const code = read(file);
        const assigned = [...code.matchAll(/innerHTML\s*=\s*([^;]+);/g)].map(m => m[1].trim());
        assert.deepEqual(assigned.filter(v => v !== '""'), [], `${file} sets innerHTML to something other than an empty string`);
        assert.ok(!/\beval\(|document\.write|insertAdjacentHTML|new Function/.test(code), file);
    }
});

test("a table row that shares the name cat-row with the categories dialog is still a table row", () => {
    // categories.css gives .cat-row display:flex. Without this reset the cells of the Stats and Net worth tables stop being columns:
    // they shrink to their content, sit 8px apart and no longer line up under the headings.
    const charts = fs.readFileSync(path.join(STATIC, "css", "charts.css"), "utf8");
    assert.match(charts, /\.chart-table tr\.cat-row\s*\{[^}]*display:\s*table-row/);
    const flex = fs.readFileSync(path.join(STATIC, "css", "categories.css"), "utf8");
    assert.match(flex, /^\.cat-row\s*\{[^}]*display:\s*flex/m, "the collision this guards against still exists");
});
