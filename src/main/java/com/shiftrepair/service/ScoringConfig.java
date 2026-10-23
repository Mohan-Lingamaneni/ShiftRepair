package com.shiftrepair.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Percent weights must sum to exactly 100. Example settings are provisional. */
public record ScoringConfig(BigDecimal availabilityWeight, BigDecimal workloadWeight,
                            BigDecimal fairnessWeight, BigDecimal responseWeight,
                            Duration availabilityBuffer, Duration historyLookback) {
    public ScoringConfig {
        availabilityWeight = weight(availabilityWeight);
        workloadWeight = weight(workloadWeight);
        fairnessWeight = weight(fairnessWeight);
        responseWeight = weight(responseWeight);
        if (availabilityWeight.add(workloadWeight).add(fairnessWeight).add(responseWeight)
                .compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new IllegalArgumentException("Percentage weights must sum to exactly 100");
        }
        Objects.requireNonNull(availabilityBuffer, "availabilityBuffer");
        Objects.requireNonNull(historyLookback, "historyLookback");
        if (availabilityBuffer.isNegative() || historyLookback.isNegative() || historyLookback.isZero()) {
            throw new IllegalArgumentException("Buffer must be nonnegative and history lookback must be positive");
        }
    }

    public static ScoringConfig provisionalExample() {
        return new ScoringConfig(BigDecimal.valueOf(30), BigDecimal.valueOf(30),
                BigDecimal.valueOf(25), BigDecimal.valueOf(15), Duration.ZERO, Duration.ofDays(28));
    }

    private static BigDecimal weight(BigDecimal value) {
        Objects.requireNonNull(value, "weight");
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Weights must be between 0 and 100");
        }
        return value.stripTrailingZeros();
    }
}
