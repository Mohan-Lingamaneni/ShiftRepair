package com.shiftrepair.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A single coverage position. Empty assignedEmployeeId denotes an open position. */
public record Shift(String id, String locationId, TimeWindow window,
                    Set<Qualification> requiredQualifications, Optional<String> assignedEmployeeId) {
    public Shift {
        id = ModelChecks.text(id, "id");
        locationId = ModelChecks.text(locationId, "locationId");
        Objects.requireNonNull(window, "window");
        requiredQualifications = Set.copyOf(Objects.requireNonNull(requiredQualifications, "requiredQualifications"));
        Objects.requireNonNull(assignedEmployeeId, "assignedEmployeeId");
        assignedEmployeeId.ifPresent(value -> ModelChecks.text(value, "assignedEmployeeId"));
    }
}
