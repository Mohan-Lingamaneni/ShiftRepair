package com.shiftrepair.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ScoringResult(String employeeId, EligibilityResult eligibility,
                            Optional<BigDecimal> overallScore, List<FactorScore> factors) {
    public enum Factor { AVAILABILITY_FIT, WORKLOAD_BALANCE, FAIRNESS_HISTORY, RESPONSE_HISTORY }
    public enum DataStatus { AVAILABLE, MISSING_HISTORY, NO_COMPLETED_OFFERS, INVALID_HISTORY, NOT_DIFFERENTIATED }

    /** Contributions are points on the overall 0..100 scale, not percentages of a rounded total. */
    public record FactorScore(Factor factor, BigDecimal score, BigDecimal weight,
                              BigDecimal contribution, DataStatus dataStatus, String explanation) {
        public FactorScore {
            Objects.requireNonNull(factor, "factor");
            bounded(score);
            bounded(weight);
            bounded(contribution);
            Objects.requireNonNull(dataStatus, "dataStatus");
            Objects.requireNonNull(explanation, "explanation");
            if (explanation.isBlank() || contribution.compareTo(score.multiply(weight).movePointLeft(2)) != 0) {
                throw new IllegalArgumentException("Factor must explain its exact weighted contribution");
            }
        }
    }

    /** Each scored list is independently ordered. Conditional candidates are never assignable by this result. */
    public record Ranking(List<ScoringResult> eligibleCandidates, List<ScoringResult> conditionalCandidates,
                          List<ScoringResult> excludedCandidates) {
        public Ranking {
            eligibleCandidates = List.copyOf(eligibleCandidates);
            conditionalCandidates = List.copyOf(conditionalCandidates);
            excludedCandidates = List.copyOf(excludedCandidates);
        }
    }

    public ScoringResult {
        Objects.requireNonNull(employeeId, "employeeId");
        if (employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee ID must not be blank");
        }
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(overallScore, "overallScore");
        factors = List.copyOf(factors);
        if (eligibility.status() == EligibilityResult.Status.INELIGIBLE) {
            if (overallScore.isPresent() || !factors.isEmpty()) {
                throw new IllegalArgumentException("Ineligible employees must not be scored");
            }
        } else {
            bounded(overallScore.orElseThrow(() -> new IllegalArgumentException("Score required")));
            if (factors.size() != Factor.values().length
                    || factors.stream().map(FactorScore::factor).distinct().count() != Factor.values().length
                    || factors.stream().map(FactorScore::weight).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(BigDecimal.valueOf(100)) != 0
                    || factors.stream().map(FactorScore::contribution).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(overallScore.get()) != 0) {
                throw new IllegalArgumentException("Four factors must reconcile with weights and total");
            }
        }
    }

    private static void bounded(BigDecimal value) {
        Objects.requireNonNull(value, "score value");
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Score values must be between 0 and 100");
        }
    }
}
