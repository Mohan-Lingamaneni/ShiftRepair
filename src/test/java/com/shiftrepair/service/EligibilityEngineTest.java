package com.shiftrepair.service;

import com.shiftrepair.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.shiftrepair.service.EligibilityResult.Code.*;
import static com.shiftrepair.service.EligibilityResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class EligibilityEngineTest {
    private static final Instant PERIOD_START = Instant.parse("2026-10-19T05:00:00Z");
    private static final TimeWindow PERIOD = new TimeWindow(PERIOD_START, PERIOD_START.plusSeconds(7 * 86400));
    private static final Qualification CASHIER = new Qualification(Qualification.Type.ROLE, "cashier");
    private static final Qualification LEAD = new Qualification(Qualification.Type.ROLE, "shift-lead");
    private static final Qualification FOOD = new Qualification(Qualification.Type.CERTIFICATION, "food-safety");
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    private final EligibilityEngine engine = new EligibilityEngine();
    private Employee employee;
    private Shift target;
    private List<Shift> assignments;
    private WorkHourSummary hours;
    private EligibilityPolicy policy;
    private List<OvertimeApproval> approvals;
    private Instant cutoff;
    private Instant now;

    @BeforeEach
    void setup() {
        now = NOW;
        cutoff = now;
        target = shift("coverage-1", "2026-10-23T21:00:00Z", "2026-10-24T03:00:00Z", null);
        employee = new Employee("employee-7", "Alex Rivera", Employee.EmploymentStatus.ACTIVE,
                Set.of(new EmployeeQualification(CASHIER, Optional.empty())), new Availability(List.of(PERIOD)));
        assignments = List.of();
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("0"), decimal("24"));
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED, decimal("36")),
                Duration.ofMinutes(15), Duration.ofHours(2), Set.of("manager-2"), Map.of());
        approvals = List.of();
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }

    private Shift shift(String id, String start, String end, String assignee) {
        return new Shift(id, "store-3", new TimeWindow(Instant.parse(start), Instant.parse(end)),
                Set.of(CASHIER), Optional.ofNullable(assignee));
    }

    private EligibilityRequest request() {
        return new EligibilityRequest(employee, target, assignments, hours, policy, approvals, cutoff, now);
    }

    private EligibilityResult evaluate() { return engine.evaluate(request()); }

    private void expect(EligibilityResult.Status status, EligibilityResult.Code code) {
        var result = evaluate();
        assertEquals(status, result.status(), result.reasons().toString());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.code() == code), result.reasons().toString());
        assertEquals(status == ELIGIBLE, result.isEligible());
    }

    private void mode(OvertimePolicy.Mode mode) {
        policy = new EligibilityPolicy(policy.accountingPeriod(), new OvertimePolicy(mode, policy.overtime().regularHoursLimit()),
                policy.maxSnapshotAge(), policy.approvalValidity(), policy.authorizedManagerIds(), policy.qualificationSubstitutes());
    }

    private void actual(String value) {
        hours = new WorkHourSummary(employee.id(), PERIOD, hours.scheduledHours(), decimal(value));
    }

    private OvertimeApproval approval(String id, String employeeId, String shiftId, TimeWindow period,
                                      String amount, OvertimeApproval.Decision decision, String manager, Instant at) {
        return new OvertimeApproval(id, employeeId, shiftId, period, decimal(amount), decision, manager, at, "Coverage authorization");
    }

    private OvertimeApproval approved(String amount) {
        return approval("decision-1", employee.id(), target.id(), PERIOD, amount,
                OvertimeApproval.Decision.APPROVED, "manager-2", now);
    }

    private void overtime() {
        actual("34"); // Six-hour target -> 40; configured threshold is 36, not 40.
        mode(OvertimePolicy.Mode.MANAGER_APPROVAL_REQUIRED);
    }

    @Test
    void fullyEligibleWithSyntheticEmployeeAndNoAssignments() {
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("30").compareTo(evaluate().projectedHours().orElseThrow()));
        assertEquals(List.of(), assignments);
    }

    @Test
    void missingRequiredCertificationFailsEvenWhenRoleMatches() {
        target = new Shift(target.id(), target.locationId(), target.window(), Set.of(CASHIER, FOOD), Optional.empty());
        expect(INELIGIBLE, MISSING_QUALIFICATION);
    }

    @Test
    void wrongRoleIsRejectedWithoutExplicitEquivalence() {
        employee = new Employee(employee.id(), employee.name(), employee.status(),
                Set.of(new EmployeeQualification(LEAD, Optional.empty())), employee.availability());
        expect(INELIGIBLE, MISSING_QUALIFICATION);
    }

    @Test
    void equivalentValidQualificationSatisfiesRoleWithoutChangingEmployee() {
        employee = new Employee(employee.id(), employee.name(), employee.status(),
                Set.of(new EmployeeQualification(LEAD, Optional.of(target.window().end()))), employee.availability());
        policy = new EligibilityPolicy(PERIOD, policy.overtime(), policy.maxSnapshotAge(), policy.approvalValidity(),
                policy.authorizedManagerIds(), Map.of(CASHIER, Set.of(LEAD)));
        expect(ELIGIBLE, REQUIREMENTS_MET);
    }

    @Test
    void qualificationEquivalenceIsDirectionalNotTransitiveOrImplicit() {
        target = new Shift(target.id(), target.locationId(), target.window(), Set.of(LEAD), Optional.empty());
        policy = new EligibilityPolicy(PERIOD, policy.overtime(), policy.maxSnapshotAge(), policy.approvalValidity(),
                policy.authorizedManagerIds(), Map.of(CASHIER, Set.of(LEAD)));
        expect(INELIGIBLE, MISSING_QUALIFICATION);
    }

    @Test
    void expiryExactlyAtShiftEndPasses() {
        employee = new Employee(employee.id(), employee.name(), employee.status(),
                Set.of(new EmployeeQualification(CASHIER, Optional.of(target.window().end()))), employee.availability());
        expect(ELIGIBLE, REQUIREMENTS_MET);
    }

    @Test
    void expiryDuringShiftFails() {
        employee = new Employee(employee.id(), employee.name(), employee.status(),
                Set.of(new EmployeeQualification(CASHIER, Optional.of(target.window().end().minusNanos(1)))), employee.availability());
        expect(INELIGIBLE, QUALIFICATION_EXPIRED);
    }

    @Test
    void configuredSubstituteStillNeedsFullShiftValidity() {
        employee = new Employee(employee.id(), employee.name(), employee.status(),
                Set.of(new EmployeeQualification(LEAD, Optional.of(target.window().start()))), employee.availability());
        policy = new EligibilityPolicy(PERIOD, policy.overtime(), policy.maxSnapshotAge(), policy.approvalValidity(),
                policy.authorizedManagerIds(), Map.of(CASHIER, Set.of(LEAD)));
        expect(INELIGIBLE, QUALIFICATION_EXPIRED);
    }

    @Test
    void contradictoryQualificationExpiryRecordsFailClosed() {
        employee = new Employee(employee.id(), employee.name(), employee.status(), Set.of(
                new EmployeeQualification(CASHIER, Optional.empty()),
                new EmployeeQualification(CASHIER, Optional.of(now))), employee.availability());
        expect(INELIGIBLE, CONTRADICTORY_QUALIFICATIONS);
    }

    @Test
    void inactiveEmployeeCannotBeRescuedByApprovalOrPermittedOvertime() {
        employee = new Employee(employee.id(), employee.name(), Employee.EmploymentStatus.INACTIVE,
                employee.qualifications(), employee.availability());
        mode(OvertimePolicy.Mode.PERMITTED);
        approvals = List.of(approved("100"));
        expect(INELIGIBLE, INACTIVE_EMPLOYEE);
    }

    @Test
    void partialAvailabilityFailsAtNanosecondGap() {
        employee = new Employee(employee.id(), employee.name(), employee.status(), employee.qualifications(),
                new Availability(List.of(new TimeWindow(target.window().start().plusNanos(1), target.window().end()))));
        expect(INELIGIBLE, NOT_AVAILABLE);
    }

    @Test
    void adjacentAvailabilityWindowsCoverWholeShift() {
        var middle = target.window().start().plusSeconds(10800);
        employee = new Employee(employee.id(), employee.name(), employee.status(), employee.qualifications(), new Availability(List.of(
                new TimeWindow(middle, target.window().end()), new TimeWindow(target.window().start(), middle))));
        expect(ELIGIBLE, REQUIREMENTS_MET);
    }

    @Test
    void overnightOverlapIsDetectedUsingDates() {
        assignments = List.of(shift("s2", "2026-10-24T02:00:00Z", "2026-10-24T06:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("4"), decimal("24"));
        expect(INELIGIBLE, SHIFT_CONFLICT);
    }

    @Test
    void overnightAdjacentAssignmentsAreAllowedAndIncludedInProjection() {
        assignments = List.of(shift("s2", "2026-10-24T03:00:00Z", "2026-10-24T07:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("4"), decimal("24"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("34").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void adjacentBeforeTargetAndExactThresholdAreEligible() {
        assignments = List.of(shift("s2", "2026-10-23T15:00:00Z", "2026-10-23T21:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("6"), decimal("24"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("36").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void overtimeProhibitedCannotBeOverriddenByApproval() {
        actual("34");
        approvals = List.of(approved("4"));
        expect(INELIGIBLE, OVERTIME_PROHIBITED);
    }

    @Test
    void approvalRequiredIsConditionalAndNeverFullyEligible() {
        overtime();
        expect(REQUIRES_APPROVAL, APPROVAL_REQUIRED);
    }

    @Test
    void validAuthorizedApprovalCoversTotalProjectedOvertime() {
        overtime();
        approvals = List.of(approved("4"));
        expect(ELIGIBLE, APPROVAL_VALID);
    }

    @Test
    void insufficientApprovalIsConditional() {
        overtime();
        approvals = List.of(approved("3.999"));
        expect(REQUIRES_APPROVAL, APPROVAL_INSUFFICIENT);
    }

    @Test
    void unauthorizedManagerCannotApprove() {
        overtime();
        approvals = List.of(approval("a1", employee.id(), target.id(), PERIOD, "4",
                OvertimeApproval.Decision.APPROVED, "unlisted-manager", now));
        expect(REQUIRES_APPROVAL, APPROVAL_UNAUTHORIZED);
    }

    @Test
    void approvalExpiresAtExactConfiguredBoundary() {
        overtime();
        approvals = List.of(approval("a1", employee.id(), target.id(), PERIOD, "4",
                OvertimeApproval.Decision.APPROVED, "manager-2", now.minus(policy.approvalValidity())));
        expect(REQUIRES_APPROVAL, APPROVAL_EXPIRED);
    }

    @Test
    void approvalPredatingVerifiedSnapshotNeedsRevalidation() {
        overtime();
        approvals = List.of(approval("a1", employee.id(), target.id(), PERIOD, "4",
                OvertimeApproval.Decision.APPROVED, "manager-2", now.minusSeconds(1)));
        expect(REQUIRES_APPROVAL, APPROVAL_STALE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "shift", "period"})
    void irrelevantApprovalDoesNotAuthorizeCoverage(String mismatch) {
        overtime();
        approvals = List.of(approval("a1", mismatch.equals("employee") ? "other" : employee.id(),
                mismatch.equals("shift") ? "other" : target.id(),
                mismatch.equals("period") ? new TimeWindow(PERIOD.end(), PERIOD.end().plusSeconds(86400)) : PERIOD,
                "4", OvertimeApproval.Decision.APPROVED, "manager-2", now));
        expect(REQUIRES_APPROVAL, APPROVAL_IRRELEVANT);
    }

    @Test
    void futureApprovalIsInvalidRatherThanAutomaticAuthorization() {
        overtime();
        approvals = List.of(approval("a1", employee.id(), target.id(), PERIOD, "4",
                OvertimeApproval.Decision.APPROVED, "manager-2", now.plusNanos(1)));
        expect(INELIGIBLE, INVALID_APPROVAL_DATA);
    }

    @Test
    void latestDenialOverridesOlderApproval() {
        overtime();
        cutoff = now.minusSeconds(1);
        approvals = List.of(approval("a0", employee.id(), target.id(), PERIOD, "10",
                        OvertimeApproval.Decision.APPROVED, "manager-2", cutoff),
                approval("a1", employee.id(), target.id(), PERIOD, "10",
                        OvertimeApproval.Decision.DENIED, "manager-2", now));
        expect(INELIGIBLE, APPROVAL_DENIED);
    }

    @Test
    void newApprovalCanSupersedeOlderDenial() {
        overtime();
        approvals = List.of(approval("a0", employee.id(), target.id(), PERIOD, "10",
                        OvertimeApproval.Decision.DENIED, "manager-2", now.minusSeconds(1)), approved("4"));
        expect(ELIGIBLE, APPROVAL_VALID);
    }

    @Test
    void contradictoryLatestDecisionsFailClosed() {
        overtime();
        approvals = List.of(approved("4"), approval("a2", employee.id(), target.id(), PERIOD, "4",
                OvertimeApproval.Decision.DENIED, "manager-2", now));
        expect(INELIGIBLE, INVALID_APPROVAL_DATA);
    }

    @Test
    void conflictingDuplicateApprovalIdsFailClosed() {
        overtime();
        approvals = List.of(approved("4"), approved("5"));
        expect(INELIGIBLE, INVALID_APPROVAL_DATA);
    }

    @Test
    void identicalDuplicateApprovalIsHarmless() {
        overtime();
        approvals = List.of(approved("4"), approved("4"));
        expect(ELIGIBLE, APPROVAL_VALID);
    }

    @Test
    void permittedOvertimeDoesNotRequireApproval() {
        actual("34");
        mode(OvertimePolicy.Mode.PERMITTED);
        expect(ELIGIBLE, OVERTIME_PERMITTED);
    }

    @ParameterizedTest
    @EnumSource(OvertimePolicy.Mode.class)
    void missingHoursAlwaysFailClosedIncludingPermittedOvertime(OvertimePolicy.Mode mode) {
        mode(mode);
        hours = null;
        expect(INELIGIBLE, MISSING_DATA);
    }

    @Test
    void scheduledAggregateMustMatchCompleteAssignmentSnapshot() {
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("8"), decimal("24"));
        expect(INELIGIBLE, CONTRADICTORY_HOURS);
    }

    @Test
    void repeatingDecimalScheduledHoursReconcileAtDeclaredReportingPrecision() {
        assignments = List.of(shift("one-second", "2026-10-23T15:00:00Z", "2026-10-23T15:00:01Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("0.000277777778"), decimal("24"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        // Projection still uses the assignment's exact second, not the rounded aggregate.
        assertEquals(0, decimal("30.000277777778").compareTo(evaluate().projectedHours().orElseThrow()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("0.000277777779"), decimal("24"));
        expect(INELIGIBLE, CONTRADICTORY_HOURS);
    }

    @Test
    void actualHoursCannotExceedElapsedVerifiedTime() {
        actual("200");
        expect(INELIGIBLE, CONTRADICTORY_HOURS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "period"})
    void mismatchedHourSummaryFails(String mismatch) {
        hours = new WorkHourSummary(mismatch.equals("employee") ? "another" : employee.id(),
                mismatch.equals("period") ? target.window() : PERIOD, decimal("0"), decimal("24"));
        expect(INELIGIBLE, PERIOD_MISMATCH);
    }

    @Test
    void pastScheduledHoursAreNotAddedToVerifiedActualHours() {
        assignments = List.of(shift("past", "2026-10-22T15:00:00Z", "2026-10-22T23:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("8"), decimal("30"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("36").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void inProgressAssignmentAddsOnlyFutureRemainder() {
        assignments = List.of(shift("ongoing", "2026-10-23T13:00:00Z", "2026-10-23T17:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("4"), decimal("28"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("36").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void unverifiedElapsedTimeIsReservedEvenWithoutScheduledWork() {
        cutoff = now.minusSeconds(900);
        expect(ELIGIBLE, UNVERIFIED_TIME_RESERVED);
        assertEquals(0, decimal("30.25").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void stalePartiallyCompletedShiftDoesNotDoubleCountGapOrElapsedSchedule() {
        cutoff = now.minusSeconds(900);
        assignments = List.of(shift("ongoing", "2026-10-23T13:00:00Z", "2026-10-23T17:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("4"), decimal("27.75"));
        expect(ELIGIBLE, UNVERIFIED_TIME_RESERVED);
        assertEquals(0, decimal("36").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void snapshotOlderThanMaxAgeIsRejectedAtNanosecondBoundary() {
        cutoff = now.minus(policy.maxSnapshotAge()).minusNanos(1);
        expect(INELIGIBLE, STALE_DATA);
    }

    @Test
    void companyCanRequireExactlyCurrentVerification() {
        policy = new EligibilityPolicy(PERIOD, policy.overtime(), Duration.ZERO, policy.approvalValidity(),
                policy.authorizedManagerIds(), Map.of());
        expect(ELIGIBLE, REQUIREMENTS_MET);
        cutoff = now.minusNanos(1);
        expect(INELIGIBLE, STALE_DATA);
    }

    @Test
    void futureSnapshotIsInvalid() {
        cutoff = now.plusNanos(1);
        expect(INELIGIBLE, INVALID_SNAPSHOT_TIME);
    }

    @Test
    void snapshotBeforePeriodIsInvalid() {
        cutoff = PERIOD.start().minusNanos(1);
        expect(INELIGIBLE, INVALID_SNAPSHOT_TIME);
    }

    @Test
    void targetCrossingAccountingBoundaryIsRejectedRatherThanUnderCounted() {
        target = shift("coverage-1", "2026-10-26T03:00:00Z", "2026-10-26T07:00:00Z", null);
        expect(INELIGIBLE, PERIOD_MISMATCH);
    }

    @Test
    void schedulesCrossingPeriodStartAreClippedForReconciliation() {
        assignments = List.of(shift("boundary", "2026-10-19T03:00:00Z", "2026-10-19T07:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("2"), decimal("24"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("30").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void futureScheduleCrossingPeriodEndOnlyCountsInsidePeriod() {
        assignments = List.of(shift("boundary", "2026-10-26T03:00:00Z", "2026-10-26T07:00:00Z", employee.id()));
        hours = new WorkHourSummary(employee.id(), PERIOD, decimal("2"), decimal("24"));
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("32").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void actualHoursAtPeriodStartMustBeZero() {
        now = PERIOD.start();
        cutoff = now;
        actual("0");
        expect(ELIGIBLE, REQUIREMENTS_MET);
        actual("0.001");
        expect(INELIGIBLE, CONTRADICTORY_HOURS);
    }

    @Test
    void evaluationAtPeriodEndIsInvalid() {
        now = PERIOD.end();
        cutoff = now;
        expect(INELIGIBLE, INVALID_SNAPSHOT_TIME);
    }

    @Test
    void targetAlreadyAssignedIsNotAnUncoveredPosition() {
        target = new Shift(target.id(), target.locationId(), target.window(), target.requiredQualifications(), Optional.of("original"));
        expect(INELIGIBLE, TARGET_NOT_OPEN);
    }

    @Test
    void alreadyStartedTargetNeedsExplicitRemainingPosition() {
        now = target.window().start().plusSeconds(1);
        cutoff = now;
        expect(INELIGIBLE, TARGET_ALREADY_STARTED);
    }

    @Test
    void targetStartingExactlyAtEvaluationIsAllowed() {
        now = target.window().start();
        cutoff = now;
        expect(ELIGIBLE, REQUIREMENTS_MET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-assignee", "open", "duplicate", "overlap", "target-id", "null"})
    void malformedSchedulesReturnStructuredRejection(String kind) {
        var first = shift("s1", "2026-10-22T15:00:00Z", "2026-10-22T19:00:00Z", employee.id());
        assignments = switch (kind) {
            case "wrong-assignee" -> List.of(shift("s1", "2026-10-22T15:00:00Z", "2026-10-22T19:00:00Z", "other"));
            case "open" -> List.of(shift("s1", "2026-10-22T15:00:00Z", "2026-10-22T19:00:00Z", null));
            case "duplicate" -> List.of(first, first);
            case "overlap" -> List.of(first, shift("s2", "2026-10-22T18:00:00Z", "2026-10-22T20:00:00Z", employee.id()));
            case "target-id" -> List.of(shift(target.id(), "2026-10-22T15:00:00Z", "2026-10-22T19:00:00Z", employee.id()));
            default -> Arrays.asList((Shift) null);
        };
        expect(INELIGIBLE, INVALID_SCHEDULE);
    }

    @Test
    void missingTopLevelRequestReturnsReason() {
        var result = engine.evaluate(null);
        assertEquals(INELIGIBLE, result.status());
        assertEquals(MISSING_DATA, result.reasons().getFirst().code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "target", "schedule", "hours", "policy", "approvals", "cutoff", "now"})
    void missingInputsReturnStructuredReasons(String field) {
        var input = new EligibilityRequest(field.equals("employee") ? null : employee, field.equals("target") ? null : target,
                field.equals("schedule") ? null : assignments, field.equals("hours") ? null : hours,
                field.equals("policy") ? null : policy, field.equals("approvals") ? null : approvals,
                field.equals("cutoff") ? null : cutoff, field.equals("now") ? null : now);
        var result = engine.evaluate(input);
        assertEquals(INELIGIBLE, result.status());
        assertEquals(MISSING_DATA, result.reasons().getFirst().code());
    }

    @Test
    void missingApprovalRecordIsNotSilentlyIgnored() {
        approvals = Arrays.asList((OvertimeApproval) null);
        expect(INELIGIBLE, INVALID_APPROVAL_DATA);
    }

    @Test
    void subSecondOvertimeIsDetectedWithoutRoundingDown() {
        actual("30");
        target = new Shift(target.id(), target.locationId(),
                new TimeWindow(target.window().start(), target.window().end().plusNanos(1)), target.requiredQualifications(), Optional.empty());
        expect(INELIGIBLE, OVERTIME_PROHIBITED);
    }

    @Test
    void exactSecondsComparisonDoesNotRejectAtVeryPreciseConfiguredThreshold() {
        target = new Shift(target.id(), target.locationId(), new TimeWindow(target.window().start(),
                target.window().start().plusSeconds(1)), target.requiredQualifications(), Optional.empty());
        actual("0");
        policy = new EligibilityPolicy(PERIOD, new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED,
                decimal("0.000277777777777778")), policy.maxSnapshotAge(), policy.approvalValidity(),
                policy.authorizedManagerIds(), Map.of());
        expect(ELIGIBLE, REQUIREMENTS_MET);
        // Preserve threshold precision so the reporting upper bound also stays within it.
        assertTrue(evaluate().projectedHours().orElseThrow().compareTo(policy.overtime().regularHoursLimit()) <= 0);
    }

    @Test
    void explicitZonedWorkweekAndOvernightShiftHandleDaylightSavingFallBack() {
        var zone = ZoneId.of("America/Chicago");
        var weekStart = LocalDate.of(2026, 10, 26).atStartOfDay(zone).toInstant();
        var weekEnd = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant();
        var dstPeriod = new TimeWindow(weekStart, weekEnd);
        assertEquals(169, dstPeriod.duration().toHours());
        now = LocalDate.of(2026, 10, 31).atTime(12, 0).atZone(zone).toInstant();
        cutoff = now;
        target = new Shift("coverage-dst", "store-3", new TimeWindow(
                LocalDate.of(2026, 10, 31).atTime(22, 0).atZone(zone).toInstant(),
                LocalDate.of(2026, 11, 1).atTime(6, 0).atZone(zone).toInstant()), Set.of(CASHIER), Optional.empty());
        assertEquals(9, target.window().duration().toHours());
        employee = new Employee(employee.id(), employee.name(), employee.status(), employee.qualifications(),
                new Availability(List.of(dstPeriod)));
        hours = new WorkHourSummary(employee.id(), dstPeriod, decimal("0"), decimal("24"));
        policy = new EligibilityPolicy(dstPeriod, new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED, decimal("33")),
                Duration.ZERO, Duration.ofHours(1), Set.of(), Map.of());
        expect(ELIGIBLE, REQUIREMENTS_MET);
        assertEquals(0, decimal("33").compareTo(evaluate().projectedHours().orElseThrow()));
    }

    @Test
    void multipleHardFailuresAreExplainedInStableOrder() {
        employee = new Employee(employee.id(), employee.name(), Employee.EmploymentStatus.INACTIVE,
                Set.of(), new Availability(List.of()));
        var result = evaluate();
        assertEquals(INELIGIBLE, result.status());
        assertEquals(List.of(INACTIVE_EMPLOYEE, MISSING_QUALIFICATION, NOT_AVAILABLE),
                result.reasons().stream().map(EligibilityResult.Reason::code).toList());
        assertEquals(result, evaluate());
    }

    @Test
    void requestAndPolicySnapshotsAreDefensive() {
        var mutableAssignments = new ArrayList<Shift>();
        var mutableApprovals = new ArrayList<OvertimeApproval>();
        var input = new EligibilityRequest(employee, target, mutableAssignments, hours, policy, mutableApprovals, cutoff, now);
        mutableAssignments.add(target);
        mutableApprovals.add(approved("4"));
        assertTrue(engine.evaluate(input).isEligible());
        assertThrows(UnsupportedOperationException.class, () -> input.assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.approvals().clear());
        var managers = new HashSet<>(Set.of("manager-2"));
        var replacements = new HashSet<>(Set.of(LEAD));
        var equivalences = new HashMap<Qualification, Set<Qualification>>();
        equivalences.put(CASHIER, replacements);
        var snapshot = new EligibilityPolicy(PERIOD, policy.overtime(), Duration.ZERO, Duration.ofHours(1), managers, equivalences);
        managers.clear();
        replacements.clear();
        equivalences.clear();
        assertEquals(Set.of("manager-2"), snapshot.authorizedManagerIds());
        assertEquals(Set.of(LEAD), snapshot.qualificationSubstitutes().get(CASHIER));
    }

    @Test
    void invalidPoliciesFailAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new EligibilityPolicy(PERIOD, policy.overtime(),
                Duration.ofSeconds(-1), Duration.ofHours(1), Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EligibilityPolicy(PERIOD, policy.overtime(),
                Duration.ZERO, Duration.ZERO, Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EligibilityPolicy(PERIOD, policy.overtime(),
                Duration.ZERO, Duration.ofHours(1), Set.of(" "), Map.of()));
        assertThrows(NullPointerException.class, () -> new EligibilityPolicy(null, policy.overtime(),
                Duration.ZERO, Duration.ofHours(1), Set.of(), Map.of()));
    }
}
