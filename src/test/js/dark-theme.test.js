/* The dark theme is colours in CSS, so these tests read the CSS: nothing the light theme defines may be forgotten in the
   dark one, and the text and fills must be readable (WCAG contrast) on the dark surfaces. */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { STATIC } = require("./harness");

const css = f => fs.readFileSync(path.join(STATIC, "css", f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const base = css("base.css");

function block(source, selector) {
    const start = source.indexOf(selector + " {");
    assert.ok(start >= 0, `no ${selector} block`);
    return source.slice(start, source.indexOf("}", start));
}
const variables = text => Object.fromEntries([...text.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)].map(m => [m[1], m[2].trim()]));

const light = variables(block(base, ":root"));
const dark = variables(block(base, ':root[data-theme="dark"]'));

function luminance(hex) {
    const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map(v => v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const contrast = (a, b) => { const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); };

test("every colour the light theme defines is also defined for the dark theme, unless it is meant to stay the same", () => {
    const sameInBoth = new Set(["--accent-fill", "--green-fill", "--red-fill"]);   // fills under white text keep their values
    const forgotten = Object.keys(light).filter(name => !(name in dark) && !sameInBoth.has(name));
    assert.deepEqual(forgotten, []);
});

test("the text colours are readable on the dark page and card (4.5:1)", () => {
    for (const background of [dark["--bg"], dark["--surface"]]) {
        for (const name of ["--text", "--muted", "--accent", "--green", "--red", "--yellow"]) {
            assert.ok(contrast(dark[name], background) >= 4.5, `${name} ${dark[name]} on ${background}: ${contrast(dark[name], background).toFixed(2)}`);
        }
    }
});

test("fills that carry white text are dark enough for it in both themes (4.5:1)", () => {
    for (const theme of [light, { ...light, ...dark }]) {   // the dark theme inherits what it does not redefine
        for (const name of ["--accent-fill", "--green-fill", "--red-fill"]) {
            const value = theme[name].startsWith("var(") ? light[theme[name].slice(4, -1)] : theme[name];
            assert.ok(contrast("#ffffff", value) >= 4.5, `white on ${name} ${value}: ${contrast("#ffffff", value).toFixed(2)}`);
        }
    }
});

test("the light theme's own text colours are still readable on its surface", () => {
    for (const name of ["--text", "--muted", "--accent", "--green", "--red"]) {
        assert.ok(contrast(light[name], light["--surface"]) >= 4.5, name);
    }
});

test("the chart colours for the dark theme are the five that passed the data-viz validator on #171d26", () => {
    const palette = variables(block(css("charts.css"), ':root[data-theme="dark"]'));
    assert.equal(dark["--surface"], "#171d26", "the validator was run against this surface");
    assert.deepEqual(["--s0", "--s1", "--s2", "--s3", "--s4"].map(n => palette[n]), ["#2f8cf0", "#e8652d", "#16a87a", "#bf8300", "#e0599a"]);
    for (const name of ["--s0", "--s1", "--s2", "--s3", "--s4"]) assert.ok(contrast(palette[name], dark["--surface"]) >= 3, `${name} against the dark card`);
});

test("the Color theme keeps its text and accent colours readable (4.5:1) and white text on its fill", () => {
    const colour = { ...light, ...variables(block(css("color-theme.css"), ':root[data-theme="color"]')) };
    for (const name of ["--text", "--muted", "--accent", "--green", "--red"]) {
        assert.ok(contrast(colour[name], colour["--surface"]) >= 4.5, `${name} ${colour[name]} on the card: ${contrast(colour[name], colour["--surface"]).toFixed(2)}`);
        assert.ok(contrast(colour[name], colour["--bg"]) >= 4.5, `${name} on the page ${colour["--bg"]}: ${contrast(colour[name], colour["--bg"]).toFixed(2)}`);
    }
    assert.ok(contrast("#ffffff", colour["--accent-fill"]) >= 4.5, "white on --accent-fill");
});

test("no style sheet uses a variable that nothing defines", () => {
    const defined = new Set();
    const used = new Set();
    for (const file of fs.readdirSync(path.join(STATIC, "css"))) {
        const text = css(file);
        for (const m of text.matchAll(/(--[a-z0-9-]+)\s*:/g)) defined.add(m[1]);
        for (const m of text.matchAll(/var\((--[a-z0-9-]+)/g)) used.add(m[1]);
    }
    const inScripts = new Set(fs.readdirSync(path.join(STATIC, "js"), { recursive: true }).filter(f => f.endsWith(".js"))
        .flatMap(f => [...fs.readFileSync(path.join(STATIC, "js", f), "utf8").matchAll(/(--[a-z0-9-]+)/g)].map(m => m[1])));
    assert.deepEqual([...used].filter(v => !defined.has(v) && !inScripts.has(v)), []);
});

test("the solid colours that used to be fixed hex values now follow the theme", () => {
    for (const [file, forbidden] of [["net-worth.css", /(stroke|fill):\s*#0a84ff/], ["portfolio-stats.css", /(stroke|fill):\s*#(0a84ff|00a86b)/]]) {
        assert.doesNotMatch(css(file), forbidden, file);
    }
    assert.doesNotMatch(fs.readFileSync(path.join(STATIC, "js", "panels", "net-worth.js"), "utf8"), /"stop-color": "#0a84ff"/);
});

test("the Calendar heatmap steps are the specified light colours, run dim to strong in both themes, and their text is readable (4.5:1)", () => {
    const spec = { e: ["#E5E7EB", "#FEE2E2", "#FCA5A5", "#DC2626", "#991B1B"], i: ["#E5E7EB", "#DCFCE7", "#86EFAC", "#16A34A", "#166534"] };
    for (const side of ["e", "i"]) {
        assert.deepEqual([0, 1, 2, 3, 4].map(n => light[`--heat-${side}${n}`].toUpperCase()), spec[side], `light ${side}`);
        for (const [theme, vars] of [["light", light], ["dark", dark]]) {
            for (let n = 0; n <= 4; n++) {
                const fill = vars[`--heat-${side}${n}`], text = vars[`--heat-${side === "e" ? "et" : "it"}${n}`];
                assert.ok(fill && text, `${theme} step ${n} is defined`);
                assert.ok(contrast(fill, text) >= 4.5, `${theme} ${side}${n} ${fill} with ${text}: ${contrast(fill, text).toFixed(2)}`);
            }
        }
    }
    // dark steps 1 to 4 never go backwards in strength: each is further from the dark page than the one before
    for (const side of ["e", "i"]) {
        const strength = n => contrast(dark[`--heat-${side}${n}`], dark["--bg"]);
        for (let n = 2; n <= 4; n++) assert.ok(strength(n) >= strength(n - 1), `dark ${side}${n} is not stronger than ${side}${n - 1}`);
    }
});
