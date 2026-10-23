package com.shiftrepair.persistence;

import com.shiftrepair.model.RecoveryRequest;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.OvertimeApproval;
import com.shiftrepair.model.Employee;
import com.shiftrepair.model.TimeWindow;
import com.shiftrepair.model.WorkHourSummary;
import com.shiftrepair.service.*;

import java.time.Instant;
import java.util.List;

/** Immutable transfer state between orchestration and storage, not a second domain model. */
public record RecoveryState(List<SavedRecovery> recoveries, List<Shift> assignments, List<OvertimeApproval> approvals, List<SourceFacts> sources,
                             Instant lastObservedTime, long nextOfferId) {
    /** Latest authoritative facts survive candidate removal; revision is receipt order, not verification time. */
    public record SourceFacts(Employee employee, TimeWindow period, List<Shift> assignments, WorkHourSummary workHours,
                               Instant verifiedThrough, long revision) {
        public SourceFacts {
            java.util.Objects.requireNonNull(employee,"employee");
            java.util.Objects.requireNonNull(period,"period");
            assignments = assignments == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(assignments));
            if(revision<=0) throw new IllegalArgumentException("Invalid source revision");
        }
    }
    public record SavedRecovery(RecoveryRequest recovery, EligibilityPolicy policy, RecoveryConfig config,
                                List<ScoringRequest> inputs, List<ScoringResult> candidates,
                                List<RecoveryView.Offer> offers, List<RecoveryView.Event> events, long sourceRevision) {
        public SavedRecovery {
            inputs = List.copyOf(inputs);
            candidates = List.copyOf(candidates);
            offers = List.copyOf(offers);
            events = List.copyOf(events);
            if (sourceRevision <= 0) throw new IllegalArgumentException("Invalid source revision");
        }
    }

    public RecoveryState {
        recoveries = List.copyOf(recoveries);
        assignments = List.copyOf(assignments);
        approvals = List.copyOf(approvals);
        sources = List.copyOf(sources);
        java.util.Objects.requireNonNull(lastObservedTime, "lastObservedTime");
        if (nextOfferId < 0) throw new IllegalArgumentException("Negative offer sequence");
    }

    public static RecoveryState empty() {
        return new RecoveryState(List.of(), List.of(), List.of(), List.of(), Instant.MIN, 0);
    }
}
