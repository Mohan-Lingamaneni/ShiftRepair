package com.shiftrepair.service;

import com.shiftrepair.model.RecoveryRequest;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.ShiftOffer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Detached immutable read models. Mutable ShiftOffer instances never escape RecoveryService. */
public record RecoveryView(RecoveryRequest recovery, List<Offer> offers,
                           List<ScoringResult> candidates, List<Event> events, Optional<Shift> assignment) {
    public enum EventType {
        CREATED, CANDIDATES_EVALUATED, OFFER_CREATED, OFFER_DECLINED, OFFER_EXPIRED,
        OFFER_CLOSED, ACCEPTANCE_REJECTED, FILLED, CANCELLED, ESCALATED, REOPENED
    }

    public record Event(EventType type, Instant at, Optional<String> employeeId,
                        Optional<String> offerId, String message) { }

    /** Exact shift instants and full scoring result are authoritative; no display-time conversion needed. */
    public record Offer(String id, String recoveryId, String employeeId, Shift shift,
                        ShiftOffer.Status status, Instant createdAt, Instant expiresAt, ScoringResult scoring) { }

    public RecoveryView {
        offers = List.copyOf(offers);
        candidates = List.copyOf(candidates);
        events = List.copyOf(events);
    }
}
