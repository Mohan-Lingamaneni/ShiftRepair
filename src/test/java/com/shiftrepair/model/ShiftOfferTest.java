package com.shiftrepair.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the existing UI's shared offer model. */
class ShiftOfferTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-23T20:00:00Z");

    private ShiftOffer offer(LocalTime start, LocalTime end, List<String> reasons) {
        return new ShiftOffer("offer-1", "Jordan", "Sarah", "Cashier", "Store",
                LocalDate.of(2026, 10, 23), start, end, 92, DEADLINE, reasons);
    }

    private ShiftOffer offer() {
        return offer(LocalTime.of(16, 0), LocalTime.of(22, 0), List.of());
    }

    @Test
    void responseIsRecordedOnlyOnce() {
        var offer = offer();
        assertTrue(offer.respond(ShiftOffer.Status.ACCEPTED, DEADLINE.minusSeconds(1)));
        assertFalse(offer.respond(ShiftOffer.Status.DECLINED, DEADLINE.minusSeconds(1)));
        assertFalse(offer.expireIfNeeded(DEADLINE));
        assertEquals(ShiftOffer.Status.ACCEPTED, offer.getStatus());
        assertEquals(0, offer.getRemainingSeconds(DEADLINE.minusSeconds(1)));
    }

    @Test
    void declineIsTerminalAndInvalidResponsesAreRejected() {
        var offer = offer();
        assertThrows(IllegalArgumentException.class, () -> offer.respond(ShiftOffer.Status.PENDING, DEADLINE));
        assertThrows(IllegalArgumentException.class, () -> offer.respond(ShiftOffer.Status.EXPIRED, DEADLINE));
        assertTrue(offer.respond(ShiftOffer.Status.DECLINED, DEADLINE.minusSeconds(1)));
        assertFalse(offer.respond(ShiftOffer.Status.ACCEPTED, DEADLINE.minusSeconds(1)));
        assertEquals(ShiftOffer.Status.DECLINED, offer.getStatus());
    }

    @Test
    void exactDeadlineExpiresOfferAndCountdownRoundsUp() {
        var offer = offer();
        assertEquals(1, offer.getRemainingSeconds(DEADLINE.minusNanos(1)));
        assertEquals(2, offer.getRemainingSeconds(DEADLINE.minusSeconds(1).minusNanos(1)));
        assertEquals(0, offer.getRemainingSeconds(DEADLINE));
        assertFalse(offer.respond(ShiftOffer.Status.ACCEPTED, DEADLINE));
        assertEquals(ShiftOffer.Status.EXPIRED, offer.getStatus());
        assertFalse(offer.expireIfNeeded(DEADLINE.plusSeconds(1)));
    }

    @Test
    void overnightDurationAndEqualTimesValidationRemainSupported() {
        var offer = offer(LocalTime.of(22, 0), LocalTime.of(6, 0), List.of());
        assertEquals(LocalDate.of(2026, 10, 24), offer.getEndDate());
        assertEquals(8, offer.getDuration().toHours());
        assertEquals(6, offer().getDuration().toHours());
        assertThrows(IllegalArgumentException.class, () -> offer(LocalTime.NOON, LocalTime.NOON, List.of()));
    }

    @Test
    void reasonsAreCopiedAndNormalized() {
        var reasons = new ArrayList<>(List.of("  Role match  ", " "));
        var offer = offer(LocalTime.of(16, 0), LocalTime.of(22, 0), reasons);
        reasons.clear();
        assertEquals(List.of("Role match"), offer.getSelectionReasons());
        assertThrows(UnsupportedOperationException.class, () -> offer.getSelectionReasons().clear());
    }
}
