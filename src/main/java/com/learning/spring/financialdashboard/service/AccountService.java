package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountSummary;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.Currency;
import com.learning.spring.financialdashboard.model.Transaction;
import com.learning.spring.financialdashboard.model.TransactionKind;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.model.RingFence;
import com.learning.spring.financialdashboard.model.Bucket;
import com.learning.spring.financialdashboard.repository.BucketEntryRepository;
import com.learning.spring.financialdashboard.repository.DebtDetailRepository;
import com.learning.spring.financialdashboard.repository.BucketRepository;
import com.learning.spring.financialdashboard.repository.EmergencyFundDetailRepository;
import com.learning.spring.financialdashboard.model.Holding;
import com.learning.spring.financialdashboard.model.VehicleDetail;
import com.learning.spring.financialdashboard.repository.HoldingRepository;
import com.learning.spring.financialdashboard.repository.HoldingSnapshotRepository;
import com.learning.spring.financialdashboard.repository.LedgerDetailRepository;
import com.learning.spring.financialdashboard.repository.JewelleryItemRepository;
import com.learning.spring.financialdashboard.repository.LoanWriteOffRepository;
import com.learning.spring.financialdashboard.repository.SplitSettlementRepository;
import com.learning.spring.financialdashboard.repository.PortfolioDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositDetailRepository;
import com.learning.spring.financialdashboard.repository.RecurringDepositRepository;
import com.learning.spring.financialdashboard.repository.RingFenceRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final TransactionRepository transactions;
    private final EmergencyFundDetailRepository emergencyFunds;
    private final RingFenceRepository ringFences;
    private final SystemCategories systemCategories;
    private final LedgerDetailRepository ledgers;
    private final BucketRepository ledgerBuckets;
    private final BucketEntryRepository ledgerEntries;
    private final PortfolioDetailRepository portfolios;
    private final RecurringDepositDetailRepository recurringDepositDetails;
    private final RecurringDepositRepository recurringDeposits;
    private final HoldingRepository holdings;
    private final HoldingSnapshotRepository holdingSnapshots;
    private final VehicleDetailRepository vehicleDetails;
    private final DebtDetailRepository debtDetails;
    private final JewelleryItemRepository jewelleryItems;
    private final ExchangeRateService exchangeRates;
    private final TransactionTagService tagService;
    private final LoanWriteOffRepository loanWriteOffs;
    private final SplitSettlementRepository splitSettlements;

    public AccountService(AccountRepository repository, TransactionRepository transactions,
                          EmergencyFundDetailRepository emergencyFunds, RingFenceRepository ringFences,
                          SystemCategories systemCategories, LedgerDetailRepository ledgers,
                          BucketRepository ledgerBuckets, BucketEntryRepository ledgerEntries,
                          PortfolioDetailRepository portfolios, HoldingRepository holdings,
                          HoldingSnapshotRepository holdingSnapshots, VehicleDetailRepository vehicleDetails,
                          DebtDetailRepository debtDetails, JewelleryItemRepository jewelleryItems,
                          ExchangeRateService exchangeRates, RecurringDepositDetailRepository recurringDepositDetails,
                          RecurringDepositRepository recurringDeposits, TransactionTagService tagService, LoanWriteOffRepository loanWriteOffs,
                          SplitSettlementRepository splitSettlements) {
        this.splitSettlements = splitSettlements;
        this.repository = repository;
        this.transactions = transactions;
        this.emergencyFunds = emergencyFunds;
        this.ringFences = ringFences;
        this.systemCategories = systemCategories;
        this.ledgers = ledgers;
        this.ledgerBuckets = ledgerBuckets;
        this.ledgerEntries = ledgerEntries;
        this.portfolios = portfolios;
        this.holdings = holdings;
        this.holdingSnapshots = holdingSnapshots;
        this.vehicleDetails = vehicleDetails;
        this.debtDetails = debtDetails;
        this.jewelleryItems = jewelleryItems;
        this.exchangeRates = exchangeRates;
        this.recurringDepositDetails = recurringDepositDetails;
        this.recurringDeposits = recurringDeposits;
        this.tagService = tagService;
        this.loanWriteOffs = loanWriteOffs;
    }

    /** Fields a caller can set. A null level falls back to the type's default, a null currency means rupees. */
    public record AccountData(String name, AccountType type, Integer level, Long parentId,
                              BigDecimal openingBalance, BigDecimal targetAmount, String notes, String currency) {

        /** An account in rupees. */
        public AccountData(String name, AccountType type, Integer level, Long parentId,
                           BigDecimal openingBalance, BigDecimal targetAmount, String notes) {
            this(name, type, level, parentId, openingBalance, targetAmount, notes, null);
        }
    }

    public Account create(AccountData data) {
        requireOnlyOne(data.type());
        requireCurrencyAllowed(new Account(), data);
        Account account = new Account();
        apply(account, data);
        Account saved = repository.save(account);
        if (saved.getType() == AccountType.VEHICLE) {
            markVehicleValueUpdated(saved.getId());
        }
        log.info("Account created: id={} type={} level={}", saved.getId(), saved.getType(), saved.getLevel());
        return saved;
    }

    public Account update(Long id, AccountData data) {
        Account account = get(id);
        requireActive(account);
        BigDecimal before = account.getOpeningBalance();
        requireManagedFieldsUnchanged(account, data);
        requireTypeChangeAllowed(account, data.type());
        requireCurrencyAllowed(account, data);
        apply(account, data);
        Account saved = repository.save(account);
        if (saved.getType() == AccountType.VEHICLE && before.compareTo(saved.getOpeningBalance()) != 0) {
            markVehicleValueUpdated(saved.getId());       // a vehicle's value is its amount, so this is a new valuation
        }
        log.info("Account updated: id={} type={}", saved.getId(), saved.getType());
        return saved;
    }

    /** Types that are one of a kind: Receivables is a single header account, and there is one Jewellery account for all the items. */
    private static final Set<AccountType> ONLY_ONE = Set.of(AccountType.RECEIVABLE, AccountType.JEWELLERY);

    /** Types that need details an ordinary account does not have (a card's limit, the Receivables total, the items), so they are only created as such. */
    private static final Set<AccountType> NEEDS_DETAILS = Set.of(AccountType.CREDIT_CARD, AccountType.RECEIVABLE, AccountType.JEWELLERY);

    private void requireOnlyOne(AccountType type) {
        if (type != null && ONLY_ONE.contains(type) && repository.existsByType(type)) {
            throw new ValidationException("There is already a " + (type == AccountType.RECEIVABLE ? "Receivables" : "Jewellery")
                    + " account, and there can only be one");
        }
    }

    /**
     * A type change must not leave an account without what its type needs, or turn its whole history round. Refused: changing to or from
     * Receivables, Jewellery or Credit Card (they are created as such), between an asset and a debt (what you owe would become what you
     * have, and net worth would move by twice the amount), and any change for a goal fund that is the Emergency Fund or ring-fenced.
     */
    private void requireTypeChangeAllowed(Account account, AccountType newType) {
        AccountType oldType = account.getType();
        if (newType == null || newType == oldType) {
            return;
        }
        if (NEEDS_DETAILS.contains(newType)) {
            throw new ValidationException("A " + typeLabel(newType) + " account needs its own details, so it cannot be made from another type. Add a new account instead");
        }
        if (NEEDS_DETAILS.contains(oldType)) {
            throw new ValidationException("A " + typeLabel(oldType) + " account cannot be changed to another type. Add a new account instead");
        }
        if (oldType.isLiability() != newType.isLiability()) {
            throw new ValidationException("An account cannot change between something you have and something you owe: its whole history would turn round. Add a new account instead");
        }
        if (emergencyFunds.existsById(account.getId()) || ringFences.existsById(account.getId())) {
            throw new ValidationException("This goal fund has Emergency Fund or ring-fence settings, so its type cannot be changed");
        }
    }

    /** The currency asked for; an update that leaves it out keeps the account's own, a new account without one is in rupees. */
    private static Currency currencyOf(Account account, AccountData data) {
        return data.currency() == null && account.getId() != null ? account.getCurrency() : Currency.parse(data.currency());
    }

    /** Types that only make sense in rupees: their own screens (goal targets, Receivables, Jewellery, loan and card payments and bills) have no currency yet. */
    private static final Set<AccountType> RUPEES_ONLY = Set.of(AccountType.RECEIVABLE, AccountType.JEWELLERY, AccountType.GOAL_FUND,
            AccountType.DEBT, AccountType.CREDIT_CARD);

    /**
     * A foreign currency is for holding an account (a bank, cash, wallet, investment, property, vehicle or other account),
     * not for the walled-off ones. It needs an exchange rate, the amount must fit the currency's decimals, and it cannot change once the
     * account has transactions (their amounts would turn into another currency).
     */
    private void requireCurrencyAllowed(Account account, AccountData data) {
        Currency currency = currencyOf(account, data);
        if (account.getId() != null && currency != account.getCurrency()) {
            if (transactions.existsByFromAccountIdOrToAccountId(account.getId(), account.getId())) {
                throw new ValidationException("The currency cannot be changed once the account has transactions. Add a new account instead");
            }
        }
        if (currency.isBase()) {
            return;
        }
        if (data.type() == null || RUPEES_ONLY.contains(data.type())) {
            throw new ValidationException("This kind of account can only be in rupees");
        }
        if (account.getId() != null && (ledgers.existsById(account.getId()) || portfolios.existsById(account.getId()) || recurringDepositDetails.existsById(account.getId()))) {
            throw new ValidationException("The Ledger, Mutual Funds, Shares and Recurring Deposits can only be in rupees");
        }
        exchangeRates.requireRate(currency);
        currency.requireScale(data.openingBalance(), "The balance");
        currency.requireScale(data.targetAmount(), "The target");
    }

    private static String typeLabel(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "Credit Card";
            case RECEIVABLE -> "Receivables";
            default -> "Jewellery";
        };
    }

    /**
     * The Ledger, Mutual Funds, Shares and Jewellery accounts get their balance from their own buckets, holdings or items, so
     * the type and the opening balance cannot be edited here (a different opening balance would stop the balance matching
     * what is inside). Their name, notes and parent can.
     */
    private void requireManagedFieldsUnchanged(Account account, AccountData data) {
        boolean managed = ledgers.existsById(account.getId()) || portfolios.existsById(account.getId()) || recurringDepositDetails.existsById(account.getId())
                || account.getType() == AccountType.JEWELLERY;
        if (!managed) {
            return;
        }
        BigDecimal opening = data.openingBalance() != null ? data.openingBalance() : BigDecimal.ZERO;
        if (data.type() != account.getType() || opening.compareTo(account.getOpeningBalance()) != 0) {
            throw new ValidationException("'" + account.getName() + "' is kept up to date through its own buttons, "
                    + "so only its name and notes can be changed here");
        }
    }

    /** Remembers that a vehicle's value was set today, so the page can remind you when it has gone stale. */
    private void markVehicleValueUpdated(Long accountId) {
        VehicleDetail detail = vehicleDetails.findById(accountId).orElseGet(() -> new VehicleDetail(accountId, null, null));
        detail.setValueUpdatedOn(LocalDate.now());
        vehicleDetails.save(detail);
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return repository.findAllByOrderByLevelAscNameAsc();
    }

    /**
     * Accounts with calculated balances. An asset is opening + money in - money out. A liability is the amount
     * owed: opening + money out - money in, so spending on a card raises it and paying the card lowers it.
     */
    @Transactional(readOnly = true)
    public List<AccountSummary> listSummaries() {
        Map<Long, BigDecimal> in = totals(transactions.sumIncomingByAccount());
        Map<Long, BigDecimal> out = totals(transactions.sumOutgoingByAccount());
        Set<Long> fenced = ringFences.findAll().stream().map(RingFence::getAccountId).collect(Collectors.toSet());
        List<Account> all = repository.findAllByOrderByLevelAscNameAsc();
        // Money lent and collected is not posted to the Receivables account itself: its balance is worked out here.
        Long receivablesId = all.stream().filter(a -> a.getType() == AccountType.RECEIVABLE && a.isActive())
                .map(Account::getId).findFirst().orElse(null);
        BigDecimal letGo = loanWriteOffs.sumAll() == null ? BigDecimal.ZERO : loanWriteOffs.sumAll();   // debts people owed you that you let go of
        BigDecimal shared = splitSettlements.sumSharesAll() == null ? BigDecimal.ZERO : splitSettlements.sumSharesAll();   // what people owe of expenses split with them
        BigDecimal paidBack = splitSettlements.sumRepaidAll() == null ? BigDecimal.ZERO : splitSettlements.sumRepaidAll();   // paying back what you owe is not lending
        BigDecimal setOff = splitSettlements.sumOffsetAll() == null ? BigDecimal.ZERO : splitSettlements.sumOffsetAll();   // shares cancelled against bills: both sides come down
        BigDecimal paidForYou = splitSettlements.sumPaidForYouAll() == null ? BigDecimal.ZERO : splitSettlements.sumPaidForYouAll();
        BigDecimal youOwe = paidForYou.subtract(paidBack).subtract(setOff);   // bills people paid for you that you have not paid back: Receivables is the balance, net of them
        BigDecimal loansNet = loanTotal(SystemCategories.LOANS_GIVEN).subtract(paidBack).add(shared).subtract(setOff).subtract(youOwe).subtract(loanTotal(SystemCategories.LOANS_COLLECTED)).subtract(letGo);
        // Mutual Funds and Shares accounts count at the sum of their holdings' invested amounts (what the holdings you have cost); the market value is
        // shown beside it on the cards and the Investments tile, not counted in the totals or in net worth.
        Set<Long> portfolioIds = portfolios.findAll().stream().map(p -> p.getAccountId()).collect(Collectors.toSet());
        Map<Long, BigDecimal> held = new HashMap<>();
        for (Holding h : holdings.findAll()) {
            held.merge(h.getAccountId(), h.getInvestedAmount(), BigDecimal::add);
        }
        ExchangeRateService.Table rates = exchangeRates.table();
        LocalDate today = LocalDate.now();
        return all.stream().map(a -> {
            if (portfolioIds.contains(a.getId())) {
                return AccountSummary.of(a, held.getOrDefault(a.getId(), BigDecimal.ZERO), fenced.contains(a.getId()));
            }
            BigDecimal moneyIn = in.getOrDefault(a.getId(), BigDecimal.ZERO);
            BigDecimal moneyOut = out.getOrDefault(a.getId(), BigDecimal.ZERO);
            BigDecimal net = a.getType().isLiability() ? moneyOut.subtract(moneyIn) : moneyIn.subtract(moneyOut);
            if (a.getId().equals(receivablesId)) {
                net = net.add(loansNet);
            }
            BigDecimal balance = a.getOpeningBalance().add(net);
            return AccountSummary.of(a, balance, fenced.contains(a.getId()), rates.toRupees(balance, a.getCurrency(), today));
        }).toList();
    }

    private BigDecimal loanTotal(String categoryKey) {
        return systemCategories.find(categoryKey)
                .map(c -> transactions.sumForCategory(c.getId())).filter(total -> total != null).orElse(BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> totals(List<Object[]> rows) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    public Account deactivate(Long id) {
        Account account = get(id);
        account.setActive(false);
        log.info("Account deactivated: id={}", id);
        return repository.save(account);
    }

    /** A deactivated account allows nothing until it is activated again. */
    private void requireActive(Account account) {
        if (!account.isActive()) {
            throw new ValidationException("'" + account.getName() + "' is inactive. Activate it first");
        }
    }

    /**
     * Brings an account's balance to zero with one dated transaction in the fixed category Balance Adjustment, and the note says what
     * happened to the money (it is required). An account whose money is still in it loses it from its balance: an expense for an asset
     * with money in it, an income for one that is overdrawn; for a loan or card, an income (money in lowers what is owed) when something
     * is owed, an expense when it was overpaid. Only an active account, and only one whose balance comes from its transactions.
     */
    /**
     * A Reset balance (or a Write off) as it was made on an earlier day, from an exported file (Import from Excel): the same transaction in the
     * fixed Balance Adjustment category, on its own day, for the amount the file says. None of today's checks apply; the account only has to exist.
     */
    public Transaction restoreAdjustment(Long id, TransactionKind kind, BigDecimal amount, LocalDate date, String note) {
        get(id);
        if (kind != TransactionKind.INCOME && kind != TransactionKind.EXPENSE) {
            throw new ValidationException("A balance adjustment is income or an expense");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("A balance adjustment has to be for an amount");
        }
        if (date == null || date.isAfter(LocalDate.now())) {
            throw new ValidationException("The balance adjustment needs a day that is not in the future");
        }
        Transaction t = new Transaction();
        t.setKind(kind);
        if (kind == TransactionKind.INCOME) {
            t.setToAccountId(id);
        } else {
            t.setFromAccountId(id);
        }
        t.setAmount(amount);
        t.setDate(date);
        t.setNote(note == null || note.isBlank() ? null : note.trim());
        t.setCategoryId(systemCategories.require(kind == TransactionKind.INCOME ? SystemCategories.BALANCE_ADJUSTMENT_INCOME
                : SystemCategories.BALANCE_ADJUSTMENT_EXPENSE).getId());
        tagService.tag(t);
        Transaction saved = transactions.save(t);
        log.info("Balance adjustment restored: account={} kind={} date={}", id, kind, date);
        return saved;
    }

    public Transaction resetBalance(Long id, String note) {
        Account account = get(id);
        requireActive(account);
        boolean managed = ledgers.existsById(id) || portfolios.existsById(id) || recurringDepositDetails.existsById(id)
                || account.getType() == AccountType.JEWELLERY || account.getType() == AccountType.RECEIVABLE;
        if (managed) {
            throw new ValidationException("'" + account.getName() + "' is kept up to date through its own buttons, so its balance cannot be reset here");
        }
        if (ringFences.existsById(id)) {
            throw new ValidationException("'" + account.getName() + "' is ring-fenced. Release it first");
        }
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            throw new ValidationException("Say what happened to the money");
        }
        if (reason.length() > 255) {
            throw new ValidationException("The note can have at most 255 characters");
        }
        BigDecimal balance = listSummaries().stream().filter(a -> a.id().equals(id)).map(AccountSummary::balance).findFirst()
                .orElse(account.getOpeningBalance());
        if (balance.signum() == 0) {
            throw new ValidationException("The balance of '" + account.getName() + "' is already zero");
        }
        boolean liability = account.getType().isLiability();
        boolean moneyIn = liability == (balance.signum() > 0);   // an asset with money in it, or a debt with something owed: money comes off the balance
        Transaction t = new Transaction();
        // an asset holding money loses it (expense), an overdrawn one gains it (income); a liability is the other way round
        TransactionKind kind = moneyIn ? TransactionKind.INCOME : TransactionKind.EXPENSE;
        t.setKind(kind);
        if (kind == TransactionKind.INCOME) {
            t.setToAccountId(id);
        } else {
            t.setFromAccountId(id);
        }
        t.setAmount(balance.abs());
        t.setDate(LocalDate.now());
        t.setNote(reason);
        t.setCategoryId(systemCategories.require(kind == TransactionKind.INCOME ? SystemCategories.BALANCE_ADJUSTMENT_INCOME
                : SystemCategories.BALANCE_ADJUSTMENT_EXPENSE).getId());
        tagService.tag(t);
        Transaction saved = transactions.save(t);
        log.info("Balance reset: account={} kind={} date={}", id, kind, saved.getDate());
        return saved;
    }

    public Account activate(Long id) {
        Account account = get(id);
        account.setActive(true);
        log.info("Account activated: id={}", id);
        return repository.save(account);
    }

    public void delete(Long id) {
        Account account = get(id);
        if (repository.existsByParentId(id)) {
            throw new ValidationException("Account has child accounts. Delete or move them first");
        }
        if (transactions.existsByFromAccountIdOrToAccountId(id, id)) {
            throw new ValidationException("Account has transactions. Deactivate it instead");
        }
        emergencyFunds.findById(id).ifPresent(emergencyFunds::delete);
        ringFences.findById(id).ifPresent(ringFences::delete);
        vehicleDetails.findById(id).ifPresent(vehicleDetails::delete);
        debtDetails.findById(id).ifPresent(debtDetails::delete);
        if (ledgers.existsById(id)) {   // a Ledger with no transactions: its buckets and their history go with it
            for (Bucket bucket : ledgerBuckets.findAll()) {
                ledgerEntries.deleteByBucketId(bucket.getId());
            }
            ledgerBuckets.deleteAll();
            ledgers.deleteById(id);
        }
        if (portfolios.existsById(id)) {   // a Mutual Funds or Shares account with no transactions: its holdings go too
            holdingSnapshots.deleteByAccountId(id);
            holdings.deleteByAccountId(id);
            portfolios.deleteById(id);
        }
        if (recurringDepositDetails.existsById(id)) {   // a Recurring Deposits account with no transactions: its (empty) deposits go too
            recurringDeposits.deleteByAccountId(id);
            recurringDepositDetails.deleteById(id);
        }
        jewelleryItems.deleteByAccountId(id);     // a Jewellery account with no transactions: its items go too
        repository.delete(account);
        log.info("Account deleted: id={} type={}", id, account.getType());
    }

    private void apply(Account account, AccountData data) {
        if (data.type() == null) {
            throw new ValidationException("Type is required");
        }
        boolean blank = data.name() == null || data.name().isBlank();
        if (blank && data.type() != AccountType.RECEIVABLE) {
            throw new ValidationException("Name is required");
        }
        // Receivables is a single header account, so it needs no name.
        String name = blank ? "Receivables" : data.name().trim();
        validateParent(account, data.parentId());
        validateUniqueName(account, name, data.parentId());

        account.setName(name);
        account.setType(data.type());
        account.setLevel(data.level() != null ? data.level() : data.type().getDefaultLevel());
        account.setParentId(data.parentId());
        account.setOpeningBalance(data.openingBalance() != null ? data.openingBalance() : BigDecimal.ZERO);
        account.setTargetAmount(data.targetAmount());
        account.setNotes(data.notes());
        account.setCurrency(currencyOf(account, data));
    }

    private void validateParent(Account account, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(account.getId())) {
            throw new ValidationException("An account cannot be its own parent");
        }
        Long current = parentId;
        while (current != null) {
            Account ancestor = repository.findById(current)
                    .orElseThrow(() -> new ValidationException("Parent account not found"));
            if (account.getId() != null && account.getId().equals(ancestor.getParentId())) {
                throw new ValidationException("Parent would create a loop");
            }
            current = ancestor.getParentId();
        }
    }

    private void validateUniqueName(Account account, String name, Long parentId) {
        boolean sameAsSelf = account.getId() != null
                && name.equalsIgnoreCase(account.getName())
                && java.util.Objects.equals(parentId, account.getParentId());
        if (sameAsSelf) {
            return;
        }
        boolean exists = parentId == null
                ? repository.existsByNameIgnoreCaseAndParentIdIsNull(name)
                : repository.existsByNameIgnoreCaseAndParentId(name, parentId);
        if (exists) {
            throw new ValidationException("An account named '" + name + "' already exists here");
        }
    }
}
