package com.shiftrepair.service;

import com.shiftrepair.model.*;
import com.shiftrepair.service.ScoringResult.DataStatus;
import com.shiftrepair.service.ScoringResult.Factor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.shiftrepair.service.EligibilityResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    private static final TimeWindow PERIOD = new TimeWindow(Instant.parse("2026-10-19T05:00:00Z"),
            Instant.parse("2026-10-26T05:00:00Z"));
    private static final Qualification ROLE = new Qualification(Qualification.Type.ROLE, "cashier");
    private final ScoringEngine engine = new ScoringEngine();
    private EligibilityPolicy policy;
    private Shift target;
    private ScoringConfig config;

    @BeforeEach
    void setup() {
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.PERMITTED, number("40")),
                Duration.ofMinutes(15), Duration.ofHours(1), Set.of("manager-1"), Map.of());
        target = shift("coverage-1", "2026-10-23T21:00:00Z", "2026-10-24T03:00:00Z", null);
        config = ScoringConfig.provisionalExample();
    }

    private BigDecimal number(String value) { return new BigDecimal(value); }

    private Shift shift(String id, String start, String end, String employeeId) {
        return new Shift(id, "store-1", new TimeWindow(Instant.parse(start), Instant.parse(end)),
                Set.of(ROLE), Optional.ofNullable(employeeId));
    }

    private ScoringRequest candidate(String id, String actual) {
        var employee = new Employee(id, "Synthetic employee", Employee.EmploymentStatus.ACTIVE,
                Set.of(new EmployeeQualification(ROLE, Optional.empty())), new Availability(List.of(PERIOD)));
        return new ScoringRequest(new EligibilityRequest(employee, target, List.of(),
                new WorkHourSummary(id, PERIOD, BigDecimal.ZERO, number(actual)), policy, List.of(), NOW, NOW), Optional.empty());
    }

    private ScoringConfig only(Factor selected, Duration buffer) {
        return new ScoringConfig(selected == Factor.AVAILABILITY_FIT ? number("100") : BigDecimal.ZERO,
                selected == Factor.WORKLOAD_BALANCE ? number("100") : BigDecimal.ZERO,
                selected == Factor.FAIRNESS_HISTORY ? number("100") : BigDecimal.ZERO,
                selected == Factor.RESPONSE_HISTORY ? number("100") : BigDecimal.ZERO, buffer, config.historyLookback());
    }

    private ScoringRequest availability(ScoringRequest request, List<TimeWindow> windows) {
        var input = request.eligibility();
        var old = input.employee();
        var employee = new Employee(old.id(), old.name(), old.status(), old.qualifications(), new Availability(windows));
        return new ScoringRequest(new EligibilityRequest(employee, input.targetShift(), input.assignments(), input.workHours(),
                input.policy(), input.approvals(), input.verifiedThrough(), input.evaluatedAt()), request.history());
    }

    private ScoringRequest history(ScoringRequest request, List<ScoringHistory.OfferEvent> offers,
                                   List<ScoringHistory.AssignmentEvent> assignments) {
        return new ScoringRequest(request.eligibility(), Optional.of(new ScoringHistory(request.eligibility().employee().id(),
                new TimeWindow(NOW.minus(config.historyLookback()), NOW), offers, assignments)));
    }

    private ScoringHistory.OfferEvent offer(String id, String kind) {
        Instant created = NOW.minusSeconds(7200);
        Instant deadline = kind.equals("pending") ? NOW.plusSeconds(3600) : NOW.minusSeconds(3600);
        Optional<Instant> response = switch (kind) {
            case "timely" -> Optional.of(created.plusSeconds(60));
            case "late" -> Optional.of(deadline.plusSeconds(1));
            case "boundary" -> Optional.of(deadline);
            case "future" -> Optional.of(NOW.plusSeconds(1));
            default -> Optional.empty();
        };
        return new ScoringHistory.OfferEvent(id, created, deadline, response);
    }

    private ScoringResult.FactorScore factor(ScoringResult result, Factor factor) {
        return result.factors().stream().filter(value -> value.factor() == factor).findFirst().orElseThrow();
    }

    private void assertNumber(String expected, BigDecimal actual) {
        assertEquals(0, number(expected).compareTo(actual), "Expected " + expected + " but got " + actual);
    }

    @Test
    void provisionalWeightsAndExactExplainedTotal() {
        var result = engine.score(candidate("employee-a", "18"), config);
        assertEquals(ELIGIBLE, result.eligibility().status());
        assertNumber("82", result.overallScore().orElseThrow());
        var workload = factor(result, Factor.WORKLOAD_BALANCE);
        assertNumber("40", workload.score()); // Actual 18 + target 6 -> 24 / 40.
        assertNumber("12", workload.contribution());
        assertNumber("30", factor(result, Factor.AVAILABILITY_FIT).weight());
        assertNumber("25", factor(result, Factor.FAIRNESS_HISTORY).weight());
        assertNumber("15", factor(result, Factor.RESPONSE_HISTORY).weight());
        assertEquals(0, result.overallScore().get().compareTo(result.factors().stream()
                .map(ScoringResult.FactorScore::contribution).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertTrue(result.factors().stream().allMatch(value -> !value.explanation().isBlank()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "0", "99.99", "100.01"})
    void invalidWeightsAreRejected(String weight) {
        assertThrows(IllegalArgumentException.class, () -> new ScoringConfig(number(weight), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, Duration.ZERO, Duration.ofDays(28)));
    }

    @Test
    void nullNonFiniteAndInvalidDurationSettingsAreRejected() {
        assertThrows(NullPointerException.class, () -> new ScoringConfig(null, number("30"), number("25"), number("15"),
                Duration.ZERO, Duration.ofDays(28)));
        // BigDecimal's input boundary cannot represent NaN or infinity.
        assertThrows(NumberFormatException.class, () -> number("NaN"));
        assertThrows(NumberFormatException.class, () -> BigDecimal.valueOf(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ScoringConfig(number("30"), number("30"), number("25"), number("15"),
                Duration.ofSeconds(-1), Duration.ofDays(28)));
        assertThrows(IllegalArgumentException.class, () -> new ScoringConfig(number("30"), number("30"), number("25"), number("15"),
                Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ScoringConfig(number("30"), number("30"), number("25"), number("15"),
                Duration.ZERO, Duration.ofDays(-1)));
    }

    @Test
    void decimalWeightsAreAcceptedWhenTheySumExactlyTo100() {
        var weights = new ScoringConfig(number("0.1"), number("0.2"), number("49.7"), number("50"), Duration.ZERO, Duration.ofDays(28));
        var result = engine.score(candidate("a", "18"), weights);
        assertNumber("99.88", result.overallScore().orElseThrow());
    }

    @Test
    void zeroAndHundredBoundariesAreValid() {
        var request = candidate("a", "40");
        assertNumber("0", engine.score(request, only(Factor.WORKLOAD_BALANCE, Duration.ZERO)).overallScore().orElseThrow());
        assertNumber("100", engine.score(request, only(Factor.RESPONSE_HISTORY, Duration.ZERO)).overallScore().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.1", "10", "20", "33.99999999", "34", "60", "100"})
    void allScoresAndContributionsStayBounded(String actual) {
        var result = engine.score(candidate("a", actual), config);
        assertTrue(result.overallScore().orElseThrow().signum() >= 0);
        assertTrue(result.overallScore().orElseThrow().compareTo(number("100")) <= 0);
        for (var value : result.factors()) {
            assertTrue(value.score().signum() >= 0 && value.score().compareTo(number("100")) <= 0);
            assertTrue(value.contribution().signum() >= 0 && value.contribution().compareTo(value.weight()) <= 0);
        }
    }

    @Test
    void noBufferPreferenceGivesEqualAvailabilityCreditWithoutInventingDifferences() {
        var broad = candidate("a", "18");
        var exact = availability(candidate("b", "18"), List.of(target.window()));
        var one = factor(engine.score(broad, config), Factor.AVAILABILITY_FIT);
        var two = factor(engine.score(exact, config), Factor.AVAILABILITY_FIT);
        assertNumber("100", one.score());
        assertEquals(one, two);
        assertEquals(DataStatus.NOT_DIFFERENTIATED, one.dataStatus());
    }

    @Test
    void optionalBufferMeasuresSurroundingFlexibilityNotPassFailAvailability() {
        var preference = only(Factor.AVAILABILITY_FIT, Duration.ofHours(1));
        var exact = availability(candidate("a", "18"), List.of(target.window()));
        var half = availability(candidate("b", "18"), List.of(new TimeWindow(target.window().start().minusSeconds(1800),
                target.window().end().plusSeconds(1800))));
        var broad = candidate("c", "18");
        assertEquals(ELIGIBLE, engine.score(exact, preference).eligibility().status());
        assertNumber("0", engine.score(exact, preference).overallScore().orElseThrow());
        assertNumber("50", engine.score(half, preference).overallScore().orElseThrow());
        assertNumber("100", engine.score(broad, preference).overallScore().orElseThrow());
    }

    @Test
    void bufferMergesUnorderedAdjacentAndOverlappingWindowsWithoutDoubleCredit() {
        var start = target.window().start();
        var end = target.window().end();
        var request = availability(candidate("a", "18"), List.of(
                new TimeWindow(start.minusSeconds(7200), start.minusSeconds(3600)),
                new TimeWindow(end, end.plusSeconds(3600)),
                new TimeWindow(start.minusSeconds(3600), end), target.window()));
        assertNumber("100", engine.score(request, only(Factor.AVAILABILITY_FIT, Duration.ofHours(1)))
                .overallScore().orElseThrow());
    }

    @Test
    void disconnectedAvailabilityDoesNotProvideBufferCredit() {
        var request = availability(candidate("a", "18"), List.of(target.window(),
                new TimeWindow(target.window().start().minusSeconds(7200), target.window().start().minusSeconds(1))));
        assertNumber("0", engine.score(request, only(Factor.AVAILABILITY_FIT, Duration.ofHours(1)))
                .overallScore().orElseThrow());
    }

    @Test
    void assignmentCommitmentsClipBufferCreditWithoutCreatingExtraHours() {
        var request = candidate("a", "18");
        var input = request.eligibility();
        var commitments = List.of(
                shift("before", "2026-10-23T20:30:00Z", "2026-10-23T21:00:00Z", "a"),
                shift("after", "2026-10-24T03:30:00Z", "2026-10-24T04:00:00Z", "a"));
        request = new ScoringRequest(new EligibilityRequest(input.employee(), target, commitments,
                new WorkHourSummary("a", PERIOD, number("1"), number("18")), policy, List.of(), NOW, NOW), Optional.empty());
        var result = engine.score(request, only(Factor.AVAILABILITY_FIT, Duration.ofHours(1)));
        assertNumber("25", result.overallScore().orElseThrow());
        assertNumber("25", result.eligibility().projectedHours().orElseThrow()); // 18+1+6, no buffer counted as work.
    }

    @Test
    void bufferDoesNotCreditAlreadyElapsedTime() {
        target = shift("coverage-1", "2026-10-23T15:00:00Z", "2026-10-23T21:00:00Z", null);
        assertNumber("50", engine.score(candidate("a", "18"), only(Factor.AVAILABILITY_FIT, Duration.ofHours(1)))
                .overallScore().orElseThrow());
    }

    @Test
    void workloadFavorsLowerProjectedHoursAndFloorsOvertimeAtZero() {
        var workload = only(Factor.WORKLOAD_BALANCE, Duration.ZERO);
        assertNumber("85", engine.score(candidate("a", "0"), workload).overallScore().orElseThrow());
        assertNumber("40", engine.score(candidate("b", "18"), workload).overallScore().orElseThrow());
        assertNumber("0", engine.score(candidate("c", "34"), workload).overallScore().orElseThrow());
        assertNumber("0", engine.score(candidate("d", "50"), workload).overallScore().orElseThrow());
    }

    @Test
    void workloadUsesEligibilityProjectionWithNoDoubleCountOfPastOrPartialAssignments() {
        var request = candidate("a", "28");
        var input = request.eligibility();
        var assignments = List.of(shift("past", "2026-10-22T15:00:00Z", "2026-10-22T23:00:00Z", "a"),
                shift("ongoing", "2026-10-23T13:00:00Z", "2026-10-23T17:00:00Z", "a"));
        request = new ScoringRequest(new EligibilityRequest(input.employee(), target, assignments,
                new WorkHourSummary("a", PERIOD, number("12"), number("28")), policy, List.of(), NOW, NOW), Optional.empty());
        var result = engine.score(request, only(Factor.WORKLOAD_BALANCE, Duration.ZERO));
        assertNumber("36", result.eligibility().projectedHours().orElseThrow());
        assertNumber("10", result.overallScore().orElseThrow());
    }

    @Test
    void workloadPreservesConservativeUnverifiedGapSemantics() {
        var input = candidate("a", "28").eligibility();
        var request = new ScoringRequest(new EligibilityRequest(input.employee(), target, List.of(), input.workHours(),
                policy, List.of(), NOW.minusSeconds(900), NOW), Optional.empty());
        var result = engine.score(request, only(Factor.WORKLOAD_BALANCE, Duration.ZERO));
        assertNumber("34.25", result.eligibility().projectedHours().orElseThrow());
        assertNumber("14.375", result.overallScore().orElseThrow());
    }

    @Test
    void crossPeriodAssignmentsAreClippedByEligibilityBeforeWorkloadScoring() {
        var input = candidate("a", "18").eligibility();
        var assignments = List.of(shift("start-boundary", "2026-10-19T03:00:00Z", "2026-10-19T07:00:00Z", "a"),
                shift("end-boundary", "2026-10-26T03:00:00Z", "2026-10-26T07:00:00Z", "a"));
        var request = new ScoringRequest(new EligibilityRequest(input.employee(), target, assignments,
                new WorkHourSummary("a", PERIOD, number("4"), number("18")), policy, List.of(), NOW, NOW), Optional.empty());
        var result = engine.score(request, only(Factor.WORKLOAD_BALANCE, Duration.ZERO));
        assertNumber("26", result.eligibility().projectedHours().orElseThrow());
        assertNumber("35", result.overallScore().orElseThrow());
    }

    @Test
    void targetCrossingAccountingPeriodRemainsExcludedWithoutInventedPartialProjection() {
        target = shift("coverage-1", "2026-10-26T03:00:00Z", "2026-10-26T07:00:00Z", null);
        var result = engine.score(candidate("a", "18"), config);
        assertEquals(INELIGIBLE, result.eligibility().status());
        assertTrue(result.overallScore().isEmpty());
        assertTrue(result.factors().isEmpty());
    }

    @Test
    void fairnessUsesOnlySuppliedRecentOfferAndAssignmentEvents() {
        var zero = history(candidate("a", "18"), List.of(), List.of());
        var one = history(candidate("b", "18"), List.of(offer("offer-1", "pending")), List.of());
        var two = history(candidate("c", "18"), List.of(offer("offer-2", "timely")),
                List.of(new ScoringHistory.AssignmentEvent("assignment-1", NOW.minusSeconds(60))));
        var fairness = only(Factor.FAIRNESS_HISTORY, Duration.ZERO);
        assertNumber("100", engine.score(zero, fairness).overallScore().orElseThrow());
        assertNumber("50", engine.score(one, fairness).overallScore().orElseThrow());
        assertNumber("33.333333", engine.score(two, fairness).overallScore().orElseThrow());
    }

    @Test
    void responseHistoryCountsTimelyAcceptOrDeclineAndIgnoresPendingOffers() {
        var request = history(candidate("a", "18"), List.of(offer("responded", "timely"),
                offer("missed", "missed"), offer("pending", "pending")), List.of());
        var result = engine.score(request, only(Factor.RESPONSE_HISTORY, Duration.ZERO));
        assertNumber("50", result.overallScore().orElseThrow());
        assertEquals(DataStatus.AVAILABLE, factor(result, Factor.RESPONSE_HISTORY).dataStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"late", "boundary", "missed"})
    void lateOrMissingResponseReceivesNoTimelyResponseCredit(String kind) {
        var request = history(candidate("a", "18"), List.of(offer("offer-1", kind)), List.of());
        assertNumber("0", engine.score(request, only(Factor.RESPONSE_HISTORY, Duration.ZERO)).overallScore().orElseThrow());
    }

    @Test
    void deadlineExactlyAtEvaluationIsCompletedAndUnanswered() {
        var event = new ScoringHistory.OfferEvent("offer-1", NOW.minusSeconds(60), NOW, Optional.empty());
        var request = history(candidate("a", "18"), List.of(event), List.of());
        assertNumber("0", engine.score(request, only(Factor.RESPONSE_HISTORY, Duration.ZERO)).overallScore().orElseThrow());
    }

    @Test
    void noHistoryAndNoCompletedOffersNeverAutomaticallyPenalizeEmployee() {
        var absent = engine.score(candidate("a", "18"), config);
        assertEquals(DataStatus.MISSING_HISTORY, factor(absent, Factor.FAIRNESS_HISTORY).dataStatus());
        assertNumber("100", factor(absent, Factor.FAIRNESS_HISTORY).score());
        assertNumber("100", factor(absent, Factor.RESPONSE_HISTORY).score());
        var pending = engine.score(history(candidate("b", "18"), List.of(offer("pending", "pending")), List.of()), config);
        assertEquals(DataStatus.NO_COMPLETED_OFFERS, factor(pending, Factor.RESPONSE_HISTORY).dataStatus());
        assertNumber("100", factor(pending, Factor.RESPONSE_HISTORY).score());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-employee", "old-window", "short-window", "future-response"})
    void incompleteStaleOrContradictoryHistoryIsFlaggedAndUnused(String kind) {
        var request = candidate("a", "18");
        var end = kind.equals("old-window") ? NOW.minusSeconds(1) : NOW;
        var start = end.minus(kind.equals("short-window") ? Duration.ofDays(1) : config.historyLookback());
        var history = new ScoringHistory(kind.equals("wrong-employee") ? "other" : "a", new TimeWindow(start, end),
                List.of(offer("offer-1", kind.equals("future-response") ? "future" : "missed")), List.of());
        var result = engine.score(new ScoringRequest(request.eligibility(), Optional.of(history)), config);
        assertEquals(ELIGIBLE, result.eligibility().status());
        assertEquals(DataStatus.INVALID_HISTORY, factor(result, Factor.FAIRNESS_HISTORY).dataStatus());
        assertEquals(DataStatus.INVALID_HISTORY, factor(result, Factor.RESPONSE_HISTORY).dataStatus());
        assertNumber("100", factor(result, Factor.RESPONSE_HISTORY).score());
    }

    @Test
    void changingWeightsPredictablyChangesRanking() {
        var lightButNarrow = availability(candidate("a", "0"), List.of(target.window()));
        var loadedButFlexible = candidate("b", "30");
        var candidates = List.of(loadedButFlexible, lightButNarrow);
        assertEquals("a", engine.rank(candidates, only(Factor.WORKLOAD_BALANCE, Duration.ofHours(1)))
                .eligibleCandidates().getFirst().employeeId());
        assertEquals("b", engine.rank(candidates, only(Factor.AVAILABILITY_FIT, Duration.ofHours(1)))
                .eligibleCandidates().getFirst().employeeId());
    }

    @Test
    void equalScoresUseEmployeeIdentifierOnlyAsStablePresentationTieBreaker() {
        var candidates = new ArrayList<>(List.of(candidate("employee-c", "18"), candidate("employee-a", "18"), candidate("employee-b", "18")));
        var first = engine.rank(candidates, config);
        assertEquals(List.of("employee-a", "employee-b", "employee-c"), first.eligibleCandidates().stream().map(ScoringResult::employeeId).toList());
        Collections.reverse(candidates);
        assertEquals(first, engine.rank(candidates, config));
        assertEquals(first.eligibleCandidates().get(0).overallScore(), first.eligibleCandidates().get(1).overallScore());
    }

    @Test
    void eligibilityAlwaysRunsAndIneligibleCandidateHasNoRankableScore() {
        var input = candidate("a", "18").eligibility();
        var old = input.employee();
        var inactive = new Employee(old.id(), old.name(), Employee.EmploymentStatus.INACTIVE, old.qualifications(), old.availability());
        var request = new ScoringRequest(new EligibilityRequest(inactive, target, List.of(), input.workHours(), policy, List.of(), NOW, NOW), Optional.empty());
        var ranking = engine.rank(List.of(request, candidate("b", "18")), config);
        assertEquals(1, ranking.eligibleCandidates().size());
        assertEquals(1, ranking.excludedCandidates().size());
        assertEquals(INELIGIBLE, ranking.excludedCandidates().getFirst().eligibility().status());
        assertTrue(ranking.excludedCandidates().getFirst().overallScore().isEmpty());
        assertTrue(ranking.excludedCandidates().getFirst().factors().isEmpty());
    }

    @Test
    void highScoringConditionalCandidateRemainsInSeparateApprovalRequiredList() {
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.MANAGER_APPROVAL_REQUIRED, number("40")),
                policy.maxSnapshotAge(), policy.approvalValidity(), policy.authorizedManagerIds(), Map.of());
        var conditional = candidate("a", "38");
        var eligible = history(candidate("b", "18"), List.of(offer("missed", "missed")), List.of());
        var ranking = engine.rank(List.of(conditional, eligible), only(Factor.RESPONSE_HISTORY, Duration.ZERO));
        assertEquals("b", ranking.eligibleCandidates().getFirst().employeeId());
        assertEquals("a", ranking.conditionalCandidates().getFirst().employeeId());
        assertNumber("100", ranking.conditionalCandidates().getFirst().overallScore().orElseThrow());
        assertNumber("0", ranking.eligibleCandidates().getFirst().overallScore().orElseThrow());
        assertEquals(REQUIRES_APPROVAL, ranking.conditionalCandidates().getFirst().eligibility().status());
        assertFalse(ranking.conditionalCandidates().getFirst().eligibility().isEligible());
    }

    @Test
    void validApprovalIsPreservedFromEligibilityRatherThanReimplemented() {
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.MANAGER_APPROVAL_REQUIRED, number("40")),
                policy.maxSnapshotAge(), policy.approvalValidity(), policy.authorizedManagerIds(), Map.of());
        var input = candidate("a", "38").eligibility();
        var approval = new OvertimeApproval("approval-1", "a", target.id(), PERIOD, number("4"),
                OvertimeApproval.Decision.APPROVED, "manager-1", NOW, "Coverage approved");
        var request = new ScoringRequest(new EligibilityRequest(input.employee(), target, List.of(), input.workHours(),
                policy, List.of(approval), NOW, NOW), Optional.empty());
        var result = engine.score(request, config);
        assertEquals(new EligibilityEngine().evaluate(request.eligibility()), result.eligibility());
        assertEquals(ELIGIBLE, result.eligibility().status());
    }

    @Test
    void malformedOrMissingHoursAreExcludedBeforeScoringEvenWithPerfectHistory() {
        var input = candidate("a", "18").eligibility();
        var request = new ScoringRequest(new EligibilityRequest(input.employee(), target, List.of(), null,
                policy, List.of(), NOW, NOW), Optional.empty());
        var result = engine.score(request, config);
        assertEquals(INELIGIBLE, result.eligibility().status());
        assertTrue(result.overallScore().isEmpty());
    }

    @Test
    void sameInputsAlwaysProduceExactlyEqualResultsAndNoMutation() {
        var request = history(candidate("a", "18"), List.of(offer("timely", "timely"), offer("late", "late")), List.of());
        var first = engine.score(request, config);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, engine.score(request, config));
        }
        assertEquals(2, request.history().orElseThrow().offers().size());
        assertThrows(UnsupportedOperationException.class, () -> first.factors().clear());
        assertThrows(UnsupportedOperationException.class, () -> engine.rank(List.of(request), config).eligibleCandidates().clear());
    }

    @Test
    void responseAndFairnessAreIndependentOfEventListOrdering() {
        var first = history(candidate("a", "18"), List.of(offer("1", "late"), offer("2", "timely")), List.of());
        var second = history(candidate("a", "18"), List.of(offer("2", "timely"), offer("1", "late")), List.of());
        assertEquals(engine.score(first, config), engine.score(second, config));
    }

    @Test
    void roundedFactorsHaveExactWeightedContributionsWithoutHiddenTotalRounding() {
        var request = history(candidate("a", "18"), List.of(offer("1", "timely"), offer("2", "late")), List.of());
        var result = engine.score(request, config);
        assertNumber("33.333333", factor(result, Factor.FAIRNESS_HISTORY).score());
        assertNumber("8.33333325", factor(result, Factor.FAIRNESS_HISTORY).contribution());
        assertNumber("57.83333325", result.overallScore().orElseThrow());
    }

    @Test
    void displayNameNeverAffectsScoreOrExplanation() {
        var request = candidate("a", "18");
        var input = request.eligibility();
        var old = input.employee();
        var renamed = new Employee(old.id(), "Different display name", old.status(), old.qualifications(), old.availability());
        var other = new ScoringRequest(new EligibilityRequest(renamed, target, input.assignments(), input.workHours(),
                policy, List.of(), NOW, NOW), Optional.empty());
        assertEquals(engine.score(request, config), engine.score(other, config));
    }

    @Test
    void resultBoundaryRejectsUnboundedOrNonReconcilingOutput() {
        var result = engine.score(candidate("a", "18"), config);
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult("a", result.eligibility(),
                Optional.of(number("101")), result.factors()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult("a", result.eligibility(),
                Optional.of(number("-1")), result.factors()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult("a", result.eligibility(),
                Optional.of(number("80")), result.factors()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult("a", result.eligibility(),
                result.overallScore(), List.of()));
        var excluded = new EligibilityResult(INELIGIBLE, List.of(new EligibilityResult.Reason(
                EligibilityResult.Code.INACTIVE_EMPLOYEE, "Inactive employee")), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult("a", excluded,
                result.overallScore(), result.factors()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringResult.FactorScore(Factor.RESPONSE_HISTORY,
                number("100"), number("15"), number("14"), DataStatus.AVAILABLE, "Incorrect contribution"));
    }

    @Test
    void rankingRejectsMixedEvaluationTimes() {
        var first = candidate("a", "18");
        var input = candidate("b", "18").eligibility();
        var later = new ScoringRequest(new EligibilityRequest(input.employee(), target, input.assignments(), input.workHours(),
                policy, List.of(), NOW.plusSeconds(1), NOW.plusSeconds(1)), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> engine.rank(List.of(first, later), config));
    }

    @Test
    void rankingRejectsDuplicateEmployeeIdsAndMixedComparisonContexts() {
        var first = candidate("a", "18");
        assertThrows(IllegalArgumentException.class, () -> engine.rank(List.of(first, candidate("a", "20")), config));
        target = shift("other-target", "2026-10-23T21:00:00Z", "2026-10-24T03:00:00Z", null);
        var differentTarget = candidate("b", "18");
        assertThrows(IllegalArgumentException.class, () -> engine.rank(List.of(first, differentTarget), config));
        target = first.eligibility().targetShift();
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.PERMITTED, number("36")),
                policy.maxSnapshotAge(), policy.approvalValidity(), policy.authorizedManagerIds(), Map.of());
        var differentLimit = candidate("b", "18");
        assertThrows(IllegalArgumentException.class, () -> engine.rank(List.of(first, differentLimit), config));
    }

    @Test
    void emptyRankingIsValidAndNullApiInputsAreRejected() {
        assertEquals(new ScoringResult.Ranking(List.of(), List.of(), List.of()), engine.rank(List.of(), config));
        assertThrows(NullPointerException.class, () -> engine.rank(null, config));
        assertThrows(NullPointerException.class, () -> engine.score(null, config));
        assertThrows(NullPointerException.class, () -> engine.score(candidate("a", "18"), null));
        assertThrows(NullPointerException.class, () -> new ScoringRequest(candidate("a", "18").eligibility(), null));
    }

    @Test
    void historyValidationRejectsFabricatedShapesAndDuplicateRecords() {
        var window = new TimeWindow(NOW.minus(config.historyLookback()), NOW);
        var event = offer("offer-1", "timely");
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory("a", window, List.of(event, event), List.of()));
        var assignment = new ScoringHistory.AssignmentEvent("assignment-1", NOW.minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory("a", window, List.of(), List.of(assignment, assignment)));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory("a", window,
                List.of(new ScoringHistory.OfferEvent("outside", window.start().minusSeconds(1), NOW, Optional.empty())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory("a", window,
                List.of(), List.of(new ScoringHistory.AssignmentEvent("at-end", NOW))));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory.OfferEvent("id", NOW, NOW, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory.OfferEvent("id", NOW, NOW.plusSeconds(1), Optional.of(NOW.minusSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> new ScoringHistory.AssignmentEvent(" ", NOW));
    }

    @Test
    void historySnapshotsDefensivelyCopyEventLists() {
        var offers = new ArrayList<>(List.of(offer("offer-1", "timely")));
        var assignments = new ArrayList<>(List.of(new ScoringHistory.AssignmentEvent("a1", NOW.minusSeconds(1))));
        var request = history(candidate("a", "18"), offers, assignments);
        offers.clear();
        assignments.clear();
        assertEquals(1, request.history().orElseThrow().offers().size());
        assertEquals(1, request.history().orElseThrow().assignments().size());
        assertThrows(UnsupportedOperationException.class, () -> request.history().orElseThrow().offers().clear());
    }

    @ParameterizedTest
    @EnumSource(Factor.class)
    void everyFactorCanBeUsedAloneWithOtherWeightsDisabled(Factor selected) {
        var result = engine.score(candidate("a", "18"), only(selected, Duration.ZERO));
        assertEquals(4, result.factors().size());
        assertNumber("100", factor(result, selected).weight());
        assertEquals(1, result.factors().stream().filter(value -> value.weight().signum() > 0).count());
    }
}
