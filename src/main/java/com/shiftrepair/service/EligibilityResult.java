package com.shiftrepair.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EligibilityResult(Status status, List<Reason> reasons, Optional<BigDecimal> projectedHours) {
    public enum Status { ELIGIBLE, REQUIRES_APPROVAL, INELIGIBLE }

    public enum Code {
        MISSING_DATA, INVALID_SCHEDULE, INVALID_APPROVAL_DATA, STALE_DATA, INVALID_SNAPSHOT_TIME,
        PERIOD_MISMATCH, CONTRADICTORY_HOURS, CONTRADICTORY_QUALIFICATIONS,
        TARGET_NOT_OPEN, TARGET_ALREADY_STARTED, INACTIVE_EMPLOYEE,
        MISSING_QUALIFICATION, QUALIFICATION_EXPIRED, NOT_AVAILABLE, SHIFT_CONFLICT,
        OVERTIME_PROHIBITED, APPROVAL_REQUIRED, APPROVAL_EXPIRED, APPROVAL_IRRELEVANT,
        APPROVAL_UNAUTHORIZED, APPROVAL_INSUFFICIENT, APPROVAL_DENIED, APPROVAL_VALID, APPROVAL_STALE,
        UNVERIFIED_TIME_RESERVED, OVERTIME_PERMITTED, REQUIREMENTS_MET
    }

    public record Reason(Code code, String message) {
        public Reason {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException("Reason message must not be blank");
            }
        }
    }

    public EligibilityResult {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(reasons);
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("An eligibility result must explain its outcome");
        }
        Objects.requireNonNull(projectedHours, "projectedHours");
        if (projectedHours.isPresent() && projectedHours.get().signum() < 0) {
            throw new IllegalArgumentException("Projected hours must be nonnegative");
        }
    }

    public boolean isEligible() {
        return status == Status.ELIGIBLE;
    }
}
