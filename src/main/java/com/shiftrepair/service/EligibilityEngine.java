package com.shiftrepair.service;

import com.shiftrepair.model.Employee;
import com.shiftrepair.model.EmployeeQualification;
import com.shiftrepair.model.EmployeeSchedule;
import com.shiftrepair.model.OvertimeApproval;
import com.shiftrepair.model.OvertimePolicy;
import com.shiftrepair.model.Qualification;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.TimeWindow;
import com.shiftrepair.service.EligibilityResult.Code;
import com.shiftrepair.service.EligibilityResult.Reason;
import com.shiftrepair.service.EligibilityResult.Status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Stateless, deterministic hard-requirement evaluation. Never assigns or dispatches offers. */
public final class EligibilityEngine {
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    public EligibilityResult evaluate(EligibilityRequest request) {
        if (request == null || request.employee() == null || request.targetShift() == null
                || request.assignments() == null || request.workHours() == null || request.policy() == null
                || request.approvals() == null || request.verifiedThrough() == null || request.evaluatedAt() == null) {
            return reject(Code.MISSING_DATA, "Employee, shift, complete schedule, hours, policy, approvals and timestamps are required.");
        }
        var employee = request.employee();
        var target = request.targetShift();
        var policy = request.policy();
        var period = policy.accountingPeriod();
        var now = request.evaluatedAt();
        var cutoff = request.verifiedThrough();
        EmployeeSchedule schedule;
        try {
            schedule = new EmployeeSchedule(employee.id(), request.assignments());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return reject(Code.INVALID_SCHEDULE, "Invalid assignment snapshot: " + exception.getMessage());
        }
        if (schedule.assignments().stream().anyMatch(shift -> shift.id().equals(target.id()))) {
            return reject(Code.INVALID_SCHEDULE, "Target shift ID already appears in the employee's assignments.");
        }
        if (request.approvals().stream().anyMatch(java.util.Objects::isNull)) {
            return reject(Code.INVALID_APPROVAL_DATA, "Approval collection contains a missing record.");
        }
        var approvalIds = new HashMap<String, OvertimeApproval>();
        for (var approval : request.approvals()) {
            var previous = approvalIds.putIfAbsent(approval.id(), approval);
            if (previous != null && !previous.equals(approval)) {
                return reject(Code.INVALID_APPROVAL_DATA, "Conflicting records share approval ID " + approval.id());
            }
        }
        if (!request.workHours().employeeId().equals(employee.id())
                || !request.workHours().period().equals(period) || !period.contains(target.window())) {
            return reject(Code.PERIOD_MISMATCH, "Hours must belong to this employee and period; the whole target shift must be inside that period.");
        }
        if (cutoff.isAfter(now) || cutoff.isBefore(period.start()) || !now.isBefore(period.end())) {
            return reject(Code.INVALID_SNAPSHOT_TIME, "Require period start <= verifiedThrough <= evaluatedAt < period end.");
        }
        if (Duration.between(cutoff, now).compareTo(policy.maxSnapshotAge()) > 0) {
            return reject(Code.STALE_DATA, "Verified snapshot is older than the company's maximum age.");
        }
        BigDecimal scheduledSeconds = schedule.assignments().stream()
                .map(shift -> intersectionSeconds(shift.window(), period.start(), period.end()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualSeconds = request.workHours().actualWorkedHours().multiply(SECONDS_PER_HOUR);
        // Decimal hours cannot exactly encode durations such as one second. Reconcile
        // against the upward-rounded aggregate; projections still use exact seconds.
        BigDecimal reportedScheduledHours = scheduledSeconds.divide(SECONDS_PER_HOUR,
                Math.max(12, request.workHours().scheduledHours().scale()), RoundingMode.CEILING);
        if (reportedScheduledHours.compareTo(request.workHours().scheduledHours()) != 0
                || actualSeconds.compareTo(seconds(period.start(), cutoff)) > 0) {
            return reject(Code.CONTRADICTORY_HOURS, "Scheduled total must reconcile with the complete period schedule; actual hours cannot exceed elapsed verified time.");
        }
        var qualifications = new HashSet<Qualification>();
        for (EmployeeQualification qualification : employee.qualifications()) {
            if (!qualifications.add(qualification.qualification())) {
                return reject(Code.CONTRADICTORY_QUALIFICATIONS, "Multiple expiry records exist for qualification " + qualification.qualification().code());
            }
        }

        var reasons = new ArrayList<Reason>();
        if (target.assignedEmployeeId().isPresent()) {
            reasons.add(reason(Code.TARGET_NOT_OPEN, "Target must be an explicitly uncovered coverage position."));
        }
        if (target.window().start().isBefore(now)) {
            reasons.add(reason(Code.TARGET_ALREADY_STARTED, "Already-started shifts require an explicit remaining coverage position."));
        }
        if (employee.status() != Employee.EmploymentStatus.ACTIVE) {
            reasons.add(reason(Code.INACTIVE_EMPLOYEE, "Employee is not actively employed."));
        }
        // Stable order makes results deterministic even when qualifications are supplied as sets.
        for (Qualification required : target.requiredQualifications().stream()
                .sorted(Comparator.comparing((Qualification q) -> q.type().name()).thenComparing(Qualification::code)).toList()) {
            Set<Qualification> substitutes = policy.qualificationSubstitutes().getOrDefault(required, Set.of());
            var matching = employee.qualifications().stream()
                    .filter(q -> q.qualification().equals(required) || substitutes.contains(q.qualification())).toList();
            if (matching.isEmpty()) {
                reasons.add(reason(Code.MISSING_QUALIFICATION, "Missing required " + required.type() + ": " + required.code()));
            } else if (matching.stream().noneMatch(q -> q.covers(target.window()))) {
                reasons.add(reason(Code.QUALIFICATION_EXPIRED, "Qualification does not remain valid for the whole shift: " + required.code()));
            }
        }
        if (!employee.availability().covers(target.window())) {
            reasons.add(reason(Code.NOT_AVAILABLE, "Declared availability does not cover the entire shift."));
        }
        if (schedule.conflictsWith(target.window())) {
            reasons.add(reason(Code.SHIFT_CONFLICT, "Target overlaps an existing assignment."));
        }
        if (!reasons.isEmpty()) {
            return new EligibilityResult(Status.INELIGIBLE, reasons, Optional.empty());
        }

        // Never add elapsed scheduled hours to actuals. Reserve ALL unverified elapsed
        // time, then only the future part of assignments (including in-progress shifts).
        BigDecimal unverifiedSeconds = seconds(cutoff, now);
        BigDecimal futureSeconds = schedule.assignments().stream()
                .map(shift -> intersectionSeconds(shift.window(), now, period.end()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal projectedSeconds = actualSeconds.add(unverifiedSeconds).add(futureSeconds)
                .add(seconds(target.window().start(), target.window().end()));
        // Display an upper bound; policy and approval comparisons below use exact seconds.
        int reportingScale = Math.max(12, policy.overtime().regularHoursLimit().scale());
        BigDecimal projectedHours = projectedSeconds.divide(SECONDS_PER_HOUR, reportingScale, RoundingMode.CEILING);
        if (unverifiedSeconds.signum() > 0) {
            reasons.add(reason(Code.UNVERIFIED_TIME_RESERVED, "All elapsed time since verification is reserved as potentially worked time."));
        }
        BigDecimal overtimeSeconds = projectedSeconds.subtract(policy.overtime().regularHoursLimit().multiply(SECONDS_PER_HOUR));
        if (overtimeSeconds.signum() <= 0) {
            reasons.add(reason(Code.REQUIREMENTS_MET, "Hard requirements met; projected hours are within the configured threshold."));
            return result(Status.ELIGIBLE, reasons, projectedHours);
        }
        if (policy.overtime().mode() == OvertimePolicy.Mode.PROHIBITED) {
            reasons.add(reason(Code.OVERTIME_PROHIBITED, "Projected hours exceed the company limit and overtime is prohibited."));
            return result(Status.INELIGIBLE, reasons, projectedHours);
        }
        if (policy.overtime().mode() == OvertimePolicy.Mode.PERMITTED) {
            reasons.add(reason(Code.OVERTIME_PERMITTED, "Hard requirements met; company policy permits projected overtime."));
            return result(Status.ELIGIBLE, reasons, projectedHours);
        }
        return evaluateApproval(request, overtimeSeconds, projectedHours, reasons);
    }

    private EligibilityResult evaluateApproval(EligibilityRequest request, BigDecimal overtimeSeconds,
                                              BigDecimal projectedHours, List<Reason> reasons) {
        var scoped = request.approvals().stream().distinct()
                .filter(a -> a.employeeId().equals(request.employee().id())
                        && a.shiftId().equals(request.targetShift().id())
                        && a.period().equals(request.policy().accountingPeriod())).toList();
        if (scoped.isEmpty()) {
            reasons.add(reason(request.approvals().isEmpty() ? Code.APPROVAL_REQUIRED : Code.APPROVAL_IRRELEVANT,
                    "A manager decision covering this employee, shift and accounting period is required."));
            return result(Status.REQUIRES_APPROVAL, reasons, projectedHours);
        }
        if (scoped.stream().anyMatch(a -> a.decidedAt().isAfter(request.evaluatedAt()))) {
            reasons.add(reason(Code.INVALID_APPROVAL_DATA, "A scoped approval decision is dated in the future."));
            return result(Status.INELIGIBLE, reasons, projectedHours);
        }
        Instant latest = scoped.stream().map(OvertimeApproval::decidedAt).max(Comparator.naturalOrder()).orElseThrow();
        var latestRecords = scoped.stream().filter(a -> a.decidedAt().equals(latest)).toList();
        if (latestRecords.size() != 1) {
            reasons.add(reason(Code.INVALID_APPROVAL_DATA, "Multiple latest decisions have the same timestamp; resolve the approval history."));
            return result(Status.INELIGIBLE, reasons, projectedHours);
        }
        var approval = latestRecords.getFirst();
        Code failure = null;
        String message = null;
        if (!request.policy().authorizedManagerIds().contains(approval.managerId())) {
            failure = Code.APPROVAL_UNAUTHORIZED;
            message = "Latest decision was not recorded by a currently authorized manager.";
        } else if (Duration.between(approval.decidedAt(), request.evaluatedAt())
                .compareTo(request.policy().approvalValidity()) >= 0) {
            failure = Code.APPROVAL_EXPIRED;
            message = "Latest decision has reached its company-configured expiry.";
        } else if (approval.decidedAt().isBefore(request.verifiedThrough())) {
            failure = Code.APPROVAL_STALE;
            message = "Decision predates the verified snapshot; approval must be revalidated against current facts.";
        } else if (approval.decision() == OvertimeApproval.Decision.DENIED) {
            reasons.add(reason(Code.APPROVAL_DENIED, "The latest valid manager decision denies overtime for this coverage."));
            return result(Status.INELIGIBLE, reasons, projectedHours);
        } else if (approval.overtimeHours().multiply(SECONDS_PER_HOUR).compareTo(overtimeSeconds) < 0) {
            failure = Code.APPROVAL_INSUFFICIENT;
            message = "Approved hours do not cover the total projected overtime in this period.";
        }
        if (failure != null) {
            reasons.add(reason(failure, message));
            return result(Status.REQUIRES_APPROVAL, reasons, projectedHours);
        }
        reasons.add(reason(Code.APPROVAL_VALID, "Hard requirements met and the latest authorized decision covers projected overtime."));
        return result(Status.ELIGIBLE, reasons, projectedHours);
    }

    private static BigDecimal intersectionSeconds(TimeWindow window, Instant start, Instant end) {
        Instant clippedStart = window.start().isAfter(start) ? window.start() : start;
        Instant clippedEnd = window.end().isBefore(end) ? window.end() : end;
        return clippedEnd.isAfter(clippedStart) ? seconds(clippedStart, clippedEnd) : BigDecimal.ZERO;
    }

    private static BigDecimal seconds(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private static Reason reason(Code code, String message) {
        return new Reason(code, message);
    }

    private static EligibilityResult result(Status status, List<Reason> reasons, BigDecimal hours) {
        return new EligibilityResult(status, reasons, Optional.of(hours));
    }

    private static EligibilityResult reject(Code code, String message) {
        return new EligibilityResult(Status.INELIGIBLE, List.of(reason(code, message)), Optional.empty());
    }
}
