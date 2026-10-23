package com.shiftrepair.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Empty expiresAt means no recorded expiry. Expiry is exclusive. */
public record EmployeeQualification(Qualification qualification, Optional<Instant> expiresAt) {
    public EmployeeQualification {
        Objects.requireNonNull(qualification, "qualification");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean covers(TimeWindow window) {
        Objects.requireNonNull(window, "window");
        return expiresAt.isEmpty() || !expiresAt.get().isBefore(window.end());
    }
}
