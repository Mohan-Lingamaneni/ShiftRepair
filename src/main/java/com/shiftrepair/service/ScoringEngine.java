package com.shiftrepair.service;

import com.shiftrepair.model.TimeWindow;
import com.shiftrepair.service.ScoringResult.DataStatus;
import com.shiftrepair.service.ScoringResult.Factor;
import com.shiftrepair.service.ScoringResult.FactorScore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provisional suitability formula; hard eligibility, assignment and distribution remain separate. */
public final class ScoringEngine {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final EligibilityEngine eligibilityEngine = new EligibilityEngine();

    public ScoringResult score(ScoringRequest request, ScoringConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        var input = request.eligibility();
        var eligibility = eligibilityEngine.evaluate(input);
        if (eligibility.status() == EligibilityResult.Status.INELIGIBLE) {
            return new ScoringResult(input.employee().id(), eligibility, Optional.empty(), List.of());
        }
        var factors = new ArrayList<FactorScore>();
        factors.add(availability(request, config));
        BigDecimal projected = eligibility.projectedHours().orElseThrow();
        BigDecimal limit = input.policy().overtime().regularHoursLimit();
        factors.add(factor(Factor.WORKLOAD_BALANCE, percentage(limit.subtract(projected).max(BigDecimal.ZERO), limit),
                config.workloadWeight(), DataStatus.AVAILABLE,
                "Projected period hours including target: " + projected.toPlainString()
                        + "; configured regular-hours limit: " + limit.toPlainString()
                        + ". Score measures remaining headroom; eligibility supplied the projection."));

        var history = request.history();
        DataStatus historyStatus = historyStatus(request, config);
        if (historyStatus != DataStatus.AVAILABLE) {
            String explanation = historyStatus == DataStatus.MISSING_HISTORY
                    ? "No historical records supplied; equal full credit, no inferred events."
                    : "History employee/window or response timestamps do not match this evaluation; equal full credit, records unused.";
            factors.add(factor(Factor.FAIRNESS_HISTORY, HUNDRED, config.fairnessWeight(), historyStatus, explanation));
            factors.add(factor(Factor.RESPONSE_HISTORY, HUNDRED, config.responseWeight(), historyStatus, explanation));
        } else {
            var records = history.orElseThrow();
            long events = (long) records.offers().size() + records.assignments().size();
            factors.add(factor(Factor.FAIRNESS_HISTORY, percentage(BigDecimal.ONE, BigDecimal.valueOf(events + 1)),
                    config.fairnessWeight(), DataStatus.AVAILABLE,
                    "Complete lookback contains " + records.offers().size() + " offers and "
                            + records.assignments().size() + " coverage assignments; fewer recent opportunities receive more credit."));
            var completed = records.offers().stream()
                    .filter(offer -> offer.respondedAt().isPresent() || !offer.deadline().isAfter(input.evaluatedAt())).toList();
            long timely = completed.stream().filter(offer -> offer.respondedAt()
                    .filter(time -> time.isBefore(offer.deadline())).isPresent()).count();
            factors.add(factor(Factor.RESPONSE_HISTORY, completed.isEmpty() ? HUNDRED
                            : percentage(BigDecimal.valueOf(timely), BigDecimal.valueOf(completed.size())),
                    config.responseWeight(), completed.isEmpty() ? DataStatus.NO_COMPLETED_OFFERS : DataStatus.AVAILABLE,
                    completed.isEmpty() ? "No completed offers to measure; equal full credit. Pending offers are not missed responses."
                            : timely + " of " + completed.size() + " completed offers received a timely response; accept and decline count equally."));
        }
        BigDecimal total = factors.stream().map(FactorScore::contribution).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ScoringResult(input.employee().id(), eligibility, Optional.of(total.stripTrailingZeros()), factors);
    }

