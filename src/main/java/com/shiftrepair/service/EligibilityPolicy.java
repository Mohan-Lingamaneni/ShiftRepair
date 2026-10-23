package com.shiftrepair.service;

import com.shiftrepair.model.OvertimePolicy;
import com.shiftrepair.model.Qualification;
import com.shiftrepair.model.TimeWindow;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit company inputs for one accounting period; not an employment-law ruleset. */
public record EligibilityPolicy(TimeWindow accountingPeriod, OvertimePolicy overtime,
                                Duration maxSnapshotAge, Duration approvalValidity,
                                Set<String> authorizedManagerIds,
                                Map<Qualification, Set<Qualification>> qualificationSubstitutes) {
    public EligibilityPolicy {
        Objects.requireNonNull(accountingPeriod, "accountingPeriod");
        Objects.requireNonNull(overtime, "overtime");
        Objects.requireNonNull(maxSnapshotAge, "maxSnapshotAge");
        Objects.requireNonNull(approvalValidity, "approvalValidity");
        if (maxSnapshotAge.isNegative() || approvalValidity.isNegative() || approvalValidity.isZero()) {
            throw new IllegalArgumentException("Snapshot age must be nonnegative; approval validity must be positive");
        }
        authorizedManagerIds = Set.copyOf(Objects.requireNonNull(authorizedManagerIds, "authorizedManagerIds"));
        if (authorizedManagerIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Manager identifiers must not be blank");
        }
        Objects.requireNonNull(qualificationSubstitutes, "qualificationSubstitutes");
        qualificationSubstitutes = Map.copyOf(qualificationSubstitutes.entrySet().stream()
                .collect(Collectors.toMap(entry -> Objects.requireNonNull(entry.getKey()),
                        entry -> Set.copyOf(entry.getValue()))));
    }
}
