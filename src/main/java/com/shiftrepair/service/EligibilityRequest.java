package com.shiftrepair.service;

import com.shiftrepair.model.Employee;
import com.shiftrepair.model.OvertimeApproval;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.WorkHourSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Caller attests that employee facts, requirements, policy and the complete schedule
 * were verified at verifiedThrough, and actual hours cover period start through that
 * instant. Null/malformed boundary inputs are retained for structured engine rejection.
 */
public record EligibilityRequest(Employee employee, Shift targetShift, List<Shift> assignments,
                                 WorkHourSummary workHours, EligibilityPolicy policy,
                                 List<OvertimeApproval> approvals, Instant verifiedThrough,
                                 Instant evaluatedAt) {
    public EligibilityRequest {
        assignments = snapshot(assignments);
        approvals = snapshot(approvals);
    }

    private static <T> List<T> snapshot(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
