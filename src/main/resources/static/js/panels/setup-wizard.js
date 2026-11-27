/* First-time setup wizard, shown when there are no accounts yet.
   Screens: Welcome, Account types at a glance, Bank & Cash, Loans & credit, Everything else, Done.
   The accounts typed in are kept in memory and saved together (all or nothing) on the last content screen. */

import { TYPE_LABELS } from "../core/constants.js";
import { $, api, el, ordinal, parseDueDay, rupees } from "../core/util.js";
import { refreshData } from "../core/refresh.js";

const SETUP_STEPS = ["Welcome", "Account types", "Bank & Cash", "Loans & credit", "Everything else", "Done"];
const setup = { step: 0, entries: [], saved: 0 };   // entries: { name, type, balance, creditLimit }

/* One card per account type on the "at a glance" screen. now = set up in this wizard. */
const SETUP_GLANCE = [
    { name: "Bank", what: "Savings and current accounts.", get: "Your balance and a record of every transaction.", now: true },
    { name: "Cash", what: "Cash in hand or a wallet.", get: "Track top-ups and everyday spending.", now: true },
    { name: "Goal funds", what: "Money set aside for a purpose, like your Emergency Fund.", get: "Progress to a target, runway in months and an estimated date.", now: false },
    { name: "Investments", what: "Mutual funds, stocks and similar.", get: "Their current value counts towards your net worth.", now: false },
    { name: "Gold", what: "Gold you own.", get: "Its value counts towards your net worth.", now: false },
    { name: "Property", what: "A house, flat or land.", get: "Its value counts towards your net worth.", now: false },
    { name: "Vehicle", what: "A car or a bike.", get: "Its value counts towards your net worth.", now: false },
    { name: "Receivables", what: "Money other people owe you.", get: "One running total, with each person you lent to listed under it.", now: true },
    { name: "Debt", what: "Loans you owe, such as a home loan.", get: "The amount owed is subtracted from your net worth.", now: true },
    { name: "Credit card", what: "Cards you owe money on.", get: "Shows how much of your limit you have used.", now: true },
    { name: "Other", what: "Anything that doesn't fit above.", get: "Add it later with the + Add account button of its level on the dashboard.", now: false }
];

/* The two screens that take accounts. */
const SETUP_ADD_SCREENS = {
    2: {
        types: ["BANK", "CASH"],
        intro: ["Start with the money you can spend today. Add each bank account and any cash you keep, with what is in it right now.",
                "You don't need past transactions. Just today's balance, then record new ones as they happen."],
        example: "e.g. Salary account, Savings, Wallet"
    },
    3: {
        types: ["RECEIVABLE", "DEBT", "CREDIT_CARD"],
        intro: ["Money you have lent, loans you owe and credit cards.",
                "Receivables is one total of what others owe you, so it needs no name. For a loan, enter what you still owe. " +
                "For a credit card, enter your limit and how much you currently owe, so the dashboard can show how much of the limit is used."],
        example: "e.g. Home Loan, HDFC Credit Card"
    }
};

const SETUP_BALANCE_LABEL = {
    BANK: "Opening balance (₹)", CASH: "Opening balance (₹)", RECEIVABLE: "Total owed to you (₹)",
    DEBT: "Amount you owe (₹)", CREDIT_CARD: "Currently owed (₹)"
};

export function openSetupWizard() {
    setup.step = 0;
    setup.entries = [];
    setup.saved = 0;
    $("setupOverlay").classList.remove("hidden");
    showSetupStep();
}

function closeSetupWizard() {
    $("setupOverlay").classList.add("hidden");
    showDashboard();
}

