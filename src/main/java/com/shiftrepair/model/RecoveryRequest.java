package com.shiftrepair.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Coverage state only. Does not dispatch offers or assign the replacement's schedule. */
public record RecoveryRequest(String id, Shift shift, String requestedById, Instant requestedAt,
                              Status status, Optional<String> replacementEmployeeId) {
    public enum Status { OPEN, COVERED, CANCELLED, ESCALATED }

    public RecoveryRequest {
        id = ModelChecks.text(id, "id");
        Objects.requireNonNull(shift, "shift");
        requestedById = ModelChecks.text(requestedById, "requestedById");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(replacementEmployeeId, "replacementEmployeeId");
        replacementEmployeeId.ifPresent(value -> ModelChecks.text(value, "replacementEmployeeId"));
        if ((status == Status.COVERED) != replacementEmployeeId.isPresent()) {
            throw new IllegalArgumentException("Only a covered recovery must have a replacement");
        }
        if (replacementEmployeeId.isPresent() && replacementEmployeeId.equals(shift.assignedEmployeeId())) {
            throw new IllegalArgumentException("Replacement must differ from original employee");
        }
    }

    public static RecoveryRequest open(String id, Shift shift, String requestedById, Instant requestedAt) {
        return new RecoveryRequest(id, shift, requestedById, requestedAt, Status.OPEN, Optional.empty());
    }

    /** Caller must first verify eligibility, approval and assignment consistency. */
    public RecoveryRequest cover(String employeeId) {
        requireOpen();
        return new RecoveryRequest(id, shift, requestedById, requestedAt, Status.COVERED,
                Optional.of(ModelChecks.text(employeeId, "employeeId")));
    }

    public RecoveryRequest cancel() {
        if (status != Status.OPEN && status != Status.ESCALATED) {
            throw new IllegalStateException("Recovery is already " + status);
        }
        return new RecoveryRequest(id, shift, requestedById, requestedAt, Status.CANCELLED, Optional.empty());
    }

    public RecoveryRequest escalate() {
        requireOpen();
        return new RecoveryRequest(id, shift, requestedById, requestedAt, Status.ESCALATED, Optional.empty());
    }

    public RecoveryRequest reopen() {
        if (status != Status.ESCALATED) {
            throw new IllegalStateException("Only escalated recoveries may reopen");
        }
        return new RecoveryRequest(id, shift, requestedById, requestedAt, Status.OPEN, Optional.empty());
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Recovery is already " + status);
        }
    }
}
