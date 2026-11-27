const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/* A page with the Net worth graph loaded and `count` months of history ending in October 2026. */
function page(count = 50) {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/net-worth.js");
    const series = [];
    for (let i = count - 1; i >= 0; i--) {
        const d = new Date(2026, 9 - i, 1);
        series.push({ month: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`, label: `${MONTHS[d.getMonth()]} ${d.getFullYear()}`,
            netWorth: 100000 + (count - 1 - i) * 10000 + ((i * 7) % 5) * 1000, assets: 200000, liabilities: 100000 });
    }
    p.run(`var series = ${JSON.stringify(series)}`);
    return p;
}
const count = (svg, cls) => svg.children.filter(c => c.attrs && c.attrs.class === cls).length;

test("amounts are written in lakh and crore units", () => {
    const p = page();
    assert.deepEqual(["999", "1000", "12500", "320000", "15000000", "-45000"].map(v => p.run(`compactRupees(${v})`)),
        ["₹999", "₹1K", "₹12.5K", "₹3.2L", "₹1.5Cr", "−₹45K"]);
});

test("axis ticks are clean numbers that cover the data, and reach zero when asked", () => {
    const p = page();
    const tight = p.run("niceTicks(120000, 480000, 4, false)");
    assert.ok(tight.min <= 120000 && tight.max >= 480000);
    assert.ok(tight.ticks.every((t, i) => i === 0 || t > tight.ticks[i - 1]));
    assert.ok(p.run("niceTicks(120000, 480000, 4, true)").min <= 0);
    assert.ok(p.run("niceTicks(-500, 900, 4, false)").ticks.includes(0));
});

test("a long history is cut to one point per quarter, the quarter's last month", () => {
    const p = page(50);
    const q = p.run("quarterly(series)");
    assert.ok(q.length >= 16 && q.length <= 18, `${q.length} quarters`);
    assert.equal(q[q.length - 1].month, "2026-10");
    assert.ok(q.slice(0, -1).every(point => [3, 6, 9, 12].includes(Number(point.month.slice(5)))));
});

test("the summary says how far net worth moved, with best and weakest month, for the months in view", () => {
    const p = page(50);
    p.run(`nwZoom = "1Y"; netWorthSummary(series.slice(-12))`);
    const text = p.node("nwSummary").allText;
    assert.match(text, /^Last 12 months: ₹[\d,]+\.\d{2} → ₹[\d,]+\.\d{2}, up ₹[\d,]+\.\d{2} \(\+\d+\.\d%\)\. Best month \w{3} \d{4} \(\+₹[\d,]+\.\d{2}\)/);
    assert.match(text, /weakest \w{3} \d{4} \(/);
    p.run(`nwZoom = "ALL"; netWorthSummary(series)`);
    assert.match(p.node("nwSummary").allText, /^Since Sep 2022: /);
    p.run(`nwZoom = "3Y"; netWorthSummary(series.slice(-10))`);
    assert.match(p.node("nwSummary").allText, /^Since /, "fewer months than the zoom asks for");
    p.run(`netWorthSummary(series.slice(-1))`);
    assert.equal(p.node("nwSummary").allText, "", "one month is not enough for a summary");
    p.run(`netWorthSummary([{ month: "2026-01", label: "Jan 2026", netWorth: -1000 }, { month: "2026-02", label: "Feb 2026", netWorth: -500 }])`);
    assert.match(p.node("nwSummary").allText, /up ₹500\.00/, "a negative start is handled");
});

test("a fall is reported as a fall", () => {
    const p = page(3);
    p.run(`netWorthSummary([{ month: "2026-01", label: "Jan 2026", netWorth: 200000 }, { month: "2026-02", label: "Feb 2026", netWorth: 150000 }])`);
    const text = p.node("nwSummary").allText;
    assert.match(text, /down ₹50,000\.00 \(−25\.0%\)/);
    assert.ok(p.node("nwSummary").find(n => n.className === "down").length === 1);
});

test("the graph marks where the year changes and writes each year once", () => {
    const p = page(12);
    const svg = p.raw("netWorthSvg(series, 900)");
    assert.equal(count(svg, "nw-yearline"), 1, "Oct 2025 .. Oct 2026 crosses one new year");
    assert.ok(count(svg, "nw-year") >= 1 && count(svg, "nw-year") <= 3);
    assert.equal(count(svg, "nw-month") > 0, true);
});

test("the quarterly graph says so in its tooltips and still draws the year changes", () => {
    const p = page(50);
    const svg = p.raw("netWorthSvg(quarterly(series), 900, true)");
    assert.ok(count(svg, "nw-yearline") >= 3);
    const hits = svg.children.filter(c => c.attrs && c.attrs.class === "nw-col");
    assert.ok(hits.length > 10);
});

test("each chart type draws its own marks", () => {
    const p = page(12);
    const marks = type => { p.run(`nwChartType = "${type}"`); const svg = p.raw("netWorthSvg(series, 900)"); return svg.children; };
    const columns = svgs => svgs.flatMap(c => (c.children || []));
    const kinds = type => new Set(columns(marks(type)).map(c => c.attrs && c.attrs.class).filter(Boolean));
    const has = (type, name) => [...kinds(type)].some(k => k.split(" ").includes(name));   // a mark also carries "grow"
    assert.ok(has("bar", "nw-bar"), "bars");
    assert.ok(!has("line", "nw-bar"));
    assert.ok(has("line", "nw-dot"), "line has dots");
    const areaDots = columns(marks("area")).filter(c => c.attrs && String(c.attrs.class).startsWith("nw-dot")).length;
    assert.equal(areaDots, 1, "the area chart keeps only the latest dot");
    assert.ok(marks("bar").every(c => !(c.attrs && c.attrs.class === "nw-line")), "no line in a bar chart");
});

test("a negative month is drawn red in the bar chart", () => {
    const p = page(3);
    p.run(`nwChartType = "bar"`);
    const svg = p.raw(`netWorthSvg([{ month: "2026-01", label: "Jan 2026", netWorth: 1000, assets: 1, liabilities: 1 }, { month: "2026-02", label: "Feb 2026", netWorth: -2000, assets: 1, liabilities: 1 }], 600)`);
    const bars = svg.children.flatMap(c => c.children || []).filter(c => c.attrs && String(c.attrs.class).startsWith("nw-bar"));
    assert.deepEqual(bars.map(b => b.attrs.class), ["nw-bar grow", "nw-bar neg grow"]);
});

test("the table has a block per year, newest first, and only the latest year is open", () => {
    const p = page(50);
    const table = p.raw("netWorthTable(series)");
    const years = new Set(p.run("series").map(s => s.label.split(" ")[1]));
    assert.equal(table.children.length, years.size);
    assert.ok(table.children[0].open === true);
    assert.ok(table.children.slice(1).every(d => d.open === false));
    assert.match(table.children[0].allText, /^2026/);
    assert.match(table.children[0].children[0].allText, /10 months · ends at ₹/);
    const single = page(3).raw("netWorthTable(series.slice(-1))");
    assert.equal(single.children[0].open, true, "a lone year is open");
    assert.match(single.children[0].allText, /1 month · ends at/);
});

test("the zoom decides how many months show and the note says when points are quarters", () => {
    const p = page(50);
    p.run(`transactions = [{ date: "2022-09-15" }]; periodRange = "ALL"; netWorthSeries = series; renderNetWorth()`);
    assert.match(p.node("nwSummary").allText, /^Last 12 months/);
    assert.doesNotMatch(p.node("nwNote").allText, /one point per quarter/);
    p.run(`nwZoom = "ALL"; renderNetWorth()`);
    assert.match(p.node("nwSummary").allText, /^Since Sep 2022/);
    assert.match(p.node("nwNote").allText, /one point per quarter/);
    p.run(`nwZoom = "3Y"; renderNetWorth()`);
    assert.doesNotMatch(p.node("nwNote").allText, /one point per quarter/, "36 months are still drawn month by month");
});

test("with nothing to show the controls disappear and a message says why", () => {
    const p = page(0);
    p.run(`transactions = []; netWorthSeries = []; renderNetWorth()`);
    assert.match(p.node("nwChart").allText, /Add accounts and transactions/);
    p.run(`netWorthSeries = [{ month: "2026-10", label: "Oct 2026", netWorth: 5, assets: 0, liabilities: 0 }]; renderNetWorth()`);
    assert.ok(p.node("nwZoom").classList.contains("hidden"));
    assert.ok(p.node("nwTableToggle").classList.contains("hidden"));
});

test("the Graph / Allocation buttons turn the card over, and the face turned away is inert", () => {
    const p = page();
    p.run(`document.querySelectorAll = () => []`);   // the chips are looked up by selector; this page has none
    p.node("nwFaceAllocation").inert = true;
    p.run(`showNetWorthFace("allocation")`);
    assert.ok(p.node("nwFlip").classList.contains("flipped"));
    assert.equal(p.node("nwFaceGraph").inert, true);
    assert.equal(p.node("nwFaceAllocation").inert, false);
    p.run(`showNetWorthFace("graph")`);
    assert.ok(!p.node("nwFlip").classList.contains("flipped"));
    assert.equal(p.node("nwFaceGraph").inert, false);
    assert.equal(p.node("nwFaceAllocation").inert, true);
});

test("the page starts on the graph with the allocation face turned away and the two buttons in place", () => {
    const html = require("node:fs").readFileSync(require("node:path").join(require("./harness").STATIC, "panels", "net-worth.html"), "utf8");
    assert.match(html, /id="nwFaceAllocation" inert/, "the back face starts inert");
    assert.deepEqual([...html.matchAll(/data-face="(\w+)"/g)].map(m => m[1]), ["graph", "allocation"]);
    assert.ok(html.indexOf('id="nwFaceGraph"') < html.indexOf('id="nwChart"') && html.indexOf('id="nwChart"') < html.indexOf('id="nwFaceAllocation"'),
        "the graph is on the front face");
});
