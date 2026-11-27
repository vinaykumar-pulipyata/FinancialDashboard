package com.learning.spring.financialdashboard.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-object behaviour of the entities and enums: constructors, accessors and the enum rules. */
class ModelTest {

    @Test
    void aBucketEntryKeepsWhatItWasGiven() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        BucketEntry entry = new BucketEntry(7L, day, EntryKind.PAY, new BigDecimal("-50"), "Paid", 99L);

        assertEquals(7L, entry.getBucketId());
        assertEquals(day, entry.getDate());
        assertEquals(EntryKind.PAY, entry.getKind());
        assertEquals(new BigDecimal("-50"), entry.getAmount());
        assertEquals("Paid", entry.getNote());
        assertEquals(99L, entry.getTransactionId());
        assertNull(entry.getId());
    }

    @Test
    void aHoldingSnapshotCanBeCorrectedForTheSameDay() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        HoldingSnapshot snapshot = new HoldingSnapshot(1L, 2L, day, new BigDecimal("100"), new BigDecimal("120"));

        snapshot.setInvestedAmount(new BigDecimal("110"));
        snapshot.setCurrentValue(new BigDecimal("130"));

        assertEquals(1L, snapshot.getHoldingId());
        assertEquals(2L, snapshot.getAccountId());
        assertEquals(day, snapshot.getDate());
        assertEquals(new BigDecimal("110"), snapshot.getInvestedAmount());
        assertEquals(new BigDecimal("130"), snapshot.getCurrentValue());
        assertNull(snapshot.getId());
    }

    @Test
    void aRingFenceRemembersWhenItWasSet() {
        Instant before = Instant.now();
        RingFence fence = new RingFence(5L);

        assertEquals(5L, fence.getAccountId());
        assertFalse(fence.getFencedAt().isBefore(before));
    }

    @Test
    void aVehicleAndACardHoldTheirDetails() {
        VehicleDetail car = new VehicleDetail(3L, "KA01AB1234", 2020);
        car.setNumberPlate("KA02CD5678");
        car.setYearBought(2021);
        car.setValueUpdatedOn(LocalDate.of(2026, 9, 1));
        assertEquals(3L, car.getAccountId());
        assertEquals("KA02CD5678", car.getNumberPlate());
        assertEquals(2021, car.getYearBought());
        assertEquals(LocalDate.of(2026, 9, 1), car.getValueUpdatedOn());

        CreditCardDetail card = new CreditCardDetail(4L, new BigDecimal("50000"));
        card.setCreditLimit(new BigDecimal("75000"));
        assertEquals(4L, card.getAccountId());
        assertEquals(new BigDecimal("75000"), card.getCreditLimit());
    }

    @Test
    void aTransactionIsStampedWhenItIsCreated() {
        Transaction t = new Transaction();
        assertNotNull(t.getCreatedAt());
        assertNull(t.getId());
        assertNull(t.getFee());
    }

    @Test
    void accountTypesKnowTheirDefaultLevelAndWhetherTheyAreLiabilities() {
        assertEquals(1, AccountType.BANK.getDefaultLevel());
        assertEquals(6, AccountType.CREDIT_CARD.getDefaultLevel());
        assertTrue(AccountType.DEBT.isLiability());
        assertTrue(AccountType.CREDIT_CARD.isLiability());
        for (AccountType type : AccountType.values()) {
            assertEquals(type != AccountType.DEBT && type != AccountType.CREDIT_CARD, !type.isLiability());
            assertTrue(type.getDefaultLevel() >= 1 && type.getDefaultLevel() <= 7);
        }
    }

    @Test
    void portfolioKindsHaveLabels() {
        assertEquals("Mutual Funds", PortfolioKind.MUTUAL_FUNDS.getLabel());
        assertEquals("Shares", PortfolioKind.SHARES.getLabel());
        assertEquals(PortfolioKind.SHARES, PortfolioKind.valueOf("SHARES"));
    }

    @Test
    void anAccountSummaryCarriesTheCalculatedBalance() {
        Account account = new Account();
        account.setName("Bank");
        account.setType(AccountType.BANK);
        account.setLevel(1);
        account.setOpeningBalance(new BigDecimal("10"));

        AccountSummary summary = AccountSummary.of(account, new BigDecimal("42"), true);

        assertEquals("Bank", summary.name());
        assertEquals(new BigDecimal("42"), summary.balance());
        assertTrue(summary.ringFenced());
        assertTrue(summary.active());
    }

    @Test
    void aNoteStartsWithNothingButTimestamps() {
        Note note = new Note();
        note.setDate(LocalDate.of(2026, 10, 12));
        note.setTitle("Title");
        note.setBody("Body");
        note.setTags(java.util.List.of("b", "a"));
        Instant later = Instant.now().plusSeconds(5);
        note.setUpdatedAt(later);

        assertNull(note.getId());
        assertEquals(LocalDate.of(2026, 10, 12), note.getDate());
        assertEquals("Title", note.getTitle());
        assertEquals("Body", note.getBody());
        assertNotNull(note.getCreatedAt());
        assertEquals(later, note.getUpdatedAt());
        assertEquals(java.util.List.of("a", "b"), java.util.List.copyOf(note.getTags()));   // alphabetical
        note.setTags(java.util.List.of("c"));
        assertEquals(java.util.List.of("c"), java.util.List.copyOf(note.getTags()));         // replaced, not added to
    }

    @Test
    void anAppSettingKeepsItsKeyAndCanChangeItsValue() {
        AppSetting setting = new AppSetting("backup.copyDir", "/a");
        setting.setValue("/b");

        assertEquals("backup.copyDir", setting.getKey());
        assertEquals("/b", setting.getValue());
    }
}
