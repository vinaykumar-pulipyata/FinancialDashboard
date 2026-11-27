package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import com.learning.spring.financialdashboard.model.JewelleryItem;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CategoryRepository;
import com.learning.spring.financialdashboard.repository.JewelleryItemRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jewellery: gold, silver and other metals you own, as items with a name, a form, a weight and the price paid, inside one
 * Jewellery account (Level 3). No current value is kept, because bought jewellery is rarely sold: the account's balance is
 * what the items cost. Money only moves when an item is paid for from a bank or cash account (an ordinary transfer in) or
 * sold (a transfer out of what it cost, plus the profit as income or the loss as an expense).
 * Ordinary transactions on the account are refused, as for the Ledger and the portfolios.
 */
@Service
@Transactional
public class JewelleryService {

    private static final Logger log = LoggerFactory.getLogger(JewelleryService.class);

    public static final String ACCOUNT_NAME = "Jewellery";
    public static final List<String> FORMS = List.of("ORNAMENT", "COIN", "BAR", "LOOSE", "OTHER");

    /** paidFromAccountId is only for a new item: a bank or cash account the price was paid from; left out, the item is one you already own. */
    public record ItemData(String metal, String name, String form, BigDecimal weight, BigDecimal boughtPrice, Long paidFromAccountId) {
    }

    /** weight is optional: how much of the item is sold (all of it when left out). */
    public record SaleRequest(Long toAccountId, BigDecimal salePrice, BigDecimal weight) {
    }

    /** paidByAccount: the price went out of a bank or cash account when the piece was added, so removing it puts that money back. */
    public record ItemView(Long id, String metal, String name, String form, BigDecimal weight, BigDecimal boughtPrice, boolean paidByAccount) {
    }

    public record MetalView(String metal, BigDecimal weight, BigDecimal boughtPrice, List<ItemView> items) {
    }

    public record View(boolean configured, Long accountId, String name, BigDecimal boughtPrice, List<MetalView> metals) {

        static View notConfigured() {
            return new View(false, null, ACCOUNT_NAME, BigDecimal.ZERO, List.of());
        }
    }

    private final TransactionTagService tagService;
    private final JewelleryItemRepository items;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;

    private final BalanceGuard balanceGuard;

