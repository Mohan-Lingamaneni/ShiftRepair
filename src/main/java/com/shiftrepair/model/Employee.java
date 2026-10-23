package com.shiftrepair.model;

import java.util.Objects;
import java.util.Set;

public record Employee(String id, String name, EmploymentStatus status,
                       Set<EmployeeQualification> qualifications, Availability availability) {
    public enum EmploymentStatus { ACTIVE, INACTIVE }

    public Employee {
        id = ModelChecks.text(id, "id");
        name = ModelChecks.text(name, "name");
        Objects.requireNonNull(status, "status");
        qualifications = Set.copyOf(Objects.requireNonNull(qualifications, "qualifications"));
        Objects.requireNonNull(availability, "availability");
    }
}
