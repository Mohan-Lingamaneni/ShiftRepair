package com.shiftrepair.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** An immutable manager decision for a specific employee, shift and accounting period. */
public record OvertimeApproval(String id, String employeeId, String shiftId, TimeWindow period,
                               BigDecimal overtimeHours, Decision decision, String managerId,
                               Instant decidedAt, String reason) {
    public enum Decision { APPROVED, DENIED }

    public OvertimeApproval {
        id = ModelChecks.text(id, "id");
        employeeId = ModelChecks.text(employeeId, "employeeId");
        shiftId = ModelChecks.text(shiftId, "shiftId");
        Objects.requireNonNull(period, "period");
        overtimeHours = ModelChecks.hours(overtimeHours, "overtimeHours");
        if (overtimeHours.signum() == 0) {
            throw new IllegalArgumentException("overtimeHours must be positive");
        }
        Objects.requireNonNull(decision, "decision");
        managerId = ModelChecks.text(managerId, "managerId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        reason = ModelChecks.text(reason, "reason");
    }
}
