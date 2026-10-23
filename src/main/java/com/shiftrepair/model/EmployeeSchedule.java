package com.shiftrepair.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Validated snapshot of one employee's committed assignments, not availability. */
public record EmployeeSchedule(String employeeId, List<Shift> assignments) {
    public EmployeeSchedule {
        employeeId = ModelChecks.text(employeeId, "employeeId");
        assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        var ids = new HashSet<String>();
        for (int i = 0; i < assignments.size(); i++) {
            Shift shift = assignments.get(i);
            if (!shift.assignedEmployeeId().equals(java.util.Optional.of(employeeId))) {
                throw new IllegalArgumentException("Assignment must belong to employee " + employeeId);
            }
            if (!ids.add(shift.id())) {
                throw new IllegalArgumentException("Duplicate shift id: " + shift.id());
            }
            for (int j = 0; j < i; j++) {
                if (shift.window().overlaps(assignments.get(j).window())) {
                    throw new IllegalArgumentException("Overlapping assignments for employee " + employeeId);
                }
            }
        }
    }

    public boolean conflictsWith(TimeWindow window) {
        Objects.requireNonNull(window, "window");
        return assignments.stream().anyMatch(shift -> shift.window().overlaps(window));
    }
}