    /** Evaluates every request before ranking; does not trust caller-supplied scores or eligibility results. */
    public ScoringResult.Ranking rank(Collection<ScoringRequest> candidates, ScoringConfig config) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(config, "config");
        var inputs = List.copyOf(candidates);
        var ids = new HashSet<String>();
        if (!inputs.isEmpty()) {
            var reference = inputs.getFirst().eligibility();
            for (var candidate : inputs) {
                var input = candidate.eligibility();
                if (!ids.add(input.employee().id())) {
                    throw new IllegalArgumentException("Candidate IDs must be unique");
                }
                if (!reference.targetShift().equals(input.targetShift()) || !reference.policy().equals(input.policy())
                        || !reference.evaluatedAt().equals(input.evaluatedAt())) {
                    throw new IllegalArgumentException("Rank candidates for the same target, company policy/period and evaluation time");
                }
            }
        }
        var evaluated = inputs.stream().map(input -> score(input, config)).toList();
        Comparator<ScoringResult> order = Comparator
                .comparing((ScoringResult result) -> result.overallScore().orElseThrow(), Comparator.reverseOrder())
                .thenComparing(ScoringResult::employeeId);
        return new ScoringResult.Ranking(
                evaluated.stream().filter(r -> r.eligibility().status() == EligibilityResult.Status.ELIGIBLE).sorted(order).toList(),
                evaluated.stream().filter(r -> r.eligibility().status() == EligibilityResult.Status.REQUIRES_APPROVAL).sorted(order).toList(),
                evaluated.stream().filter(r -> r.eligibility().status() == EligibilityResult.Status.INELIGIBLE)
                        .sorted(Comparator.comparing(ScoringResult::employeeId)).toList());
    }

    private FactorScore availability(ScoringRequest request, ScoringConfig config) {
        if (config.availabilityBuffer().isZero()) {
            return factor(Factor.AVAILABILITY_FIT, HUNDRED, config.availabilityWeight(), DataStatus.NOT_DIFFERENTIATED,
                    "No surrounding-time preference configured; all eligible candidates receive equal availability credit.");
        }
        var input = request.eligibility();
        var shift = input.targetShift().window();
        // Merge declared adjacent/overlapping windows. Eligibility already established full coverage.
        var merged = new ArrayList<TimeWindow>();
        for (var window : input.employee().availability().windows().stream()
                .sorted(Comparator.comparing(TimeWindow::start)).toList()) {
            if (merged.isEmpty() || window.start().isAfter(merged.getLast().end())) {
                merged.add(window);
            } else {
                var previous = merged.removeLast();
                merged.add(new TimeWindow(previous.start(), window.end().isAfter(previous.end()) ? window.end() : previous.end()));
            }
        }
        var covering = merged.stream().filter(window -> window.contains(shift)).findFirst().orElseThrow();
        Instant left = covering.start();
        Instant right = covering.end();
        // Do not credit past time or time already committed to adjacent assignments.
        if (left.isBefore(input.evaluatedAt())) {
            left = input.evaluatedAt();
        }
        for (var assignment : input.assignments()) {
            var window = assignment.window();
            if (!window.end().isAfter(shift.start()) && window.end().isAfter(left)) {
                left = window.end();
            }
            if (!window.start().isBefore(shift.end()) && window.start().isBefore(right)) {
                right = window.start();
            }
        }
        BigDecimal buffer = seconds(config.availabilityBuffer());
        BigDecimal before = seconds(Duration.between(left, shift.start())).min(buffer);
        BigDecimal after = seconds(Duration.between(shift.end(), right)).min(buffer);
        return factor(Factor.AVAILABILITY_FIT, percentage(before.add(after), buffer.multiply(BigDecimal.valueOf(2))),
                config.availabilityWeight(), DataStatus.AVAILABLE,
                "Usable surrounding availability: " + before.toPlainString() + " seconds before and "
                        + after.toPlainString() + " seconds after, capped at " + buffer.toPlainString()
                        + " per side. This is an optional flexibility preference, not another eligibility rule.");
    }

    private DataStatus historyStatus(ScoringRequest request, ScoringConfig config) {
        if (request.history().isEmpty()) {
            return DataStatus.MISSING_HISTORY;
        }
        var history = request.history().get();
        var input = request.eligibility();
        // Duration comparison avoids subtracting an arbitrary lookback from Instant.MIN.
        if (!history.employeeId().equals(input.employee().id()) || !history.window().end().equals(input.evaluatedAt())
                || !history.window().duration().equals(config.historyLookback())
                || history.offers().stream().anyMatch(offer -> offer.respondedAt()
                        .filter(time -> time.isAfter(input.evaluatedAt())).isPresent())) {
            return DataStatus.INVALID_HISTORY;
        }
        return DataStatus.AVAILABLE;
    }

    private static FactorScore factor(Factor factor, BigDecimal score, BigDecimal weight,
                                      DataStatus status, String explanation) {
        BigDecimal rounded = score.setScale(6, RoundingMode.HALF_UP);
        return new FactorScore(factor, rounded, weight, rounded.multiply(weight).movePointLeft(2), status, explanation);
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(HUNDRED).divide(denominator, 6, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(HUNDRED);
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }
}
