package com.shiftrepair.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Company-supplied threshold for the accounting period being evaluated; no legal default. */
public record OvertimePolicy(Mode mode, BigDecimal regularHoursLimit) {
    public enum Mode { PROHIBITED, MANAGER_APPROVAL_REQUIRED, PERMITTED }

    public OvertimePolicy {
        Objects.requireNonNull(mode, "mode");
        regularHoursLimit = ModelChecks.hours(regularHoursLimit, "regularHoursLimit");
        if (regularHoursLimit.signum() == 0) {
            throw new IllegalArgumentException("regularHoursLimit must be positive");
        }
    }

    public boolean exceedsLimit(BigDecimal projectedHours) {
        return ModelChecks.hours(projectedHours, "projectedHours").compareTo(regularHoursLimit) > 0;
    }
}
