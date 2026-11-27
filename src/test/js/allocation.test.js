const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* "Where your assets are": the ring of what you own by kind of account, under the net worth graph. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/net-worth.js", "panels/summary.js", "panels/allocation.js");
    p.run(`accounts = [
        { active: true, type: "BANK", balance: 60000 }, { active: true, type: "BANK", balance: 20000 },
        { active: true, type: "CASH", balance: 5000 }, { active: true, type: "GOLD", balance: 10000 },
        { active: true, type: "INVESTMENT", balance: 40000 }, { active: true, type: "PROPERTY", balance: 100000 },
        { active: true, type: "VEHICLE", balance: 30000 }, { active: true, type: "GOAL_FUND", balance: 15000 },
        { active: true, type: "RECEIVABLE", balance: 999 }, { active: true, type: "CREDIT_CARD", balance: 7000 },
        { active: false, type: "BANK", balance: 888888 }]`);
    p.run("initSummary(); initAllocation(); renderSummary()");
    return p;
}

test("every kind gets its own slice, biggest first, with no Other; lent money and debt are not in it", () => {
    const p = page();
    const { slices, total } = p.run("allocationSlices(assetsByKind())");
    assert.equal(total, 280000, "bank 80,000 + cash 5,000 + gold 10,000 + investments 40,000 + property 100,000 + vehicle 30,000 + goal 15,000");
    assert.deepEqual(slices.map(s => [s.key, s.name, s.amount]), [
        ["s0", "Property", 100000], ["s1", "Bank accounts", 80000], ["s2", "Investments", 40000], ["s3", "Vehicles", 30000],
        ["s4", "Goal funds", 15000], ["s5", "Gold", 10000], ["s6", "Cash", 5000]]);
    assert.ok(!slices.some(s => s.name === "Other"), "nothing is folded away");
    assert.deepEqual(slices.map(s => s.colour), ["s0", "s1", "s2", "s3", "s4", "s5", "s6"]);
    assert.equal(slices.reduce((sum, s) => sum + s.amount, 0), total, "the slices add up to the total");
});

test("with nothing to show the back of the card says so instead of drawing an empty ring", () => {
    const p = page();
    p.run("accounts = []; renderSummary(); renderAllocation()");
    const box = p.node("nwAllocation");
    assert.ok(!box.classList.contains("hidden"));
    assert.equal(box.find(n => n.tag === "svg").length, 0);
    assert.match(box.allText, /Nothing to show yet/);
});

test("the ring has a trace circle and one slice per kind, with the percentages always shown", () => {
    const p = page();
    p.run("renderAllocation()");
    const box = p.node("nwAllocation");
    assert.ok(!box.classList.contains("hidden"));
    assert.equal(box.find(n => n.attrs && n.attrs.class === "donut-trace").length, 1);
    assert.equal(box.find(n => n.attrs && String(n.attrs.class).startsWith("slice ")).length, 7);
    assert.match(box.allText, /Property.*36%/s, "100,000 of 280,000 is 36%");
});

test("the amounts are masked until the eye is pressed, and shown after it", () => {
    const p = page();
    p.run("renderAllocation()");
    const box = p.node("nwAllocation");
    assert.ok(!box.allText.includes("1,00,000"), box.allText);
    assert.ok(box.allText.includes("₹ ••••••••"));
    assert.ok(box.allText.includes("You owe ₹ ••••••••"), "the debt line follows the same rule");
    p.node("nwEye").fire("click");
    assert.ok(box.allText.includes("₹1,00,000"), "revealed");
    assert.ok(box.allText.includes("You owe ₹7,000"));
    p.node("nwEye").fire("click");
    assert.ok(!box.allText.includes("₹1,00,000"), "hidden again");
});

test("the slice colours exist where the ring is drawn: --s0..--so are defined on the ring's own panel, in light and in dark", () => {
    // They are defined on .chart-card, not on :root, so a ring outside a .chart-card needs its own copy or its slices have no colour
    // (stroke: var(--s0) with nothing behind it paints nothing: the ring looked empty).
    const fs = require("node:fs"), path = require("node:path");
    const css = fs.readFileSync(path.join(require("./harness").STATIC, "css", "charts.css"), "utf8");
    assert.match(css, /\.chart-card,\s*\.nw-allocation\s*\{[^}]*--s0:\s*#0a84ff[^}]*--so:/s, "light colours");
    assert.match(css, /:root\[data-theme="dark"\]\s+\.nw-allocation\s*\{[^}]*--s0:\s*#2f8cf0[^}]*--so:\s*#8a8a85/s, "dark colours");
    const html = fs.readFileSync(path.join(require("./harness").STATIC, "panels", "net-worth.html"), "utf8");
    assert.ok(/class="nw-allocation[ "]/.test(html), "the ring's panel is the element that carries the colours");
});

test("after twelve parts the colours come round again, but every part keeps its own slice and key", () => {
    const p = page();
    const { slices } = p.run(`allocationSlices(Array.from({ length: 14 }, (_, i) => ({ label: "Part " + i, amount: 100 - i })))`);
    assert.equal(slices.length, 14);
    assert.equal(new Set(slices.map(s => s.key)).size, 14);
    assert.deepEqual([slices[11].colour, slices[12].colour, slices[13].colour], ["s11", "s0", "s1"]);
});

test("the twelve slice colours are defined in light and dark, and each has a slice, swatch and bar rule", () => {
    const fs = require("node:fs"), path = require("node:path");
    const css = fs.readFileSync(path.join(__dirname, "../../main/resources/static/css/charts.css"), "utf8");
    for (let i = 0; i < 12; i++) {
        assert.equal((css.match(new RegExp(`--s${i}:`, "g")) || []).length, 3, `--s${i} in light, in dark, and in dark on the ring`);
        assert.ok(css.includes(`.donut .slice.s${i} {`) && css.includes(`.chart-table .swatch.s${i} {`), `slice and swatch ${i}`);
        if (i) assert.ok(css.includes(`.cat-bars.s${i} {`), `bar ${i}`);
    }
});
