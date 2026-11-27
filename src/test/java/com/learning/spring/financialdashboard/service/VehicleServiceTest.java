package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.VehicleDetail;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.TransactionRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import com.learning.spring.financialdashboard.service.AccountService.AccountData;
import com.learning.spring.financialdashboard.service.SetupService.SetupAccount;
import com.learning.spring.financialdashboard.service.VehicleService.VehicleData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class VehicleServiceTest {

    @Autowired
    VehicleService service;

    @Autowired
    AccountService accountService;

    @Autowired
    SetupService setupService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    VehicleDetailRepository detailRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        wipe();
    }

    @AfterEach
    void cleanUp() {
        wipe();
    }

    private void wipe() {
        transactionRepository.deleteAll();
        detailRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private Account vehicle(String name) {
        return accountService.create(new AccountData(name, AccountType.VEHICLE, null, null,
                new BigDecimal("500000"), null, null));
    }

    @Test
    void savesThePlateAndTheYear() {
        Account car = vehicle("Honda City");

        VehicleDetail d = service.save(car.getId(), new VehicleData("mh12 ab 1234", 2019));

        assertEquals("MH12 AB 1234", d.getNumberPlate());
        assertEquals(2019, d.getYearBought());
        assertEquals(1, service.list().size());
    }

    @Test
    void savingAgainChangesTheSameRow() {
        Account car = vehicle("Honda City");
        service.save(car.getId(), new VehicleData("MH12AB1234", 2019));

        service.save(car.getId(), new VehicleData("  mh14   cd 9999 ", 2021));

        assertEquals(1, service.list().size());
        assertEquals("MH14 CD 9999", service.list().get(0).getNumberPlate());
        assertEquals(2021, service.list().get(0).getYearBought());
    }

    @Test
    void bothDetailsAreOptional() {
        Account bike = vehicle("Bicycle");

        VehicleDetail d = service.save(bike.getId(), new VehicleData("  ", null));

        assertNull(d.getNumberPlate());
        assertNull(d.getYearBought());
    }

    @Test
    void validatesTheYearAndThePlate() {
        Account car = vehicle("Honda City");

        assertThrows(ValidationException.class, () -> service.save(car.getId(), new VehicleData(null, 1899)));
        assertThrows(ValidationException.class, () -> service.save(car.getId(), new VehicleData(null, Year.now().getValue() + 1)));
        assertThrows(ValidationException.class, () -> service.save(car.getId(), new VehicleData("A".repeat(21), null)));
        service.save(car.getId(), new VehicleData(null, Year.now().getValue()));      // this year is fine
    }

    @Test
    void onlyVehiclesHaveTheseDetails() {
        Account bank = accountService.create(new AccountData("Bank", AccountType.BANK, null, null, BigDecimal.ZERO, null, null));

        assertThrows(ValidationException.class, () -> service.save(bank.getId(), new VehicleData("X", 2020)));
        assertThrows(RuntimeException.class, () -> service.save(-5L, new VehicleData("X", 2020)));
    }

    @Test
    void addingAVehicleThroughSetupKeepsItsDetails() {
        List<Account> made = setupService.createAll(List.of(new SetupAccount("Royal Enfield", AccountType.VEHICLE, null, null,
                new BigDecimal("120000"), null, null, "ka01 xy 4321", 2018)));

        VehicleDetail d = detailRepository.findById(made.get(0).getId()).orElseThrow();
        assertEquals("KA01 XY 4321", d.getNumberPlate());
        assertEquals(2018, d.getYearBought());
    }

    @Test
    void aVehicleAddedWithoutDetailsStillRemembersWhenItsValueWasSet() {
        List<Account> made = setupService.createAll(List.of(new SetupAccount("Scooter", AccountType.VEHICLE, null, null,
                new BigDecimal("40000"), null, null)));

        VehicleDetail d = detailRepository.findById(made.get(0).getId()).orElseThrow();
        assertNull(d.getNumberPlate());
        assertNull(d.getYearBought());
        assertEquals(LocalDate.now(), d.getValueUpdatedOn());
    }

    @Test
    void aNewVehicleHasItsValueDatedToday() {
        Account car = vehicle("Honda City");

        assertEquals(LocalDate.now(), detailRepository.findById(car.getId()).orElseThrow().getValueUpdatedOn());
    }

    private void backdate(Long accountId, LocalDate day) {
        VehicleDetail d = detailRepository.findById(accountId).orElseThrow();
        d.setValueUpdatedOn(day);
        detailRepository.save(d);
    }

    private AccountData dataFor(Account a, String opening) {
        return new AccountData(a.getName(), a.getType(), a.getLevel(), a.getParentId(), new BigDecimal(opening), null, null);
    }

    @Test
    void changingTheValueRestartsTheClock() {
        Account car = vehicle("Honda City");
        backdate(car.getId(), LocalDate.now().minusYears(2));

        accountService.update(car.getId(), dataFor(car, "420000"));

        assertEquals(LocalDate.now(), detailRepository.findById(car.getId()).orElseThrow().getValueUpdatedOn());
    }

    @Test
    void editingOtherThingsLeavesTheDateAlone() {
        Account car = vehicle("Honda City");
        LocalDate old = LocalDate.now().minusMonths(8);
        backdate(car.getId(), old);

        accountService.update(car.getId(), dataFor(car, "500000"));                       // same value, as when only the name changes
        service.save(car.getId(), new VehicleData("MH12AB1234", 2019));                   // plate and year

        assertEquals(old, detailRepository.findById(car.getId()).orElseThrow().getValueUpdatedOn());
    }

    @Test
    void otherAccountsGetNoVehicleRow() {
        accountService.create(new AccountData("Bank", AccountType.BANK, null, null, BigDecimal.ZERO, null, null));

        assertEquals(0, detailRepository.count());
    }

    @Test
    void deletingTheAccountRemovesItsDetails() {
        Account car = vehicle("Honda City");
        service.save(car.getId(), new VehicleData("MH12AB1234", 2019));

        accountService.delete(car.getId());

        assertEquals(0, detailRepository.count());
    }

    @Test
    void theBrowserCanSendASetupAccountWithOrWithoutVehicleDetails() {
        JsonMapper json = JsonMapper.builder().build();

        SetupAccount plain = json.readValue(
                "{\"name\":\"Bank\",\"type\":\"BANK\",\"openingBalance\":100,\"creditLimit\":null,\"targetAmount\":null}", SetupAccount.class);
        SetupAccount car = json.readValue(
                "{\"name\":\"Car\",\"type\":\"VEHICLE\",\"openingBalance\":5,\"numberPlate\":\"MH 1\",\"yearBought\":2020}", SetupAccount.class);

        assertEquals("Bank", plain.name());
        assertNull(plain.numberPlate());
        assertNull(plain.yearBought());
        assertEquals("MH 1", car.numberPlate());
        assertEquals(2020, car.yearBought());
    }
}
