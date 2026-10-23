package com.shiftrepair.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryLifecycleTest {
    private static final Instant START = Instant.parse("2026-10-31T03:00:00Z");
    private Shift shift() {
        return new Shift("shift-1", "store-1", new TimeWindow(START, START.plusSeconds(48 * 3600)), Set.of(), Optional.empty());
    }

    @Test
    void escalatedRecoveryCanReopenOrCancelButCannotBeFilledDirectly() {
        var open = RecoveryRequest.open("r1", shift(), "manager-1", START.minusSeconds(3600));
        var escalated = open.escalate();
        assertEquals(RecoveryRequest.Status.ESCALATED, escalated.status());
        assertThrows(IllegalStateException.class, () -> escalated.cover("a"));
        assertThrows(IllegalStateException.class, escalated::escalate);
        assertEquals(RecoveryRequest.Status.COVERED, escalated.reopen().cover("a").status());
        assertEquals(RecoveryRequest.Status.CANCELLED, escalated.cancel().status());
        assertThrows(IllegalStateException.class, open::reopen);
        assertThrows(IllegalStateException.class, () -> open.cancel().reopen());
        assertThrows(IllegalStateException.class, () -> open.cover("a").escalate());
    }

    @Test
    void backendOfferKeepsExactAbsoluteDurationAndCanCloseOnlyOnce() {
        var offer = ShiftOffer.forShift("offer-1", "employee-1", shift(), 82, START.minusSeconds(1), List.of("Reason"));
        assertEquals(48, offer.getDuration().toHours());
        assertEquals(LocalDate.of(2026, 11, 2), offer.getEndDate());
        assertTrue(offer.close());
        assertEquals(ShiftOffer.Status.CLOSED, offer.getStatus());
        assertFalse(offer.close());
        assertFalse(offer.expireIfNeeded(START));
        assertFalse(offer.respond(ShiftOffer.Status.ACCEPTED, START.minusSeconds(2)));
        assertEquals(0, offer.getRemainingSeconds(START.minusSeconds(2)));
    }

    @Test
    void acceptedOfferCannotBeClosedAndBackendScoreIsValidated() {
        var offer = ShiftOffer.forShift("offer-1", "employee-1", shift(), 82, START, List.of());
        assertTrue(offer.respond(ShiftOffer.Status.ACCEPTED, START.minusSeconds(1)));
        assertFalse(offer.close());
        assertEquals(ShiftOffer.Status.ACCEPTED, offer.getStatus());
        assertThrows(IllegalArgumentException.class, () -> ShiftOffer.forShift("id", "e1", shift(), 101, START, List.of()));
    }
}
