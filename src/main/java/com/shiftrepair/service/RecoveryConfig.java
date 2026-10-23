package com.shiftrepair.service;

import java.time.Duration;
import java.util.Objects;

public record RecoveryConfig(Duration offerLifetime, ScoringConfig scoring) {
    public RecoveryConfig {
        Objects.requireNonNull(offerLifetime, "offerLifetime");
        Objects.requireNonNull(scoring, "scoring");
        if (offerLifetime.isZero() || offerLifetime.isNegative()) {
            throw new IllegalArgumentException("Offer lifetime must be positive");
        }
    }
}
