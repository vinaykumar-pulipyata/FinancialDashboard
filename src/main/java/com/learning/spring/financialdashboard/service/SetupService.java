package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.CreditCardDetail;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supports the first-time setup wizard: batch creation of accounts. */
@Service
@Transactional
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    /** One account chosen in the wizard. parentName refers to another account in the same batch. */
    public record SetupAccount(String name, AccountType type, Integer level, String parentName,
                               BigDecimal openingBalance, BigDecimal targetAmount,
                               BigDecimal creditLimit, String numberPlate, Integer yearBought, Integer dueDay,
                               BigDecimal emiAmount, Integer statementDay, String currency) {

        /** In rupees. */
        public SetupAccount(String name, AccountType type, Integer level, String parentName,
                            BigDecimal openingBalance, BigDecimal targetAmount,
                            BigDecimal creditLimit, String numberPlate, Integer yearBought, Integer dueDay,
                            BigDecimal emiAmount, Integer statementDay) {
            this(name, type, level, parentName, openingBalance, targetAmount, creditLimit, numberPlate, yearBought, dueDay, emiAmount, statementDay, null);
        }

        /** For accounts that have no card statement day. */
        public SetupAccount(String name, AccountType type, Integer level, String parentName,
                            BigDecimal openingBalance, BigDecimal targetAmount,
                            BigDecimal creditLimit, String numberPlate, Integer yearBought, Integer dueDay,
                            BigDecimal emiAmount) {
            this(name, type, level, parentName, openingBalance, targetAmount, creditLimit, numberPlate, yearBought, dueDay, emiAmount, null);
        }

        /** For accounts that are not a fixed loan (emiAmount is the monthly EMI of a fixed loan). */
        public SetupAccount(String name, AccountType type, Integer level, String parentName,
                            BigDecimal openingBalance, BigDecimal targetAmount,
                            BigDecimal creditLimit, String numberPlate, Integer yearBought, Integer dueDay) {
            this(name, type, level, parentName, openingBalance, targetAmount, creditLimit, numberPlate, yearBought, dueDay, null);
        }

        /** For accounts that have no card due day. */
        public SetupAccount(String name, AccountType type, Integer level, String parentName,
                            BigDecimal openingBalance, BigDecimal targetAmount,
                            BigDecimal creditLimit, String numberPlate, Integer yearBought) {
            this(name, type, level, parentName, openingBalance, targetAmount, creditLimit, numberPlate, yearBought, null);
        }

        /** For accounts that have no vehicle details. */
        public SetupAccount(String name, AccountType type, Integer level, String parentName,
                            BigDecimal openingBalance, BigDecimal targetAmount, BigDecimal creditLimit) {
            this(name, type, level, parentName, openingBalance, targetAmount, creditLimit, null, null);
        }
    }

    private final AccountService accountService;
    private final CreditCardDetailRepository creditCardDetails;
    private final VehicleService vehicleService;
    private final DebtService debtService;

    public SetupService(AccountService accountService, CreditCardDetailRepository creditCardDetails,
                        VehicleService vehicleService, DebtService debtService) {
        this.accountService = accountService;
        this.creditCardDetails = creditCardDetails;
        this.vehicleService = vehicleService;
        this.debtService = debtService;
    }

    /** Creates all accounts or none. Parents are created before their children. */
    public List<Account> createAll(List<SetupAccount> items) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("Choose at least one account");
        }
        items = items.stream().map(this::withDefaultName).toList();
        // As in the ordinary account form, a name only has to be unique under its own parent.
        Map<String, List<SetupAccount>> byName = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (SetupAccount item : items) {
            if (item.name() == null || item.name().isBlank()) {
                throw new ValidationException("Every account needs a name");
            }
            String parent = item.parentName() == null ? "" : item.parentName().trim().toLowerCase();
            if (!seen.add(parent + "\u0000" + item.name().trim().toLowerCase())) {
                throw new ValidationException("Duplicate account name: " + item.name());
            }
            byName.computeIfAbsent(item.name().trim().toLowerCase(), k -> new ArrayList<>()).add(item);
        }

        Map<SetupAccount, Account> created = new IdentityHashMap<>();
        List<Account> result = new ArrayList<>();
        for (SetupAccount item : items) {
            create(item, byName, created, result, 0);
        }
        log.info("Set-up created {} accounts", result.size());
        return result;
    }

    /** Receivables is a single header account, so it needs no name from the user. */
    private SetupAccount withDefaultName(SetupAccount item) {
        if (item.type() == AccountType.RECEIVABLE && (item.name() == null || item.name().isBlank())) {
            return new SetupAccount("Receivables", item.type(), item.level(), item.parentName(),
                    item.openingBalance(), item.targetAmount(), item.creditLimit(), null, null, item.dueDay(), null, item.statementDay());
        }
        return item;
    }

    private Account create(SetupAccount item, Map<String, List<SetupAccount>> byName,
                           Map<SetupAccount, Account> created, List<Account> result, int depth) {
        if (created.containsKey(item)) {
            return created.get(item);
        }
        if (depth > created.size() + byName.size() + 1) {
            throw new ValidationException("Parent accounts form a loop");
        }
        Long parentId = null;
        if (item.parentName() != null && !item.parentName().isBlank()) {
            List<SetupAccount> parents = byName.getOrDefault(item.parentName().trim().toLowerCase(), List.of());
            if (parents.isEmpty()) {
                throw new ValidationException("Parent '" + item.parentName() + "' is not in the list");
            }
            if (parents.size() > 1) {
                throw new ValidationException("More than one account is called '" + item.parentName() + "', so it cannot be a parent here");
            }
            parentId = create(parents.get(0), byName, created, result, depth + 1).getId();
        }
        Account account = accountService.create(new AccountData(item.name(), item.type(), item.level(),
                parentId, item.openingBalance(), item.targetAmount(), null, item.currency()));
        if (item.type() == AccountType.CREDIT_CARD) {
            if (item.creditLimit() == null || item.creditLimit().signum() <= 0) {
                throw new ValidationException("Credit limit is required for '" + item.name() + "'");
            }
            CreditCardService.checkDueDay(item.dueDay());
            CreditCardService.checkStatementDay(item.statementDay());
            creditCardDetails.save(new CreditCardDetail(account.getId(), item.creditLimit(), item.dueDay(), item.statementDay()));
        }
        if (item.type() == AccountType.DEBT && item.emiAmount() != null) {   // a fixed loan; without an EMI it is custom
            debtService.update(account.getId(), item.emiAmount());
        }
        if (item.type() == AccountType.VEHICLE && (item.numberPlate() != null || item.yearBought() != null)) {
            vehicleService.save(account.getId(), new VehicleService.VehicleData(item.numberPlate(), item.yearBought()));
        }
        created.put(item, account);
        result.add(account);
        return account;
    }
}
