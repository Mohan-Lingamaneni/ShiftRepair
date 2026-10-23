package com.shiftrepair.service;

import com.shiftrepair.model.TimeWindow;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Caller-supplied, complete genuine event history for one employee and window. No demo generation. */
public record ScoringHistory(String employeeId, TimeWindow window,
                             List<OfferEvent> offers, List<AssignmentEvent> assignments) {
    /** A response means accept OR decline; acceptance preference is not scored. */
    public record OfferEvent(String id, Instant offeredAt, Instant deadline, Optional<Instant> respondedAt) {
        public OfferEvent {
            id = identifier(id);
            Objects.requireNonNull(offeredAt, "offeredAt");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(respondedAt, "respondedAt");
            if (!deadline.isAfter(offeredAt) || respondedAt.filter(time -> time.isBefore(offeredAt)).isPresent()) {
                throw new IllegalArgumentException("Offer deadline must follow creation; response cannot precede creation");
            }
        }
    }

    /** A recorded recovery coverage assignment event, not a second Shift domain model. */
    public record AssignmentEvent(String id, Instant assignedAt) {
        public AssignmentEvent {
            id = identifier(id);
            Objects.requireNonNull(assignedAt, "assignedAt");
        }
    }

    public ScoringHistory {
        employeeId = identifier(employeeId);
        Objects.requireNonNull(window, "window");
        offers = List.copyOf(offers);
        assignments = List.copyOf(assignments);
        var offerIds = new HashSet<String>();
        for (var offer : offers) {
            if (!offerIds.add(offer.id()) || !within(offer.offeredAt(), window)) {
                throw new IllegalArgumentException("Offers must have unique IDs and creation times within the history window");
            }
        }
        var assignmentIds = new HashSet<String>();
        for (var assignment : assignments) {
            if (!assignmentIds.add(assignment.id()) || !within(assignment.assignedAt(), window)) {
                throw new IllegalArgumentException("Assignments must have unique IDs and event times within the history window");
            }
        }
    }

    private static boolean within(Instant instant, TimeWindow window) {
        return !instant.isBefore(window.start()) && instant.isBefore(window.end());
    }

    private static String identifier(String value) {
        Objects.requireNonNull(value, "identifier");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
        return value;
    }
}