function showSetupStep() {
    $("setupError").textContent = "";
    $("setupSteps").textContent = `Step ${setup.step + 1} of ${SETUP_STEPS.length} · ${SETUP_STEPS[setup.step]}`;
    const content = $("setupContent");
    content.innerHTML = "";
    const last = setup.step === SETUP_STEPS.length - 1;
    $("setupBack").classList.toggle("hidden", setup.step === 0 || last);
    $("setupSkip").classList.toggle("hidden", last);
    $("setupNext").textContent = setup.step === 0 ? "Get started"
        : setup.step === 4 ? "Save and finish" : last ? "Open dashboard" : "Next";
    [setupWelcome, setupGlance, setupAddScreen, setupAddScreen, setupElsewhere, setupDone][setup.step](content);
}

/* ---------- screens ---------- */

function setupWelcome(root) {
    root.append(
        el("h3", { textContent: "Let's set up your dashboard" }),
        el("p", { className: "setup-lead", textContent: "This takes about a minute. We'll start with your bank accounts and cash, then any loans and credit cards. " +
            "Goals, investments and property are added from the dashboard when you are ready." }),
        el("p", { className: "setup-lead", textContent: "You don't need to enter past transactions. Just where things stand today. You can change anything later." }));
}

function setupGlance(root) {
    root.append(el("p", { className: "setup-lead", textContent: "Here is every kind of account the dashboard keeps, and what you get from each." }));
    const grid = el("div", { className: "glance-grid" });
    for (const g of SETUP_GLANCE) {
        grid.append(el("div", { className: "glance-card" },
            el("h4", { textContent: g.name }),
            el("div", { className: "what", textContent: g.what }),
            el("div", { className: "get", textContent: g.get }),
            el("span", { className: "glance-tag" + (g.now ? " now" : ""), textContent: g.now ? "Set up now" : "Add from the dashboard" })));
    }
    root.append(grid);
}

function setupAddScreen(root) {
    const cfg = SETUP_ADD_SCREENS[setup.step];
    cfg.intro.forEach(text => root.append(el("p", { className: "setup-lead", textContent: text })));

    const list = el("div");
    const refresh = () => {
        list.innerHTML = "";
        const mine = setup.entries.filter(e => cfg.types.includes(e.type));
        if (mine.length === 0) list.append(el("div", { className: "setup-empty", textContent: "Nothing added yet. You can skip this screen." }));
        for (const e of mine) {
            const remove = el("button", { className: "icon-btn", textContent: "✕", title: "Remove" });
            remove.addEventListener("click", () => { setup.entries.splice(setup.entries.indexOf(e), 1); refresh(); });
            const amount = rupees(e.balance || 0);
            const limit = e.type === "CREDIT_CARD" ? ` · limit ${rupees(e.creditLimit)}` + (e.statementDay ? ` · statement on the ${ordinal(e.statementDay)}` : "") + (e.dueDay ? ` · bill due on the ${ordinal(e.dueDay)}` : "") : "";
            list.append(el("div", { className: "setup-entry" },
                el("span", { className: "pair" }, el("span", { className: "label", textContent: "Name: " }), e.type === "RECEIVABLE" ? "Receivables" : e.name),
                el("span", { className: "pair" }, el("span", { className: "label", textContent: "Type: " }), TYPE_LABELS[e.type]),
                el("span", { className: "pair" }, el("span", { className: "label", textContent: `${SETUP_BALANCE_LABEL[e.type].replace(" (₹)", "")}: ` }), amount + limit),
                remove));
        }
    };
    refresh();
    root.append(list, setupAddForm(cfg, refresh));
}

