package com.shiftrepair.service;

import java.util.Comparator;
import java.util.List;

/** Provisional distribution only. Scores do not select a winner or suppress eligible recipients. */
public final class SimultaneousOfferPolicy {
    public List<String> recipients(ScoringResult.Ranking evaluated) {
        return evaluated.eligibleCandidates().stream().map(ScoringResult::employeeId)
                .sorted(Comparator.naturalOrder()).toList();
    }
}
