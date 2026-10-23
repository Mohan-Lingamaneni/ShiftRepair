package com.shiftrepair.service;

import com.shiftrepair.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.shiftrepair.service.RecoveryService.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    private static final TimeWindow PERIOD = new TimeWindow(Instant.parse("2026-10-19T05:00:00Z"),
            Instant.parse("2026-10-26T05:00:00Z"));
    private static final Qualification ROLE = new Qualification(Qualification.Type.ROLE, "cashier");
    private MutableClock clock;
    private RecoveryService service;
    private EligibilityPolicy policy;
    private RecoveryConfig config;
    private Shift target;

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
        void set(Instant instant) { time.set(instant); }
    }

    @BeforeEach
    void setup() {
        clock = new MutableClock();
        service = new RecoveryService(clock, Set.of("manager-1"));
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED, number("40")),
                Duration.ofMinutes(15), Duration.ofHours(1), Set.of("manager-1"), Map.of());
        config = new RecoveryConfig(Duration.ofMinutes(30), ScoringConfig.provisionalExample());
        target = shift("shift-1", "2026-10-23T21:00:00Z", "2026-10-24T03:00:00Z");
    }

    private BigDecimal number(String value) { return new BigDecimal(value); }

    private Shift shift(String id, String start, String end) {
        return new Shift(id, "store-1", new TimeWindow(Instant.parse(start), Instant.parse(end)), Set.of(ROLE), Optional.empty());
    }

    private ScoringRequest candidate(String id, Shift shift, String actual) {
        var employee = new Employee(id, "Synthetic employee", Employee.EmploymentStatus.ACTIVE,
                Set.of(new EmployeeQualification(ROLE, Optional.empty())), new Availability(List.of(policy.accountingPeriod())));
        return new ScoringRequest(new EligibilityRequest(employee, shift, List.of(),
                new WorkHourSummary(id, policy.accountingPeriod(), BigDecimal.ZERO, number(actual)), policy,
                List.of(), clock.instant(), clock.instant()), Optional.empty());
    }

    private ScoringRequest candidate(String id) { return candidate(id, target, "18"); }

    private ScoringRequest withEmployee(ScoringRequest request, Employee employee) {
        var input = request.eligibility();
        return new ScoringRequest(new EligibilityRequest(employee, input.targetShift(), input.assignments(), input.workHours(),
                input.policy(), input.approvals(), input.verifiedThrough(), input.evaluatedAt()), request.history());
    }

    private ScoringRequest inactive(ScoringRequest request) {
        var old = request.eligibility().employee();
        return withEmployee(request, new Employee(old.id(), old.name(), Employee.EmploymentStatus.INACTIVE,
                old.qualifications(), old.availability()));
    }

    private RecoveryService.Result create(String id, Shift shift, List<ScoringRequest> candidates) {
        return service.create(id, "manager-1", shift, policy, candidates, config);
    }

    private RecoveryView view(RecoveryService.Result result) { return result.recovery().orElseThrow(); }
    private RecoveryView current(String id) { return view(service.get("manager-1", id)); }
    private RecoveryView.Offer offer(String employeeId) { return service.pendingOffers(employeeId).getFirst(); }

    private void approvalPolicy() {
        policy = new EligibilityPolicy(policy.accountingPeriod(),
                new OvertimePolicy(OvertimePolicy.Mode.MANAGER_APPROVAL_REQUIRED, number("40")),
                policy.maxSnapshotAge(), policy.approvalValidity(), policy.authorizedManagerIds(), Map.of());
    }

    private ScoringRequest approve(ScoringRequest request, String amount) {
        var input = request.eligibility();
        var approval = new OvertimeApproval("approval-1", input.employee().id(), input.targetShift().id(), input.policy().accountingPeriod(),
                number(amount), OvertimeApproval.Decision.APPROVED, "manager-1", clock.instant(), "Manager recorded authorization");
        return new ScoringRequest(new EligibilityRequest(input.employee(), input.targetShift(), input.assignments(), input.workHours(),
                input.policy(), List.of(approval), input.verifiedThrough(), input.evaluatedAt()), request.history());
    }

    @Test
    void creationBroadcastsToAllEligibleCandidatesAtSameTimeAndPreservesScores() {
        var a = candidate("a", target, "0");
        var b = candidate("b", target, "30");
        var result = create("recovery-1", target, List.of(b, a));
        assertEquals(CREATED, result.code());
        var recovery = view(result);
        assertEquals(RecoveryRequest.Status.OPEN, recovery.recovery().status());
        assertEquals(2, recovery.offers().size());
        assertEquals(List.of("a", "b"), recovery.offers().stream().map(RecoveryView.Offer::employeeId).toList());
        assertTrue(recovery.offers().stream().allMatch(value -> value.createdAt().equals(NOW)
                && value.expiresAt().equals(NOW.plusSeconds(1800)) && value.status() == ShiftOffer.Status.PENDING));
        assertEquals(new ScoringEngine().score(a, config.scoring()), recovery.offers().getFirst().scoring());
        assertEquals(new ScoringEngine().score(b, config.scoring()), recovery.offers().getLast().scoring());
        assertTrue(recovery.assignment().isEmpty());
    }

    @Test
    void duplicateActiveShiftAndReusedRecoveryIdAreRejectedWithoutReplacingState() {
        create("recovery-1", target, List.of(candidate("a")));
        var original = current("recovery-1");
        assertEquals(DUPLICATE_RECOVERY, create("recovery-2", target, List.of(candidate("b"))).code());
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        assertEquals(DUPLICATE_RECOVERY, create("recovery-1", other, List.of(candidate("b", other, "18"))).code());
        assertEquals(original, current("recovery-1"));
    }

    @Test
    void inactiveUnqualifiedAndUnavailableEmployeesReceiveNoOffers() {
        var active = candidate("a");
        var unqualified = candidate("b");
        var old = unqualified.eligibility().employee();
        unqualified = withEmployee(unqualified, new Employee(old.id(), old.name(), old.status(), Set.of(), old.availability()));
        var unavailable = candidate("c");
        old = unavailable.eligibility().employee();
        unavailable = withEmployee(unavailable, new Employee(old.id(), old.name(), old.status(), old.qualifications(), new Availability(List.of())));
        var result = create("r1", target, List.of(active, unqualified, unavailable, inactive(candidate("d"))));
        assertEquals(1, view(result).offers().size());
        assertEquals(4, view(result).candidates().size());
        assertTrue(service.pendingOffers("b").isEmpty());
        assertTrue(service.pendingOffers("c").isEmpty());
        assertTrue(service.pendingOffers("d").isEmpty());
        assertEquals(3, view(result).candidates().stream().filter(value -> value.eligibility().status() == EligibilityResult.Status.INELIGIBLE).count());
    }

    @Test
    void employeeInboxIsIsolatedAndReturnsImmutableDetachedSnapshots() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        var inbox = service.pendingOffers("a");
        assertEquals(1, inbox.size());
        assertEquals("a", inbox.getFirst().employeeId());
        assertTrue(service.pendingOffers("stranger").isEmpty());
        assertThrows(UnsupportedOperationException.class, inbox::clear);
        assertThrows(UnsupportedOperationException.class, () -> current("r1").offers().clear());
        var oldView = inbox.getFirst();
        service.decline("a", oldView.id());
        assertEquals(ShiftOffer.Status.PENDING, oldView.status());
        assertEquals(ShiftOffer.Status.DECLINED, current("r1").offers().getFirst().status());
    }

    @Test
    void firstAcceptanceWinsEvenWhenScoreIsLowerAndAllCompetitorsClose() {
        create("r1", target, List.of(candidate("a", target, "0"), candidate("b", target, "30"), candidate("c")));
        String aOffer = offer("a").id();
        String bOffer = offer("b").id();
        var result = service.accept("b", bOffer);
        assertEquals(ACCEPTED, result.code());
        var recovery = view(result);
        assertEquals(RecoveryRequest.Status.COVERED, recovery.recovery().status());
        assertEquals(Optional.of("b"), recovery.recovery().replacementEmployeeId());
        assertEquals(Optional.of("b"), recovery.assignment().orElseThrow().assignedEmployeeId());
        assertEquals(1, recovery.offers().stream().filter(value -> value.status() == ShiftOffer.Status.ACCEPTED).count());
        assertEquals(2, recovery.offers().stream().filter(value -> value.status() == ShiftOffer.Status.CLOSED).count());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertTrue(service.pendingOffers("b").isEmpty());
        assertEquals(ALREADY_FILLED, service.accept("a", aOffer).code());
        assertEquals(ALREADY_FILLED, service.accept("b", bOffer).code());
        assertEquals(1, service.assignmentsFor("b").size());
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test
    void decliningDoesNotAffectCompetitorsAndLastDeclineEscalatesOnce() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        var a = offer("a");
        assertEquals(DECLINED, service.decline("a", a.id()).code());
        assertEquals(OFFER_NOT_PENDING, service.accept("a", a.id()).code());
        assertEquals(OFFER_NOT_PENDING, service.decline("a", a.id()).code());
        assertEquals(RecoveryRequest.Status.OPEN, current("r1").recovery().status());
        assertEquals(1, service.pendingOffers("b").size());
        assertEquals(DECLINED, service.decline("b", offer("b").id()).code());
        assertEquals(RecoveryRequest.Status.ESCALATED, current("r1").recovery().status());
        assertEquals(1, current("r1").events().stream().filter(value -> value.type() == RecoveryView.EventType.ESCALATED).count());
    }

    @Test
    void exactOfferDeadlineExpiresAndEscalatesOnQuery() {
        create("r1", target, List.of(candidate("a")));
        var offered = offer("a");
        clock.set(offered.expiresAt());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(OFFER_EXPIRED, service.accept("a", offered.id()).code());
        assertEquals(OFFER_EXPIRED, service.decline("a", offered.id()).code());
        var recovery = current("r1");
        assertEquals(RecoveryRequest.Status.ESCALATED, recovery.recovery().status());
        assertEquals(ShiftOffer.Status.EXPIRED, recovery.offers().getFirst().status());
        assertEquals(1, recovery.events().stream().filter(value -> value.type() == RecoveryView.EventType.OFFER_EXPIRED).count());
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test
    void configuredDeadlineIsCappedAtShiftStart() {
        config = new RecoveryConfig(Duration.ofDays(100), config.scoring());
        create("r1", target, List.of(candidate("a")));
        var offered = offer("a");
        assertEquals(target.window().start(), offered.expiresAt());
        clock.set(target.window().start());
        assertEquals(OFFER_EXPIRED, service.accept("a", offered.id()).code());
    }

    @Test
    void managerCancellationClosesOffersAndPreventsAcceptance() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        String offered = offer("a").id();
        var result = service.cancel("manager-1", "r1");
        assertEquals(CANCELLED, result.code());
        assertEquals(RecoveryRequest.Status.CANCELLED, view(result).recovery().status());
        assertTrue(view(result).offers().stream().allMatch(value -> value.status() == ShiftOffer.Status.CLOSED));
        assertEquals(RECOVERY_CANCELLED, service.accept("a", offered).code());
        assertEquals(RECOVERY_CANCELLED, service.cancel("manager-1", "r1").code());
        assertEquals(RECOVERY_CANCELLED, service.reevaluate("manager-1", "r1", List.of(candidate("a"))).code());
        assertTrue(service.assignmentsFor("a").isEmpty());
        assertEquals(CREATED, create("r2", target, List.of(candidate("a"))).code());
    }

    @Test
    void emptyCandidateSetEscalatesButStillReservesActiveRecovery() {
        var result = create("r1", target, List.of());
        assertEquals(CREATED, result.code());
        assertEquals(RecoveryRequest.Status.ESCALATED, view(result).recovery().status());
        assertTrue(view(result).offers().isEmpty());
        assertEquals(DUPLICATE_RECOVERY, create("r2", target, List.of(candidate("a"))).code());
        assertEquals(CANCELLED, service.cancel("manager-1", "r1").code());
    }

    @Test
    void noEligibleCandidatesEscalatesWithReasonsForManager() {
        var result = create("r1", target, List.of(inactive(candidate("a"))));
        assertEquals(RecoveryRequest.Status.ESCALATED, view(result).recovery().status());
        assertTrue(view(result).offers().isEmpty());
        assertEquals(EligibilityResult.Code.INACTIVE_EMPLOYEE, view(result).candidates().getFirst().eligibility().reasons().getFirst().code());
    }

    @Test
    void conditionalOvertimeCandidateIsVisibleToManagerButHasNoClaimableOffer() {
        approvalPolicy();
        var result = create("r1", target, List.of(candidate("a", target, "38")));
        assertEquals(RecoveryRequest.Status.ESCALATED, view(result).recovery().status());
        assertEquals(EligibilityResult.Status.REQUIRES_APPROVAL, view(result).candidates().getFirst().eligibility().status());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertTrue(view(result).candidates().getFirst().overallScore().isPresent());
    }

    @Test
    void mixedConditionalAndEligibleCandidatesOnlyBroadcastToEligible() {
        approvalPolicy();
        var result = create("r1", target, List.of(candidate("a", target, "38"), candidate("b")));
        assertEquals(RecoveryRequest.Status.OPEN, view(result).recovery().status());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(1, service.pendingOffers("b").size());
        assertEquals(2, view(result).candidates().size());
    }

    @Test
    void approvedCandidateGetsOfferAndCanAcceptUsingExistingApprovalLogic() {
        approvalPolicy();
        var approved = approve(candidate("a", target, "38"), "4");
        create("r1", target, List.of(approved));
        var result = service.accept("a", offer("a").id());
        assertEquals(ACCEPTED, result.code());
        assertTrue(result.eligibility().orElseThrow().reasons().stream().anyMatch(value -> value.code() == EligibilityResult.Code.APPROVAL_VALID));
    }

    @Test
    void validManagerApprovalRefreshCreatesFirstOfferAndReopensEscalation() {
        approvalPolicy();
        create("r1", target, List.of(candidate("a", target, "38")));
        clock.set(NOW.plusSeconds(1));
        var approved = approve(candidate("a", target, "38"), "4");
        var result = service.reevaluate("manager-1", "r1", List.of(approved));
        assertEquals(REEVALUATED, result.code());
        assertEquals(RecoveryRequest.Status.OPEN, view(result).recovery().status());
        assertEquals(1, service.pendingOffers("a").size());
        assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
        assertTrue(current("r1").events().stream().anyMatch(value -> value.type() == RecoveryView.EventType.REOPENED));
    }

    @Test
    void insufficientApprovalDoesNotGenerateActionableOffer() {
        approvalPolicy();
        create("r1", target, List.of(candidate("a", target, "38")));
        var result = service.reevaluate("manager-1", "r1", List.of(approve(candidate("a", target, "38"), "3")));
        assertEquals(REEVALUATED, result.code());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(EligibilityResult.Status.REQUIRES_APPROVAL, view(result).candidates().getFirst().eligibility().status());
    }

    @Test
    void approvalThatNoLongerCoversProjectionCannotBeAccepted() {
        approvalPolicy();
        create("r1", target, List.of(approve(candidate("a", target, "38"), "4")));
        var offered = offer("a");
        clock.set(NOW.plusSeconds(1)); // Conservative unverified time increases required overtime.
        var result = service.accept("a", offered.id());
        assertEquals(ELIGIBILITY_CHANGED, result.code());
        assertEquals(EligibilityResult.Status.REQUIRES_APPROVAL, result.eligibility().orElseThrow().status());
        assertEquals(ShiftOffer.Status.CLOSED, view(result).offers().getFirst().status());
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test
    void expiredApprovalCannotBeAcceptedEvenWhenOfferIsStillPending() {
        approvalPolicy();
        policy = new EligibilityPolicy(PERIOD, policy.overtime(), Duration.ofMinutes(15), Duration.ofSeconds(30),
                policy.authorizedManagerIds(), Map.of());
        create("r1", target, List.of(approve(candidate("a", target, "38"), "10")));
        var offered = offer("a");
        clock.set(NOW.plusSeconds(30));
        var result = service.accept("a", offered.id());
        assertEquals(ELIGIBILITY_CHANGED, result.code());
        assertTrue(result.eligibility().orElseThrow().reasons().stream().anyMatch(value -> value.code() == EligibilityResult.Code.APPROVAL_EXPIRED));
    }

    @Test
    void staleSnapshotIsRecheckedAtAcceptanceAndNeverAssigned() {
        create("r1", target, List.of(candidate("a")));
        var offered = offer("a");
        clock.set(NOW.plusSeconds(901));
        var result = service.accept("a", offered.id());
        assertEquals(ELIGIBILITY_CHANGED, result.code());
        assertEquals(EligibilityResult.Code.STALE_DATA, result.eligibility().orElseThrow().reasons().getFirst().code());
        assertEquals(RecoveryRequest.Status.ESCALATED, view(result).recovery().status());
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test
    void managerRefreshRevokesPendingOffersWhenEmployeeBecomesInactive() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        var oldOffer = offer("a");
        service.reevaluate("manager-1", "r1", List.of(inactive(candidate("a")), candidate("b")));
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(OFFER_NOT_PENDING, service.accept("a", oldOffer.id()).code());
        assertEquals(1, service.pendingOffers("b").size());
    }

    @Test
    void removingCandidateOnRefreshWithdrawsTheirPendingOffer() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        service.reevaluate("manager-1", "r1", List.of(candidate("b")));
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(1, service.pendingOffers("b").size());
    }

    @Test
    void refreshDoesNotDuplicatePendingOffersOrSilentlyReofferDeclinedOnes() {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        String first = offer("b").id();
        service.decline("a", offer("a").id());
        service.reevaluate("manager-1", "r1", List.of(candidate("a"), candidate("b"), candidate("c")));
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(first, offer("b").id());
        assertEquals(1, service.pendingOffers("c").size());
        assertEquals(3, current("r1").offers().size());
    }

    @Test
    void refreshDoesNotReviveExpiredOffersButCanNotifyNewCandidates() {
        create("r1", target, List.of(candidate("a")));
        clock.set(NOW.plusSeconds(1800));
        var result = service.reevaluate("manager-1", "r1", List.of(candidate("a"), candidate("b")));
        assertEquals(RecoveryRequest.Status.OPEN, view(result).recovery().status());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(1, service.pendingOffers("b").size());
    }

    @Test
    void managerAuthorizationAndOfferOwnershipAreCheckedWithoutLeakingOtherInbox() {
        assertEquals(NOT_AUTHORIZED, service.create("r1", "outsider", target, policy, List.of(candidate("a")), config).code());
        create("r1", target, List.of(candidate("a")));
        var offered = offer("a");
        var acceptance = service.accept("stranger", offered.id());
        assertEquals(NOT_OFFERED, acceptance.code());
        assertTrue(acceptance.recovery().isEmpty());
        assertEquals(NOT_OFFERED, service.decline("stranger", offered.id()).code());
        assertEquals(NOT_AUTHORIZED, service.get("outsider", "r1").code());
        assertEquals(NOT_AUTHORIZED, service.cancel("outsider", "r1").code());
        assertEquals(NOT_AUTHORIZED, service.reevaluate("outsider", "r1", List.of()).code());
        assertEquals(NOT_AUTHORIZED, service.cancel(null, "r1").code());
        assertEquals(1, service.pendingOffers("a").size());
    }

    @Test
    void missingOffersAndRecoveriesReturnClearResults() {
        assertEquals(NOT_FOUND, service.accept("a", "unknown").code());
        assertEquals(NOT_FOUND, service.decline("a", "unknown").code());
        assertEquals(NOT_FOUND, service.cancel("manager-1", "unknown").code());
        assertEquals(NOT_FOUND, service.get("manager-1", "unknown").code());
        assertEquals(NOT_FOUND, service.reevaluate("manager-1", "unknown", List.of()).code());
    }

    @Test
    void assignedShiftCannotBeRecoveredAgainEvenWithNewId() {
        create("r1", target, List.of(candidate("a")));
        service.accept("a", offer("a").id());
        assertEquals(ALREADY_FILLED, create("r2", target, List.of(candidate("b"))).code());
        assertEquals(ALREADY_FILLED, service.cancel("manager-1", "r1").code());
        assertEquals(ALREADY_FILLED, service.reevaluate("manager-1", "r1", List.of()).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"assigned", "started", "outside-period", "blank-id", "duplicate-candidate", "wrong-target", "missing-candidates"})
    void invalidCreationDoesNotPublishPartialState(String kind) {
        Shift shift = target;
        String id = "r1";
        List<ScoringRequest> candidates = List.of(candidate("a"));
        switch (kind) {
            case "assigned" -> shift = new Shift(target.id(), target.locationId(), target.window(), target.requiredQualifications(), Optional.of("original"));
            case "started" -> shift = shift("shift-1", "2026-10-23T14:00:00Z", "2026-10-23T20:00:00Z");
            case "outside-period" -> shift = shift("shift-1", "2026-10-26T03:00:00Z", "2026-10-26T07:00:00Z");
            case "blank-id" -> id = " ";
            case "duplicate-candidate" -> candidates = List.of(candidate("a"), candidate("a"));
            case "wrong-target" -> candidates = List.of(candidate("a", shift("wrong", "2026-10-23T21:00:00Z", "2026-10-24T03:00:00Z"), "18"));
            case "missing-candidates" -> candidates = null;
            default -> throw new AssertionError(kind);
        }
        assertEquals(INVALID_REQUEST, create(id, shift, candidates).code());
        assertEquals(NOT_FOUND, service.get("manager-1", id).code());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(CREATED, create("r1", target, List.of(candidate("a"))).code());
    }

    @Test
    void invalidRefreshDoesNotReplaceExistingCandidateStateOrOffers() {
        create("r1", target, List.of(candidate("a")));
        var before = current("r1");
        assertEquals(INVALID_REQUEST, service.reevaluate("manager-1", "r1", List.of(candidate("a"), candidate("a"))).code());
        assertEquals(before, current("r1"));
    }

    @Test
    void slightlyEarlierCallerEvaluationTimeIsSafelyRebasedToServiceClock() {
        var input = candidate("a");
        clock.set(NOW.plusSeconds(1));
        assertEquals(CREATED, create("r1", target, List.of(input)).code());
        assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
    }

    @Test
    void futureDatedCandidateRequestIsRejected() {
        clock.set(NOW.plusSeconds(1));
        var future = candidate("a");
        clock.set(NOW);
        assertEquals(INVALID_REQUEST, create("r1", target, List.of(future)).code());
    }

    @Test
    void overnightAssignmentUsesAbsoluteShiftInstants() {
        create("r1", target, List.of(candidate("a")));
        var result = service.accept("a", offer("a").id());
        var assigned = view(result).assignment().orElseThrow();
        assertEquals(target.window(), assigned.window());
        assertEquals(6, assigned.window().duration().toHours());
        assertEquals(Instant.parse("2026-10-24T03:00:00Z"), assigned.window().end());
    }

    @Test
    void allImportantTransitionsHaveTimestampedEventsAndDoNotMutateOldSnapshots() {
        var original = view(create("r1", target, List.of(candidate("a"), candidate("b"))));
        clock.set(NOW.plusSeconds(1));
        service.decline("a", offer("a").id());
        clock.set(NOW.plusSeconds(2));
        service.accept("b", offer("b").id());
        var events = current("r1").events();
        assertEquals(RecoveryView.EventType.CREATED, events.getFirst().type());
        assertEquals(RecoveryView.EventType.FILLED, events.getLast().type());
        assertEquals(NOW.plusSeconds(2), events.getLast().at());
        assertTrue(events.stream().anyMatch(value -> value.type() == RecoveryView.EventType.OFFER_DECLINED && value.at().equals(NOW.plusSeconds(1))));
        assertEquals(RecoveryRequest.Status.OPEN, original.recovery().status());
        assertTrue(original.offers().stream().allMatch(value -> value.status() == ShiftOffer.Status.PENDING));
        assertThrows(UnsupportedOperationException.class, () -> current("r1").events().clear());
    }

    @RepeatedTest(10)
    void concurrentAcceptancesProduceExactlyOneWinner() throws Exception {
        create("r1", target, List.of(candidate("a"), candidate("b")));
        String a = offer("a").id();
        String b = offer("b").id();
        var results = race(() -> service.accept("a", a), () -> service.accept("b", b));
        assertEquals(1, results.stream().filter(value -> value.code() == ACCEPTED).count());
        assertEquals(1, results.stream().filter(value -> value.code() == ALREADY_FILLED).count());
        assertEquals(1, service.assignmentsFor("a").size() + service.assignmentsFor("b").size());
        assertEquals(1, current("r1").offers().stream().filter(value -> value.status() == ShiftOffer.Status.ACCEPTED).count());
        assertTrue(current("r1").offers().stream().noneMatch(value -> value.status() == ShiftOffer.Status.PENDING));
    }

    @RepeatedTest(5)
    void concurrentRecoveryCreationReservesShiftExactlyOnce() throws Exception {
        var input = List.of(candidate("a"));
        var results = race(() -> create("r1", target, input), () -> create("r2", target, input));
        assertEquals(1, results.stream().filter(value -> value.code() == CREATED).count());
        assertEquals(1, results.stream().filter(value -> value.code() == DUPLICATE_RECOVERY).count());
        assertEquals(1, service.pendingOffers("a").size());
    }

    @RepeatedTest(5)
    void concurrentCancellationAndAcceptanceHaveOneConsistentOutcome() throws Exception {
        create("r1", target, List.of(candidate("a")));
        String id = offer("a").id();
        var results = race(() -> service.cancel("manager-1", "r1"), () -> service.accept("a", id));
        var current = current("r1");
        if (current.recovery().status() == RecoveryRequest.Status.COVERED) {
            assertEquals(1, service.assignmentsFor("a").size());
            assertTrue(results.stream().anyMatch(value -> value.code() == ACCEPTED));
            assertTrue(results.stream().anyMatch(value -> value.code() == ALREADY_FILLED));
        } else {
            assertEquals(RecoveryRequest.Status.CANCELLED, current.recovery().status());
            assertTrue(service.assignmentsFor("a").isEmpty());
            assertTrue(results.stream().anyMatch(value -> value.code() == CANCELLED));
            assertTrue(results.stream().anyMatch(value -> value.code() == RECOVERY_CANCELLED));
        }
        assertTrue(service.pendingOffers("a").isEmpty());
    }

    @RepeatedTest(5)
    void concurrentClaimsAcrossDifferentRecoveriesCannotDoubleBookEmployee() throws Exception {
        var other = shift("shift-2", "2026-10-23T23:00:00Z", "2026-10-24T05:00:00Z");
        create("r1", target, List.of(candidate("a")));
        create("r2", other, List.of(candidate("a", other, "18")));
        var inbox = service.pendingOffers("a");
        var results = race(() -> service.accept("a", inbox.getFirst().id()), () -> service.accept("a", inbox.getLast().id()));
        assertEquals(1, results.stream().filter(value -> value.code() == ACCEPTED).count());
        assertEquals(1, results.stream().filter(value -> value.code() == ELIGIBILITY_CHANGED).count());
        assertEquals(1, service.assignmentsFor("a").size());
        assertTrue(service.pendingOffers("a").isEmpty());
    }

    @Test
    void nonOverlappingLocalAssignmentsStillCountTowardOvertimeAtSecondAcceptance() {
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        create("r1", target, List.of(candidate("a", target, "30")));
        create("r2", other, List.of(candidate("a", other, "30")));
        var inbox = service.pendingOffers("a");
        assertEquals(ACCEPTED, service.accept("a", inbox.getFirst().id()).code());
        var second = service.accept("a", inbox.getLast().id());
        assertEquals(ELIGIBILITY_CHANGED, second.code());
        assertTrue(second.eligibility().orElseThrow().reasons().stream().anyMatch(value -> value.code() == EligibilityResult.Code.OVERTIME_PROHIBITED));
        assertEquals(1, service.assignmentsFor("a").size());
    }

    @Test
    void refreshedSourceIncludingLocalAssignmentDoesNotDoubleCountIt() {
        create("r1", target, List.of(candidate("a")));
        service.accept("a", offer("a").id());
        var assigned = service.assignmentsFor("a").getFirst();
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        var input = candidate("a", other, "18").eligibility();
        var updated = new ScoringRequest(new EligibilityRequest(input.employee(), other, List.of(assigned),
                new WorkHourSummary("a", PERIOD, number("6"), number("18")), policy, List.of(), NOW, NOW), Optional.empty());
        create("r2", other, List.of(updated));
        var result = service.accept("a", offer("a").id());
        assertEquals(ACCEPTED, result.code());
        assertEquals(0, number("30").compareTo(result.eligibility().orElseThrow().projectedHours().orElseThrow()));
        assertEquals(2, service.assignmentsFor("a").size());
    }

    @Test
    void conflictingSourceVersionOfLocalAssignmentIsRejectedRatherThanOverwritten() {
        create("r1", target, List.of(candidate("a")));
        service.accept("a", offer("a").id());
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        var conflicting = new Shift(target.id(), "different-store", target.window(), target.requiredQualifications(), Optional.of("a"));
        var input = candidate("a", other, "18").eligibility();
        var updated = new ScoringRequest(new EligibilityRequest(input.employee(), other, List.of(conflicting),
                new WorkHourSummary("a", PERIOD, number("6"), number("18")), policy, List.of(), NOW, NOW), Optional.empty());
        var result = create("r2", other, List.of(updated));
        assertTrue(view(result).offers().isEmpty());
        assertEquals(EligibilityResult.Code.INVALID_SCHEDULE, view(result).candidates().getFirst().eligibility().reasons().getFirst().code());
        assertEquals("store-1", service.assignmentsFor("a").getFirst().locationId());
    }

    @Test
    void shortLocalAssignmentDoesNotMakeSubsequentValidHoursContradictory() {
        target = shift("shift-1", "2026-10-23T21:00:00Z", "2026-10-23T21:00:01Z");
        create("r1", target, List.of(candidate("a")));
        assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        var result = create("r2", other, List.of(candidate("a", other, "18")));
        assertEquals(1, view(result).offers().size());
        assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
        assertEquals(2, service.assignmentsFor("a").size());
    }

    @Test
    void multipleFractionalLocalAssignmentsRoundOnlyAfterSummingDurations() {
        for (int i = 0; i < 5; i++) {
            Instant start = NOW.plusSeconds(21600 + i * 2);
            var shortShift = new Shift("short-" + i, "store-1", new TimeWindow(start, start.plusSeconds(1)), Set.of(ROLE), Optional.empty());
            assertEquals(CREATED, create("r-" + i, shortShift, List.of(candidate("a", shortShift, "18"))).code());
            assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
        }
        assertEquals(5, service.assignmentsFor("a").size());
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        var result = create("r-next", other, List.of(candidate("a", other, "18")));
        assertEquals(1, view(result).offers().size());
        assertEquals(ACCEPTED, service.accept("a", offer("a").id()).code());
    }

    @Test
    void invalidSourceHoursAreNotConcealedByMergingLocalAssignments() {
        create("r1", target, List.of(candidate("a")));
        service.accept("a", offer("a").id());
        var other = shift("shift-2", "2026-10-24T21:00:00Z", "2026-10-25T03:00:00Z");
        var input = candidate("a", other, "18").eligibility();
        var contradictory = new ScoringRequest(new EligibilityRequest(input.employee(), other, List.of(),
                new WorkHourSummary("a", PERIOD, number("9"), number("18")), policy, List.of(), NOW, NOW), Optional.empty());
        var result = create("r2", other, List.of(contradictory));
        assertTrue(view(result).offers().isEmpty());
        assertEquals(EligibilityResult.Code.CONTRADICTORY_HOURS, view(result).candidates().getFirst().eligibility().reasons().getFirst().code());
    }

    @RepeatedTest(5)
    void concurrentRepeatsOfSameOfferCannotCreateMultipleAssignments() throws Exception {
        create("r1", target, List.of(candidate("a")));
        String id = offer("a").id();
        var results = race(() -> service.accept("a", id), () -> service.accept("a", id));
        assertEquals(1, results.stream().filter(value -> value.code() == ACCEPTED).count());
        assertEquals(1, results.stream().filter(value -> value.code() == ALREADY_FILLED).count());
        assertEquals(1, service.assignmentsFor("a").size());
    }

    @Test
    void invalidConfigurationsAndBackwardClockFailBeforeAssignment() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryConfig(Duration.ZERO, config.scoring()));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryConfig(Duration.ofSeconds(-1), config.scoring()));
        create("r1", target, List.of(candidate("a")));
        var offered = offer("a");
        clock.set(NOW.minusSeconds(1));
        assertThrows(IllegalStateException.class, () -> service.accept("a", offered.id()));
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test start timed out");
                }
                return task.call();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<T>();
            for (var task : tasks) {
                results.add(task.get(5, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