/* A "+ Add account" button that opens a small form: type, name, balance and (for cards) the credit limit. */
function setupAddForm(cfg, refresh) {
    const type = el("select", {}, ...cfg.types.map(t => el("option", { value: t, textContent: TYPE_LABELS[t] })));
    const name = el("input", { type: "text", placeholder: cfg.example, autocomplete: "off", maxLength: 100 });
    const balance = el("input", { type: "number", min: 0, step: "any" });
    const limit = el("input", { type: "number", min: 0, step: "any" });
    const statement = el("input", { type: "number", min: 1, max: 31, step: 1, placeholder: "5" });
    const due = el("input", { type: "number", min: 1, max: 31, step: 1, placeholder: "21" });

    const typeField = el("label", { className: "wizard-field" }, "Account Type", type);
    const nameField = el("label", { className: "wizard-field" }, "Account Name", name);
    const balanceLabel = el("span", { textContent: SETUP_BALANCE_LABEL[cfg.types[0]] });
    const balanceField = el("label", { className: "wizard-field" }, balanceLabel, balance);
    const limitField = el("label", { className: "wizard-field hidden" }, "Credit limit (₹) *", limit);
    const statementField = el("label", { className: "wizard-field hidden" }, "Statement (bill) generated on day of the month (optional)", statement);
    const dueField = el("label", { className: "wizard-field hidden" }, "Bill due on day of the month (optional)", due);

    const sync = () => {
        const t = type.value;
        typeField.classList.toggle("hidden", cfg.types.length === 1);
        nameField.classList.toggle("hidden", t === "RECEIVABLE");        // one header account, no name needed
        limitField.classList.toggle("hidden", t !== "CREDIT_CARD");
        statementField.classList.toggle("hidden", t !== "CREDIT_CARD");
        dueField.classList.toggle("hidden", t !== "CREDIT_CARD");
        balanceLabel.textContent = SETUP_BALANCE_LABEL[t];
    };
    type.addEventListener("change", sync);

    const add = el("button", { className: "btn btn-primary", textContent: "Add" });
    const cancel = el("button", { className: "btn btn-secondary", textContent: "Cancel" });
    const form = el("div", { className: "setup-form hidden" }, typeField, nameField, balanceField, limitField, statementField, dueField,
        el("div", { className: "setup-form-buttons" }, add, cancel));
    const open = el("button", { className: "btn btn-secondary", textContent: "+ Add account" });
    open.style.marginTop = "12px";

    const show = visible => {
        form.classList.toggle("hidden", !visible);
        open.classList.toggle("hidden", visible);
        if (visible) { sync(); (type.value === "RECEIVABLE" ? balance : name).focus(); }
        else { name.value = ""; balance.value = ""; limit.value = ""; statement.value = ""; due.value = ""; }
    };
    const submit = () => {
        const t = type.value;
        const entryName = t === "RECEIVABLE" ? "Receivables" : name.value.trim();
        const error = $("setupError");
        if (!entryName) { error.textContent = "Enter a name for the account."; return; }
        const key = entryName.toLowerCase();
        if (setup.entries.some(e => (e.type === "RECEIVABLE" ? "receivables" : e.name.toLowerCase()) === key)) {
            error.textContent = t === "RECEIVABLE" ? "Receivables is already added." : `"${entryName}" already exists.`;
            return;
        }
        if (balance.value !== "" && !(Number(balance.value) >= 0)) { error.textContent = "The amount cannot be negative."; return; }
        if (t === "CREDIT_CARD" && !(Number(limit.value) > 0)) { error.textContent = "A credit card needs a credit limit greater than zero."; return; }
        const dueDay = parseDueDay(due.value);
        if (t === "CREDIT_CARD" && dueDay === undefined) { error.textContent = "The due day must be a day of the month, from 1 to 31."; return; }
        const statementDay = parseDueDay(statement.value);
        if (t === "CREDIT_CARD" && statementDay === undefined) { error.textContent = "The statement day must be a day of the month, from 1 to 31."; return; }
        error.textContent = "";
        setup.entries.push({ name: entryName, type: t, balance: balance.value === "" ? 0 : Number(balance.value),
            creditLimit: t === "CREDIT_CARD" ? Number(limit.value) : null, dueDay: t === "CREDIT_CARD" ? dueDay : null, statementDay: t === "CREDIT_CARD" ? statementDay : null });
        show(false);
        refresh();
    };
    open.addEventListener("click", () => show(true));
    cancel.addEventListener("click", () => show(false));
    add.addEventListener("click", submit);
    [name, balance, limit, statement, due].forEach(input => input.addEventListener("keydown", e => {
        if (e.key === "Enter") submit();
        if (e.key === "Escape") show(false);
    }));

    return el("div", {}, open, form);
}

