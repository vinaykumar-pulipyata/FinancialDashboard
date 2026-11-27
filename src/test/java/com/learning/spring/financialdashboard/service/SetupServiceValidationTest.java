package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.CreditCardDetailRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The batch endpoint's rules: what is required, parents, cards and vehicles. */
@SpringBootTest
class SetupServiceValidationTest {

    @Autowired
    SetupService service;
    @Autowired
    AccountRepository accounts;
    @Autowired
    CreditCardDetailRepository cards;
    @Autowired
    VehicleDetailRepository vehicles;

    @BeforeEach
    @AfterEach
    void clean() {
        cards.deleteAll();
        vehicles.deleteAll();
        accounts.deleteAll();
    }

    private SetupAccount item(String name, AccountType type, String parent, String limit, String plate, Integer year) {
        return new SetupAccount(name, type, null, parent, new BigDecimal("100"), null,
                limit == null ? null : new BigDecimal(limit), plate, year);
    }

    @Test
    void somethingMustBeChosenAndEveryAccountNeedsADistinctName() {
        assertThrows(ValidationException.class, () -> service.createAll(null));
        assertThrows(ValidationException.class, () -> service.createAll(List.of()));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(item("  ", AccountType.BANK, null, null, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(item(null, AccountType.BANK, null, null, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(Arrays.asList(
                item("Bank", AccountType.BANK, null, null, null, null), item("bank", AccountType.CASH, null, null, null, null))));
    }

    @Test
    void parentsAreCreatedFirstAndMustBeInTheList() {
        List<Account> made = service.createAll(List.of(
                item("Child", AccountType.BANK, "Parent", null, null, null), item("Parent", AccountType.BANK, null, null, null, null)));
        assertEquals(2, made.size());
        Long parentId = made.stream().filter(a -> a.getName().equals("Parent")).findFirst().orElseThrow().getId();
        assertEquals(parentId, made.stream().filter(a -> a.getName().equals("Child")).findFirst().orElseThrow().getParentId());

        assertThrows(ValidationException.class, () -> service.createAll(List.of(item("Lost", AccountType.BANK, "Nowhere", null, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("A", AccountType.BANK, "B", null, null, null), item("B", AccountType.BANK, "A", null, null, null))));
    }

    @Test
    void receivablesNeedNoNameAndCardsNeedALimit() {
        List<Account> made = service.createAll(List.of(item(null, AccountType.RECEIVABLE, null, null, null, null)));
        assertEquals("Receivables", made.get(0).getName());

        assertThrows(ValidationException.class, () -> service.createAll(List.of(item("Card", AccountType.CREDIT_CARD, null, null, null, null))));
        assertThrows(ValidationException.class, () -> service.createAll(List.of(item("Card", AccountType.CREDIT_CARD, null, "0", null, null))));
        Account card = service.createAll(List.of(item("Card", AccountType.CREDIT_CARD, null, "5000", null, null))).get(0);
        assertTrue(cards.existsById(card.getId()));
    }

    @Test
    void vehiclesKeepTheirPlateAndYearButNeedNeitherAndEverythingIsAllOrNothing() {
        Account withPlate = service.createAll(List.of(item("Car", AccountType.VEHICLE, null, null, "KA01AB1234", 2020))).get(0);
        assertEquals("KA01AB1234", vehicles.findById(withPlate.getId()).orElseThrow().getNumberPlate());
        assertEquals(2020, vehicles.findById(withPlate.getId()).orElseThrow().getYearBought());
        Account bare = service.createAll(List.of(item("Bike", AccountType.VEHICLE, null, null, null, null))).get(0);
        assertNull(vehicles.findById(bare.getId()).map(v -> v.getNumberPlate()).orElse(null));

        long before = accounts.count();
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("Fine", AccountType.BANK, null, null, null, null), item("Bad card", AccountType.CREDIT_CARD, null, null, null, null))));
        assertEquals(before, accounts.count());
    }

    @Test
    void aCardCanComeWithItsDueDayAndABadOneStopsTheWholeBatch() {
        Account card = service.createAll(List.of(new SetupAccount("Card", AccountType.CREDIT_CARD, null, null,
                new BigDecimal("100"), null, new BigDecimal("5000"), null, null, 21))).get(0);
        assertEquals(21, cards.findById(card.getId()).orElseThrow().getDueDay());
        assertNull(cards.findById(service.createAll(List.of(item("Plain", AccountType.CREDIT_CARD, null, "5000", null, null))).get(0).getId())
                .orElseThrow().getDueDay(), "no due day unless one is given");

        long before = accounts.count();
        assertThrows(ValidationException.class, () -> service.createAll(List.of(
                item("Fine", AccountType.BANK, null, null, null, null),
                new SetupAccount("Bad", AccountType.CREDIT_CARD, null, null, new BigDecimal("100"), null, new BigDecimal("5000"), null, null, 40))));
        assertEquals(before, accounts.count());
    }
}
