package com.shiftrepair.service;

import java.util.Objects;
import java.util.Optional;

/** Eligibility is evaluated by ScoringEngine; callers cannot supply a forged eligibility status. */
public record ScoringRequest(EligibilityRequest eligibility, Optional<ScoringHistory> history) {
    public ScoringRequest {
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(eligibility.employee(), "employee");
        Objects.requireNonNull(eligibility.targetShift(), "targetShift");
        Objects.requireNonNull(eligibility.policy(), "policy");
        Objects.requireNonNull(eligibility.evaluatedAt(), "evaluatedAt");
        Objects.requireNonNull(history, "history");
    }
}
