package com.shiftrepair.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DomainFoundationTest {
    private static final Instant START = Instant.parse("2026-10-23T20:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plusSeconds(21600));
    private static final Qualification CASHIER = new Qualification(Qualification.Type.ROLE, "cashier");

    private Shift shift(String id, TimeWindow window, String employeeId) {
        return new Shift(id, "store-1", window, Set.of(CASHIER), Optional.ofNullable(employeeId));
    }

    @Test
    void rejectsNonPositiveWindowsAndNullEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(START, START));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(START, START.minusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new TimeWindow(null, START));
        assertThrows(NullPointerException.class, () -> new TimeWindow(START, null));
    }

    @Test
    void usesAbsoluteTimesAcrossMidnightAndHalfOpenBoundaries() {
        assertEquals(6, WINDOW.duration().toHours());
        TimeWindow adjacent = new TimeWindow(WINDOW.end(), WINDOW.end().plusSeconds(3600));
        assertFalse(WINDOW.overlaps(adjacent));
        TimeWindow overlapping = new TimeWindow(START.plusSeconds(1), WINDOW.end().plusSeconds(1));
        assertTrue(WINDOW.overlaps(overlapping));
        assertTrue(overlapping.overlaps(WINDOW));
        assertTrue(WINDOW.contains(WINDOW));
        assertFalse(WINDOW.contains(overlapping));
    }

    @Test
    void availabilityRequiresCompleteCoverageWithoutGaps() {
        Instant midpoint = START.plusSeconds(10800);
        var first = new TimeWindow(START, midpoint);
        var second = new TimeWindow(midpoint, WINDOW.end());
        assertTrue(new Availability(List.of(second, first)).covers(WINDOW));
        assertTrue(new Availability(List.of(WINDOW, first)).covers(WINDOW));
        assertFalse(new Availability(List.of(first)).covers(WINDOW));
        assertFalse(new Availability(List.of()).covers(WINDOW));
        assertFalse(new Availability(List.of(first,
                new TimeWindow(midpoint.plusSeconds(1), WINDOW.end()))).covers(WINDOW));
    }

    @Test
    void availabilityCopiesInputs() {
        var windows = new ArrayList<>(List.of(WINDOW));
        var availability = new Availability(windows);
        windows.clear();
        assertTrue(availability.covers(WINDOW));
        assertThrows(UnsupportedOperationException.class, () -> availability.windows().clear());
        assertThrows(NullPointerException.class, () -> new Availability(null));
    }

    @Test
    void qualificationExpiryMustCoverEntireShift() {
        assertTrue(new EmployeeQualification(CASHIER, Optional.empty()).covers(WINDOW));
        assertTrue(new EmployeeQualification(CASHIER, Optional.of(WINDOW.end())).covers(WINDOW));
        assertFalse(new EmployeeQualification(CASHIER, Optional.of(WINDOW.end().minusSeconds(1))).covers(WINDOW));
        assertFalse(new EmployeeQualification(CASHIER, Optional.of(START)).covers(WINDOW));
        assertNotEquals(CASHIER, new Qualification(Qualification.Type.SKILL, "cashier"));
        assertThrows(IllegalArgumentException.class, () -> new Qualification(Qualification.Type.SKILL, " "));
        assertThrows(NullPointerException.class, () -> new Qualification(null, "cashier"));
    }

    @Test
    void employeeRetainsIndependentEmploymentAndAvailabilityFacts() {
        var qualifications = new HashSet<>(Set.of(new EmployeeQualification(CASHIER, Optional.empty())));
        var employee = new Employee("e1", "Jordan Lee", Employee.EmploymentStatus.INACTIVE,
                qualifications, new Availability(List.of(WINDOW)));
        qualifications.clear();
        assertEquals(1, employee.qualifications().size());
        assertEquals(Employee.EmploymentStatus.INACTIVE, employee.status());
        assertTrue(employee.availability().covers(WINDOW)); // Availability alone is not eligibility.
        assertThrows(UnsupportedOperationException.class, () -> employee.qualifications().clear());
        assertThrows(IllegalArgumentException.class, () -> new Employee(" ", "Jordan", employee.status(),
                Set.of(), employee.availability()));
        assertThrows(IllegalArgumentException.class, () -> new Employee("e1", " ", employee.status(),
                Set.of(), employee.availability()));
        assertThrows(NullPointerException.class, () -> new Employee("e1", "Jordan", null,
                Set.of(), employee.availability()));
    }

    @Test
    void shiftValidatesIdentifiersAndCopiesRequirements() {
        var requirements = new HashSet<>(Set.of(CASHIER));
        var shift = new Shift("s1", "store", WINDOW, requirements, Optional.empty());
        requirements.clear();
        assertEquals(Set.of(CASHIER), shift.requiredQualifications());
        assertTrue(shift.assignedEmployeeId().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> shift.requiredQualifications().clear());
        assertThrows(IllegalArgumentException.class, () -> shift(" ", WINDOW, null));
        assertThrows(IllegalArgumentException.class, () -> shift("s1", WINDOW, " "));
        assertThrows(IllegalArgumentException.class, () -> new Shift("s1", " ", WINDOW, Set.of(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> shift("s1", null, null));
    }

    @Test
    void scheduleRejectsWrongEmployeeOpenAssignmentsDuplicatesAndOverlaps() {
        Shift first = shift("s1", WINDOW, "e1");
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule("e2", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule("e1", List.of(shift("s1", WINDOW, null))));
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule("e1", List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule("e1",
                List.of(first, shift("s2", new TimeWindow(START.plusSeconds(1), WINDOW.end()), "e1"))));
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule(" ", List.of()));
    }

    @Test
    void scheduleAllowsAdjacentAssignmentsAndDetectsConflictsWithoutMutation() {
        var adjacent = new TimeWindow(WINDOW.end(), WINDOW.end().plusSeconds(3600));
        var assignments = new ArrayList<>(List.of(shift("s1", WINDOW, "e1"), shift("s2", adjacent, "e1")));
        var schedule = new EmployeeSchedule("e1", assignments);
        assignments.clear();
        assertEquals(2, schedule.assignments().size());
        assertTrue(schedule.conflictsWith(WINDOW));
        assertFalse(schedule.conflictsWith(new TimeWindow(adjacent.end(), adjacent.end().plusSeconds(1))));
        assertThrows(UnsupportedOperationException.class, () -> schedule.assignments().clear());
        assertThrows(IllegalArgumentException.class, () -> new EmployeeSchedule("e1", List.of(
                shift("s1", WINDOW, "e1"), shift("s1", adjacent, "e1"))));
    }

    @Test
    void hoursKeepScheduledAndActualSeparateAndRejectNegativeValues() {
        var summary = new WorkHourSummary("e1", WINDOW, new BigDecimal("32.5"), new BigDecimal("34.75"));
        assertEquals(new BigDecimal("32.5"), summary.scheduledHours());
        assertEquals(new BigDecimal("34.75"), summary.actualWorkedHours());
        assertDoesNotThrow(() -> new WorkHourSummary("e1", WINDOW, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkHourSummary("e1", WINDOW,
                new BigDecimal("-1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkHourSummary("e1", WINDOW,
                BigDecimal.ZERO, new BigDecimal("-1")));
        assertThrows(NullPointerException.class, () -> new WorkHourSummary("e1", WINDOW, null, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkHourSummary(" ", WINDOW, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @ParameterizedTest
    @EnumSource(OvertimePolicy.Mode.class)
    void eachOvertimeModeRetainsThresholdWithoutImplicitApproval(OvertimePolicy.Mode mode) {
        var policy = new OvertimePolicy(mode, new BigDecimal("40"));
        assertEquals(mode, policy.mode());
        assertFalse(policy.exceedsLimit(new BigDecimal("40.00")));
        assertTrue(policy.exceedsLimit(new BigDecimal("40.01")));
        assertThrows(IllegalArgumentException.class, () -> policy.exceedsLimit(new BigDecimal("-1")));
    }

    @Test
    void overtimePolicyRequiresPositiveThresholdAndMode() {
        assertThrows(IllegalArgumentException.class, () -> new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new OvertimePolicy(OvertimePolicy.Mode.PERMITTED, new BigDecimal("-1")));
        assertThrows(NullPointerException.class, () -> new OvertimePolicy(null, BigDecimal.TEN));
    }

    private OvertimeApproval approval(String manager, BigDecimal hours, OvertimeApproval.Decision decision) {
        return new OvertimeApproval("a1", "e1", "s1", WINDOW, hours, decision, manager, START, "Coverage needed");
    }

    @ParameterizedTest
    @EnumSource(OvertimeApproval.Decision.class)
    void approvalRecordsExplicitManagerDecision(OvertimeApproval.Decision decision) {
        var approval = approval("manager-1", new BigDecimal("2.5"), decision);
        assertEquals(decision, approval.decision());
        assertEquals("e1", approval.employeeId());
        assertEquals("s1", approval.shiftId());
        assertEquals("manager-1", approval.managerId());
        assertEquals(START, approval.decidedAt());
    }

    @Test
    void approvalRequiresPositiveHoursAndDecisionAttribution() {
        assertThrows(IllegalArgumentException.class, () -> approval(" ", BigDecimal.ONE, OvertimeApproval.Decision.APPROVED));
        assertThrows(IllegalArgumentException.class, () -> approval("m1", BigDecimal.ZERO, OvertimeApproval.Decision.APPROVED));
        assertThrows(IllegalArgumentException.class, () -> approval("m1", new BigDecimal("-1"), OvertimeApproval.Decision.APPROVED));
        assertThrows(NullPointerException.class, () -> approval("m1", BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> new OvertimeApproval(" ", "e1", "s1", WINDOW,
                BigDecimal.ONE, OvertimeApproval.Decision.APPROVED, "m1", START, "Reason"));
    }

    @Test
    void recoveryTransitionsAreTerminalAndPreserveOriginalSnapshot() {
        var open = RecoveryRequest.open("r1", shift("s1", WINDOW, "e1"), "m1", START);
        var covered = open.cover("e2");
        assertEquals(RecoveryRequest.Status.OPEN, open.status());
        assertEquals(Optional.of("e2"), covered.replacementEmployeeId());
        assertEquals(Optional.of("e1"), covered.shift().assignedEmployeeId());
        assertThrows(IllegalStateException.class, covered::cancel);
        assertThrows(IllegalStateException.class, () -> covered.cover("e3"));
        var cancelled = open.cancel();
        assertEquals(RecoveryRequest.Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.replacementEmployeeId().isEmpty());
        assertThrows(IllegalStateException.class, cancelled::cancel);
        assertThrows(IllegalStateException.class, () -> cancelled.cover("e2"));
        assertThrows(IllegalArgumentException.class, () -> open.cover("e1"));
        assertThrows(IllegalArgumentException.class, () -> open.cover(" "));
    }

    @Test
    void recoveryRejectsInconsistentStateAndMissingIds() {
        Shift shift = shift("s1", WINDOW, null);
        assertDoesNotThrow(() -> RecoveryRequest.open("r1", shift, "m1", START).cover("e1"));
        assertThrows(IllegalArgumentException.class, () -> RecoveryRequest.open(" ", shift, "m1", START));
        assertThrows(IllegalArgumentException.class, () -> RecoveryRequest.open("r1", shift, " ", START));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryRequest("r1", shift, "m1", START,
                RecoveryRequest.Status.COVERED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryRequest("r1", shift, "m1", START,
                RecoveryRequest.Status.OPEN, Optional.of("e1")));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryRequest("r1", shift, "m1", START,
                RecoveryRequest.Status.CANCELLED, Optional.of("e1")));
    }
}