function setupElsewhere(root) {
    root.append(
        el("p", { className: "setup-lead", textContent: "The rest of your accounts are added from the dashboard, each with an explanation, whenever you are ready." }),
        el("ul", { className: "setup-points" },
            el("li", {}, el("strong", { textContent: "Goal funds: " }),
                "your Emergency Fund card is already waiting on the dashboard, and you can add goals of your own. The Future Expenses Ledger, for bills you know are coming, is there too."),
            el("li", {}, el("strong", { textContent: "Investments and Gold: " }), "enter their current value."),
            el("li", {}, el("strong", { textContent: "Property and Vehicle: " }), "enter what they are worth today."),
            el("li", {}, el("strong", { textContent: "Other: " }), "for anything that doesn't fit, which is rare.")),
        el("p", { className: "setup-lead" }, "Use the ", el("strong", { textContent: "+ Add account" }), " button of any level on the dashboard at any time."),
        el("p", { className: "edit-hint", textContent: setup.entries.length
            ? `Next we'll save the ${setup.entries.length} account${setup.entries.length === 1 ? "" : "s"} you added.`
            : "You haven't added any accounts yet. That's fine, you can add them from the dashboard." }));
}

function setupDone(root) {
    root.append(
        el("h3", { textContent: "You're all set" }),
        el("p", { className: "setup-lead", textContent: setup.saved
            ? `${setup.saved} account${setup.saved === 1 ? "" : "s"} saved. Your dashboard is ready.`
            : "Your dashboard is ready. Add accounts any time with the + Add account button of a level." }),
        el("p", { className: "edit-hint", textContent: "Tip: set up your Emergency Fund from its card in the Goals section." }));
}

/* ---------- saving and navigation ---------- */

async function saveSetupAccounts() {
    if (setup.entries.length === 0) { setup.saved = 0; return; }
    const payload = setup.entries.map(e => ({
        name: e.name, type: e.type, openingBalance: e.balance || 0,
        creditLimit: e.type === "CREDIT_CARD" ? e.creditLimit : null,
        dueDay: e.type === "CREDIT_CARD" ? e.dueDay : null,
        statementDay: e.type === "CREDIT_CARD" ? e.statementDay : null
    }));
    const created = await api("/api/setup/accounts", { method: "POST", body: payload });
    setup.saved = created.length;
}

async function setupNext() {
    if (setup.step === SETUP_STEPS.length - 1) { closeSetupWizard(); return; }
    if (setup.step === 4) {
        const next = $("setupNext");
        next.disabled = true;
        next.textContent = "Saving...";
        try {
            await saveSetupAccounts();
        } catch (e) {
            $("setupError").textContent = e.message;
            next.disabled = false;
            next.textContent = "Save and finish";
            return;
        }
        next.disabled = false;
        setup.step++;
        showSetupStep();
        // Reload in the background: the accounts are saved, so a failure here must not block the wizard.
        refreshData().catch(e => {
            $("dashboardDate").textContent = "Accounts saved, but the dashboard could not reload: " + e.message;
        });
        return;
    }
    setup.step++;
    showSetupStep();
}

export function initSetupWizard() {
    $("setupNext").addEventListener("click", setupNext);
    $("setupBack").addEventListener("click", () => { setup.step--; showSetupStep(); });
    $("setupSkip").addEventListener("click", closeSetupWizard);
    $("setupClose").addEventListener("click", closeSetupWizard);
}

/* The dashboard stays hidden until the first-run steps are over (or the page found a password already set). */
export function showDashboard() {
    $("dashboardContainer").classList.remove("hidden");
}