    public JewelleryService(JewelleryItemRepository items, AccountRepository accounts, AccountService accountService,
                            TransactionRepository transactions, CategoryRepository categories,
                         TransactionTagService tagService, BalanceGuard balanceGuard) {
        this.balanceGuard = balanceGuard;
        this.tagService = tagService;
        this.items = items;
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public View get() {
        return account().map(this::view).orElseGet(View::notConfigured);
    }

    /** Creates the Jewellery account if it is not there yet. */
    public View setUp() {
        Optional<Account> current = account();
        if (current.isPresent()) {
            return view(current.get());
        }
        if (accounts.findAll().stream().anyMatch(a -> a.getName().equalsIgnoreCase(ACCOUNT_NAME))) {
            throw new ValidationException("An account called '" + ACCOUNT_NAME + "' already exists. Rename or remove it first");
        }
        Account created = accountService.create(new AccountData(ACCOUNT_NAME, AccountType.JEWELLERY, null, null, BigDecimal.ZERO, null, null));
        log.info("Jewellery set up: account={}", created.getId());
        return view(created);
    }

    /**
     * Adds an item. Paid from a bank or cash account, its price moves in by an ordinary transfer; otherwise it is one you already own and
     * its price is added to the account's opening balance, so the net worth history counts it from the start.
     */
    public View add(ItemData data) {
        Account account = requireAccount();
        JewelleryItem item = new JewelleryItem();
        item.setAccountId(account.getId());
        fill(item, data);
        if (data.paidFromAccountId() != null) {
            Account from = requireBankOrCash(data.paidFromAccountId(), "the price was paid from");
            if (item.getBoughtPrice().signum() > 0) {
                balanceGuard.requireFunds(from.getId(), item.getBoughtPrice());
                item.setPurchaseTransactionId(saveTransfer(from.getId(), account.getId(), item.getBoughtPrice(), "Bought " + item.getName()).getId());
            }
            items.save(item);
        } else {
            items.save(item);
            shiftOpening(account, item.getBoughtPrice());
        }
        log.info("Jewellery item added: id={}", item.getId());
        return view(account);
    }

    /**
     * Corrects an item. A changed price keeps the balance equal to what the items cost: for a piece paid for from an account it
     * changes that transfer (so past net worth stays right), for one already owned it moves the opening balance.
     */
    public View update(Long id, ItemData data) {
        Account account = requireAccount();
        JewelleryItem item = requireItem(account, id);
        BigDecimal before = item.getBoughtPrice();
        fill(item, data);
        Optional<Transaction> purchase = purchaseOf(item);
        if (purchase.isPresent()) {
            // The payment also carries the cost of what was sold from this item, so it moves by the change, not to the new price.
            BigDecimal amount = purchase.get().getAmount().add(item.getBoughtPrice()).subtract(before);
            if (amount.signum() > 0) {
                purchase.get().setAmount(amount);
                transactions.save(purchase.get());
            } else {
                transactions.delete(purchase.get());
                item.setPurchaseTransactionId(null);
            }
        } else {
            shiftOpening(account, item.getBoughtPrice().subtract(before));
        }
        items.save(item);
        log.info("Jewellery item updated: id={}", id);
        return view(account);
    }

    /**
     * Sells an item, or part of its weight. What it cost (all of it, or its share by weight) moves from the Jewellery account to the
     * bank or cash account as a transfer, and the difference to the sale price is recorded there as income (a profit, filed under
     * Investment Income) or an expense (a loss, under Other Expense; when that category is deleted or deactivated, the entry has no category and a warning is logged). A sale of the whole weight removes the item.
     */
    public View sell(Long id, SaleRequest request) {
        Account account = requireAccount();
        JewelleryItem item = requireItem(account, id);
        Account to = requireBankOrCash(request.toAccountId(), "the money goes to");
        BigDecimal price = request.salePrice();
        if (price == null || price.signum() < 0) {
            throw new ValidationException("Enter the price it sold for");
        }
        BigDecimal sold = request.weight() == null ? item.getWeight() : request.weight();
        if (sold.signum() <= 0 || sold.compareTo(item.getWeight()) > 0) {
            throw new ValidationException("The weight sold must be more than 0 and at most " + plain(item.getWeight()) + " g");
        }
        boolean whole = sold.compareTo(item.getWeight()) == 0;
        BigDecimal cost = whole ? item.getBoughtPrice()
                : item.getBoughtPrice().multiply(sold).divide(item.getWeight(), 2, RoundingMode.HALF_UP);
        String name = item.getName();
        if (cost.signum() > 0) {
            saveTransfer(account.getId(), to.getId(), cost, "Sold " + name);
        }
        BigDecimal difference = price.subtract(cost);
        if (difference.signum() > 0) {
            saveGainOrLoss(TransactionKind.INCOME, to.getId(), difference, "Profit on selling " + name, incomeCategoryId());
        } else if (difference.signum() < 0) {
            saveGainOrLoss(TransactionKind.EXPENSE, to.getId(), difference.negate(), "Loss on selling " + name, expenseCategoryId());
        }
        if (whole) {
            items.delete(item);
        } else {
            item.setWeight(item.getWeight().subtract(sold));
            item.setBoughtPrice(item.getBoughtPrice().subtract(cost));
            // the purchase stays linked: it still carries the whole price, and the sale above took back its share
            items.save(item);
        }
        log.info("Jewellery item sold: id={} whole={}", id, whole);
        return view(account);
    }

    /**
     * Takes an item off the list (given away, or entered by mistake). A piece paid for from an account has that payment undone,
     * so the money goes back to the account; one already owned just comes off the opening balance. To record a loss, sell it.
     */
    public View remove(Long id) {
        Account account = requireAccount();
        JewelleryItem item = requireItem(account, id);
        Optional<Transaction> purchase = purchaseOf(item);
        if (purchase.isPresent()) {
            // Undo the payment for what is left; the share already sold came back by the sale's own transfer.
            BigDecimal amount = purchase.get().getAmount().subtract(item.getBoughtPrice());
            if (amount.signum() > 0) {
                purchase.get().setAmount(amount);
                transactions.save(purchase.get());
            } else {
                transactions.delete(purchase.get());
            }
        } else {
            shiftOpening(account, item.getBoughtPrice().negate());
        }
        items.delete(item);
        log.info("Jewellery item removed: id={}", id);
        return view(account);
    }

    // ------------------------------------------------------------------------------------------ restoring (Import from Excel)

    /** A transfer into or out of the Jewellery account as it was made, on its own day, with none of today's checks. Returns its id. */
    public Long restoreTransfer(Long fromAccountId, Long toAccountId, BigDecimal amount, LocalDate date, String note) {
        requireAccount();
        Transaction t = new Transaction();
        t.setKind(TransactionKind.TRANSFER);
        t.setFromAccountId(fromAccountId);
        t.setToAccountId(toAccountId);
        t.setAmount(amount);
        t.setDate(date);
        t.setNote(note);
        tagService.tag(t);
        return transactions.save(t).getId();
    }

    /** An item as it stands in the file (what is left of it after any sale), linked to the transfer that paid for it when there was one. */
    public void restoreItem(String metal, String name, String form, BigDecimal weight, BigDecimal boughtPrice, Long purchaseTransactionId) {
        Account account = requireAccount();
        JewelleryItem item = new JewelleryItem();
        item.setAccountId(account.getId());
        fill(item, new ItemData(metal, name, form, weight, boughtPrice, null));
        item.setPurchaseTransactionId(purchaseTransactionId);
        items.save(item);
    }

    /** The account's opening balance as the file has it (what the items already owned cost, before any sale of them). */
    public void restoreOpening(BigDecimal opening) {
        Account account = requireAccount();
        account.setOpeningBalance(opening);
        accounts.save(account);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Optional<Account> account() {
        return accounts.findAll().stream().filter(a -> a.getType() == AccountType.JEWELLERY).findFirst();
    }

    private Account requireAccount() {
        return account().orElseThrow(() -> new ValidationException("Set up Jewellery first"));
    }

    private JewelleryItem requireItem(Account account, Long id) {
        JewelleryItem item = items.findById(id == null ? -1L : id).orElseThrow(() -> new NotFoundException("Jewellery item " + id + " not found"));
        if (!account.getId().equals(item.getAccountId())) {
            throw new NotFoundException("Jewellery item " + id + " not found");
        }
        return item;
    }

    private void fill(JewelleryItem item, ItemData data) {
        String name = data.name() == null ? "" : data.name().trim();
        if (name.isEmpty()) {
            throw new ValidationException("Enter a name");
        }
        if (name.length() > 100) {
            throw new ValidationException("A name can be at most 100 characters");
        }
        item.setName(name);
        item.setMetal(cleanMetal(data.metal()));
        String form = data.form() == null ? "" : data.form().trim().toUpperCase();
        if (!FORMS.contains(form)) {
            throw new ValidationException("Choose a type: ornament, coin, bar, loose or other");
        }
        item.setForm(form);
        if (data.weight() == null || data.weight().signum() <= 0) {
            throw new ValidationException("Enter the weight in grams");
        }
        item.setWeight(data.weight().setScale(3, RoundingMode.HALF_UP));
        if (data.boughtPrice() == null || data.boughtPrice().signum() < 0) {
            throw new ValidationException("Enter the price paid");
        }
        item.setBoughtPrice(data.boughtPrice().setScale(2, RoundingMode.HALF_UP));
    }

    /** Gold and Silver are spelt one way whatever was typed; any other name is kept as typed. */
    private String cleanMetal(String metal) {
        String m = metal == null ? "" : metal.trim().replaceAll("\\s+", " ");
        if (m.isEmpty()) {
            throw new ValidationException("Choose Gold, Silver or type another kind");
        }
        if (m.length() > 40) {
            throw new ValidationException("A kind can be at most 40 characters");
        }
        if (m.equalsIgnoreCase("gold")) {
            return "Gold";
        }
        return m.equalsIgnoreCase("silver") ? "Silver" : m;
    }

    private void shiftOpening(Account account, BigDecimal change) {
        if (change.signum() != 0) {
            account.setOpeningBalance(account.getOpeningBalance().add(change));
            accounts.save(account);
        }
    }

    private Account requireBankOrCash(Long accountId, String role) {
        Account account = accounts.findById(accountId == null ? -1L : accountId)
                .orElseThrow(() -> new ValidationException("Choose the account " + role));
        if (!account.isActive() || (account.getType() != AccountType.BANK && account.getType() != AccountType.CASH)) {
            throw new ValidationException("Choose a bank or cash account");
        }
        if (!account.getCurrency().isBase()) {
            throw new ValidationException("Choose a bank or cash account in rupees");
        }
        return account;
    }

    private Optional<Transaction> purchaseOf(JewelleryItem item) {
        return item.getPurchaseTransactionId() == null ? Optional.empty() : transactions.findById(item.getPurchaseTransactionId());
    }

    private Transaction saveTransfer(Long from, Long to, BigDecimal amount, String note) {
        Transaction t = new Transaction();
        t.setKind(TransactionKind.TRANSFER);
        t.setFromAccountId(from);
        t.setToAccountId(to);
        t.setAmount(amount);
        t.setDate(LocalDate.now());
        t.setNote(note);
        tagService.tag(t);
        return transactions.save(t);
    }

    private void saveGainOrLoss(TransactionKind kind, Long accountId, BigDecimal amount, String note, Long categoryId) {
        Transaction t = new Transaction();
        t.setKind(kind);
        if (kind == TransactionKind.INCOME) {
            t.setToAccountId(accountId);
        } else {
            t.setFromAccountId(accountId);
        }
        t.setAmount(amount);
        t.setDate(LocalDate.now());
        t.setNote(note);
        t.setCategoryId(categoryId);
        tagService.tag(t);
        transactions.save(t);
    }

    private Long incomeCategoryId() {
        return categoryId(CategoryKind.INCOME, "Investment Income");
    }

    private Long expenseCategoryId() {
        return categoryId(CategoryKind.EXPENSE, "Other Expense");
    }

    private Long categoryId(CategoryKind kind, String name) {
        Long id = categories.findAll().stream()
                .filter(c -> c.isActive() && c.getParentId() == null && c.getKind() == kind && c.getName().equalsIgnoreCase(name))
                .findFirst().map(Category::getId).orElse(null);
        if (id == null) {
            // Deleted or switched off: the entry is kept without a category rather than put in one the user has retired.
            log.warn("Jewellery sale: no active {} category to file the gain or loss under; it is saved uncategorised", kind);
        }
        return id;
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private View view(Account account) {
        List<JewelleryItem> all = items.findAllByAccountIdOrderByIdAsc(account.getId());
        Map<String, List<ItemView>> byMetal = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing((JewelleryItem i) -> i.getName().toLowerCase()))
                .forEach(i -> byMetal.computeIfAbsent(i.getMetal(), k -> new ArrayList<>())
                        .add(new ItemView(i.getId(), i.getMetal(), i.getName(), i.getForm(), i.getWeight(), i.getBoughtPrice(), purchaseOf(i).isPresent())));
        List<MetalView> metals = byMetal.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<ItemView>> e) -> e.getKey().equals("Gold") ? 0 : e.getKey().equals("Silver") ? 1 : 2)
                        .thenComparing(e -> e.getKey().toLowerCase()))
                .map(e -> new MetalView(e.getKey(),
                        e.getValue().stream().map(ItemView::weight).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().stream().map(ItemView::boughtPrice).reduce(BigDecimal.ZERO, BigDecimal::add), e.getValue()))
                .toList();
        BigDecimal total = metals.stream().map(MetalView::boughtPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new View(true, account.getId(), account.getName(), total, metals);
    }
}
