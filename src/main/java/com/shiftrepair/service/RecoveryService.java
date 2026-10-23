package com.shiftrepair.service;

import com.shiftrepair.model.EmployeeSchedule;
import com.shiftrepair.model.RecoveryRequest;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.ShiftOffer;
import com.shiftrepair.model.TimeWindow;
import com.shiftrepair.model.WorkHourSummary;
import com.shiftrepair.model.OvertimeApproval;
import com.shiftrepair.service.RecoveryView.EventType;
import com.shiftrepair.persistence.RecoveryState;
import com.shiftrepair.persistence.RecoveryStore;
import com.shiftrepair.persistence.SqliteRecoveryStore;
import com.shiftrepair.persistence.PersistenceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.nio.file.Path;

/**
 * One shared instance is the process-local source of truth. All commands and queries
 * use its monitor, including eligibility recheck, assignment and competing-offer closure.
 * Callers must authenticate actor IDs before invoking this in-process API.
 */
public final class RecoveryService {
    public enum Code {
        CREATED, REEVALUATED, ACCEPTED, DECLINED, CANCELLED, FOUND,
        NOT_AUTHORIZED, NOT_FOUND, NOT_OFFERED, INVALID_REQUEST, DUPLICATE_RECOVERY,
        ALREADY_FILLED, RECOVERY_CANCELLED, OFFER_EXPIRED, OFFER_NOT_PENDING, ELIGIBILITY_CHANGED
    }

    public record Result(Code code, String message, Optional<RecoveryView> recovery,
                         Optional<EligibilityResult> eligibility) { }

    private static final class Entry {
        RecoveryRequest recovery;
        final EligibilityPolicy policy;
        final RecoveryConfig config;
        Map<String, ScoringRequest> inputs = new LinkedHashMap<>();
        Map<String, ScoringResult> candidates = new LinkedHashMap<>();
        final List<ManagedOffer> offers = new ArrayList<>();
        final List<RecoveryView.Event> events = new ArrayList<>();
        long sourceRevision;

        Entry(RecoveryRequest recovery, EligibilityPolicy policy, RecoveryConfig config) {
            this.recovery = recovery;
            this.policy = policy;
            this.config = config;
        }
    }

    private record ManagedOffer(String recoveryId, String employeeId, Shift shift, Instant createdAt,
                                ScoringResult scoring, ShiftOffer offer) {
        RecoveryView.Offer view() {
            return new RecoveryView.Offer(offer.getId(), recoveryId, employeeId, shift, offer.getStatus(),
                    createdAt, offer.getExpiresAt(), scoring);
        }
    }

    private final Clock clock;
    private final Set<String> managerIds;
    private final RecoveryStore store;
    private final EligibilityEngine eligibility = new EligibilityEngine();
    private final ScoringEngine scoring = new ScoringEngine();
    private final SimultaneousOfferPolicy distribution = new SimultaneousOfferPolicy();
    private final Map<String, Entry> recoveries = new LinkedHashMap<>();
    private final Map<String, String> activeByShift = new LinkedHashMap<>();
    private final Map<String, ManagedOffer> offers = new LinkedHashMap<>();
    private final Map<String, Shift> assignments = new LinkedHashMap<>();
    private final Map<String, OvertimeApproval> approvalLedger = new LinkedHashMap<>();
    private record SourceKey(String employeeId, TimeWindow period) { }
    private final Map<SourceKey, RecoveryState.SourceFacts> authoritativeSources = new LinkedHashMap<>();
    private Instant lastObservedTime = Instant.MIN;
    private long nextOfferId;

    public RecoveryService(Clock clock, Set<String> managerIds) {
        this(clock, managerIds, null);
    }

    public RecoveryService(Clock clock, Set<String> managerIds, RecoveryStore store) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = store;
        this.managerIds = Set.copyOf(managerIds);
        if (this.managerIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Manager IDs must not be blank");
        }
    }

    /** Durable mode: no long-lived in-memory cache competes with database state. */
    public static RecoveryService open(Path database, Clock clock, Set<String> managerIds) {
        return new RecoveryService(clock, managerIds, new SqliteRecoveryStore(database));
    }

    public synchronized Result create(String id, String managerId, Shift uncovered,
                                      EligibilityPolicy policy, Collection<ScoringRequest> candidates,
                                      RecoveryConfig config) {
        if (store != null) return transact(service -> service.create(id, managerId, uncovered, policy, candidates, config));
        if (managerId == null || !managerIds.contains(managerId)) {
            return error(Code.NOT_AUTHORIZED, "Manager authorization required.");
        }
        Instant now = now();
        try {
            if (id == null || id.isBlank() || uncovered == null || policy == null || config == null
                    || uncovered.assignedEmployeeId().isPresent() || !uncovered.window().start().isAfter(now)
                    || !policy.accountingPeriod().contains(uncovered.window())
                    || now.isBefore(policy.accountingPeriod().start()) || !now.isBefore(policy.accountingPeriod().end())) {
                return error(Code.INVALID_REQUEST, "Require an identified, future, unassigned shift inside the current explicit accounting period.");
            }
            if (recoveries.containsKey(id) || activeByShift.containsKey(uncovered.id())) {
                return error(Code.DUPLICATE_RECOVERY, "Recovery ID is already used or this shift has an active recovery.");
            }
            if (assignments.containsKey(uncovered.id())) {
                return error(Code.ALREADY_FILLED, "This shift is already assigned by the recovery service.");
            }
            // Validate all inputs and deadline before publishing any state.
            uncovered.window().start().atOffset(ZoneOffset.UTC);
            uncovered.window().end().atOffset(ZoneOffset.UTC);
            Instant deadline = deadline(now, uncovered, config);
            var inputs = validateInputs(candidates, uncovered, policy, now);
            var ranking = evaluate(inputs, config, now);
            var entry = new Entry(RecoveryRequest.open(id, uncovered, managerId, now), policy, config);
            registerApprovals(inputs.values());
            entry.sourceRevision = nextSourceRevision();
            registerSources(inputs.values(),entry.sourceRevision);
            entry.inputs = inputs;
            setCandidates(entry, ranking);
            event(entry, EventType.CREATED, now, null, null, "Manager " + managerId + " created an uncovered-shift recovery.");
            event(entry, EventType.CANDIDATES_EVALUATED, now, null, null, "Eligibility evaluated before informational scoring.");
            recoveries.put(id, entry);
            activeByShift.put(uncovered.id(), id);
            broadcast(entry, ranking, now, deadline);
            escalateIfEmpty(entry, now);
            return result(Code.CREATED, "Recovery created.", entry, null);
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException | ArithmeticException exception) {
            return error(Code.INVALID_REQUEST, "Invalid recovery input: " + exception.getMessage());
        }
    }

    /** Trusted manager refresh, including already-recorded approvals. Does not fabricate or auto-approve overtime. */
    public synchronized Result reevaluate(String managerId, String recoveryId, Collection<ScoringRequest> candidates) {
        if (store != null) return transact(service -> service.reevaluate(managerId, recoveryId, candidates));
        if (managerId == null || !managerIds.contains(managerId)) {
            return error(Code.NOT_AUTHORIZED, "Manager authorization required.");
        }
        var entry = recoveries.get(recoveryId);
        if (entry == null) {
            return error(Code.NOT_FOUND, "Recovery not found.");
        }
        Instant now = now();
        expire(entry, now);
        if (!active(entry)) {
            return terminal(entry);
        }
        try {
            var inputs = validateInputs(candidates, entry.recovery.shift(), entry.policy, now);
            var ranking = evaluate(inputs, entry.config, now);
            Instant deadline = deadline(now, entry.recovery.shift(), entry.config);
            registerApprovals(inputs.values());
            entry.sourceRevision = nextSourceRevision();
            registerSources(inputs.values(),entry.sourceRevision);
            entry.inputs = inputs;
            setCandidates(entry, ranking);
            for (var offer : entry.offers) {
                var candidate = entry.candidates.get(offer.employeeId());
                if (candidate == null || !candidate.eligibility().isEligible()) {
                    close(entry, offer, now, "Candidate is absent or no longer fully eligible.");
                }
            }
            event(entry, EventType.CANDIDATES_EVALUATED, now, null, null, "Manager " + managerId + " refreshed candidates and recorded approval information.");
            broadcast(entry, ranking, now, deadline);
            if (hasPending(entry) && entry.recovery.status() == RecoveryRequest.Status.ESCALATED) {
                entry.recovery = entry.recovery.reopen();
                event(entry, EventType.REOPENED, now, null, null, "Newly eligible candidates have actionable offers.");
            }
            escalateIfEmpty(entry, now);
            return result(Code.REEVALUATED, "Candidates refreshed; conditional candidates require valid approval before receiving offers.", entry, null);
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException | ArithmeticException exception) {
            return result(Code.INVALID_REQUEST, "Invalid refresh: " + exception.getMessage(), entry, null);
        }
    }

    public synchronized Result accept(String employeeId, String offerId) {
        if (store != null) return transact(service -> service.accept(employeeId, offerId));
        var offered = offers.get(offerId);
        if (offered == null) {
            return error(Code.NOT_FOUND, "Offer not found.");
        }
        if (!offered.employeeId().equals(employeeId)) {
            return error(Code.NOT_OFFERED, "This employee was not offered this position.");
        }
        var entry = recoveries.get(offered.recoveryId());
        Instant now = now();
        expire(entry, now);
        if (!active(entry)) {
            return terminal(entry);
        }
        if (offered.offer().getStatus() != ShiftOffer.Status.PENDING) {
            return result(offered.offer().getStatus() == ShiftOffer.Status.EXPIRED ? Code.OFFER_EXPIRED : Code.OFFER_NOT_PENDING,
                    "Offer is " + offered.offer().getStatus() + ".", entry, null);
        }
        var input = withLocalAssignments(entry.inputs.get(employeeId), now, false);
        var current = scoring.score(input, entry.config.scoring());
        entry.candidates.put(employeeId, current);
        if (!current.eligibility().isEligible()) {
            close(entry, offered, now, "Current eligibility does not authorize assignment.");
            event(entry, EventType.ACCEPTANCE_REJECTED, now, employeeId, offerId, current.eligibility().reasons().toString());
            escalateIfEmpty(entry, now);
            return result(Code.ELIGIBILITY_CHANGED, "Acceptance rejected after current eligibility recheck.", entry, current.eligibility());
        }
        Shift assigned = new Shift(entry.recovery.shift().id(), entry.recovery.shift().locationId(),
                entry.recovery.shift().window(), entry.recovery.shift().requiredQualifications(), Optional.of(employeeId));
        var combined = new ArrayList<>(input.eligibility().assignments());
        combined.add(assigned);
        new EmployeeSchedule(employeeId, combined); // Validate before any winning-state mutation.
        var covered = entry.recovery.cover(employeeId);
        if (!offered.offer().respond(ShiftOffer.Status.ACCEPTED, now)) {
            throw new IllegalStateException("Pending offer changed while holding the service monitor");
        }
        assignments.put(assigned.id(), assigned);
        entry.recovery = covered; // COVERED is the existing model's FILLED equivalent.
        for (var competing : entry.offers) {
            close(entry, competing, now, "Position claimed; competing offer withdrawn.");
        }
        activeByShift.remove(assigned.id());
        event(entry, EventType.FILLED, now, employeeId, offerId, "First valid acceptance assigned the position atomically.");
        return result(Code.ACCEPTED, "Shift assigned; all competing offers closed.", entry, current.eligibility());
    }

    public synchronized Result decline(String employeeId, String offerId) {
        if (store != null) return transact(service -> service.decline(employeeId, offerId));
        var offered = offers.get(offerId);
        if (offered == null) {
            return error(Code.NOT_FOUND, "Offer not found.");
        }
        if (!offered.employeeId().equals(employeeId)) {
            return error(Code.NOT_OFFERED, "This employee was not offered this position.");
        }
        var entry = recoveries.get(offered.recoveryId());
        Instant now = now();
        expire(entry, now);
        if (!active(entry)) {
            return terminal(entry);
        }
        if (!offered.offer().respond(ShiftOffer.Status.DECLINED, now)) {
            return result(offered.offer().getStatus() == ShiftOffer.Status.EXPIRED ? Code.OFFER_EXPIRED : Code.OFFER_NOT_PENDING,
                    "Offer is " + offered.offer().getStatus() + ".", entry, null);
        }
        event(entry, EventType.OFFER_DECLINED, now, employeeId, offerId, "Employee declined.");
        escalateIfEmpty(entry, now);
        return result(Code.DECLINED, "Offer declined.", entry, null);
    }

    public synchronized Result cancel(String managerId, String recoveryId) {
        if (store != null) return transact(service -> service.cancel(managerId, recoveryId));
        if (managerId == null || !managerIds.contains(managerId)) {
            return error(Code.NOT_AUTHORIZED, "Manager authorization required.");
        }
        var entry = recoveries.get(recoveryId);
        if (entry == null) {
            return error(Code.NOT_FOUND, "Recovery not found.");
        }
        Instant now = now();
        expire(entry, now);
        if (!active(entry)) {
            return terminal(entry);
        }
        entry.recovery = entry.recovery.cancel();
        for (var offer : entry.offers) {
            close(entry, offer, now, "Manager cancelled the recovery.");
        }
        activeByShift.remove(entry.recovery.shift().id());
        event(entry, EventType.CANCELLED, now, null, null, "Manager " + managerId + " cancelled recovery.");
        return result(Code.CANCELLED, "Recovery cancelled.", entry, null);
    }

    public synchronized Result get(String managerId, String recoveryId) {
        if (store != null) return transact(service -> service.get(managerId, recoveryId));
        if (managerId == null || !managerIds.contains(managerId)) {
            return error(Code.NOT_AUTHORIZED, "Manager authorization required.");
        }
        var entry = recoveries.get(recoveryId);
        if (entry == null) {
            return error(Code.NOT_FOUND, "Recovery not found.");
        }
        expire(entry, now());
        return result(Code.FOUND, "Recovery snapshot.", entry, null);
    }

    /** In-app inbox, refreshed on demand. No background job or closed-app delivery exists. */
    public synchronized List<RecoveryView.Offer> pendingOffers(String employeeId) {
        if (store != null) return transact(service -> service.pendingOffers(employeeId));
        Objects.requireNonNull(employeeId, "employeeId");
        Instant now = now();
        for (var entry : recoveries.values()) {
            expire(entry, now);
        }
        return offers.values().stream().filter(offer -> offer.employeeId().equals(employeeId)
                        && offer.offer().getStatus() == ShiftOffer.Status.PENDING)
                .map(ManagedOffer::view).sorted(Comparator.comparing(RecoveryView.Offer::createdAt)
                        .thenComparing(RecoveryView.Offer::id)).toList();
    }

    public synchronized List<Shift> assignmentsFor(String employeeId) {
        if (store != null) return transact(service -> service.assignmentsFor(employeeId));
        return assignments.values().stream().filter(shift -> shift.assignedEmployeeId().orElseThrow().equals(employeeId))
                .sorted(Comparator.comparing(Shift::id)).toList();
    }

    private Map<String, ScoringRequest> validateInputs(Collection<ScoringRequest> candidates, Shift target,
                                                     EligibilityPolicy policy, Instant now) {
        var inputs = new LinkedHashMap<String, ScoringRequest>();
        for (var candidate : List.copyOf(candidates)) {
            var input = candidate.eligibility();
            if (!input.targetShift().equals(target) || !input.policy().equals(policy) || input.evaluatedAt().isAfter(now)
                    || inputs.putIfAbsent(input.employee().id(), candidate) != null) {
                throw new IllegalArgumentException("Candidates must be unique, match the authoritative target/policy and not be future-dated");
            }
        }
        return inputs;
    }

    private ScoringResult.Ranking evaluate(Map<String, ScoringRequest> inputs, RecoveryConfig config, Instant now) {
        return scoring.rank(inputs.values().stream().map(input -> withLocalAssignments(input, now, true)).toList(), config.scoring());
    }

    private ScoringRequest withLocalAssignments(ScoringRequest request, Instant now, boolean managerInput) {
        request = authoritativeSource(request, now, managerInput);
        var input = request.eligibility();
        var current = new EligibilityRequest(input.employee(), input.targetShift(), input.assignments(), input.workHours(),
                input.policy(), input.approvals(), input.verifiedThrough(), now);
        var base = new ScoringRequest(current, request.history());
        var hours = input.workHours();
        if (input.assignments() == null || hours == null) {
            return base;
        }
        var additional = assignments.values().stream()
                .filter(local -> local.assignedEmployeeId().orElseThrow().equals(input.employee().id()) && !input.assignments().contains(local)).toList();
        if (additional.isEmpty() || eligibility.evaluate(current).status() == EligibilityResult.Status.INELIGIBLE) {
            return base; // Never repair or conceal contradictory source snapshots while merging local facts.
        }
        var merged = new ArrayList<>(input.assignments());
        merged.addAll(additional); // Same ID with different facts is rejected by EmployeeSchedule.
        BigDecimal scheduledSeconds = merged.stream().map(shift -> clippedSeconds(shift.window(), hours.period()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var updatedHours = new WorkHourSummary(hours.employeeId(), hours.period(), scheduledSeconds.divide(BigDecimal.valueOf(3600),
                Math.max(12, hours.scheduledHours().scale()), RoundingMode.CEILING), hours.actualWorkedHours());
        return new ScoringRequest(new EligibilityRequest(input.employee(), input.targetShift(), merged, updatedHours,
                input.policy(), input.approvals(), input.verifiedThrough(), now), request.history());
    }

    private static BigDecimal clippedSeconds(TimeWindow window, TimeWindow period) {
        Instant start = window.start().isAfter(period.start()) ? window.start() : period.start();
        Instant end = window.end().isBefore(period.end()) ? window.end() : period.end();
        if (!end.isAfter(start)) {
            return BigDecimal.ZERO;
        }
        Duration duration = Duration.between(start, end);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private void broadcast(Entry entry, ScoringResult.Ranking ranking, Instant now, Instant deadline) {
        if (!deadline.isAfter(now)) {
            return;
        }
        for (String employeeId : distribution.recipients(ranking)) {
            if (entry.offers.stream().anyMatch(offer -> offer.employeeId().equals(employeeId))) {
                continue; // No silent re-offer after a decline, expiry or withdrawal.
            }
            var candidate = entry.candidates.get(employeeId);
            String id = "offer-" + ++nextOfferId;
            var model = ShiftOffer.forShift(id, employeeId, entry.recovery.shift(),
                    candidate.overallScore().orElseThrow().setScale(0, RoundingMode.HALF_UP).intValueExact(), deadline,
                    candidate.factors().stream().map(ScoringResult.FactorScore::explanation).toList());
            var offer = new ManagedOffer(entry.recovery.id(), employeeId, entry.recovery.shift(), now, candidate, model);
            entry.offers.add(offer);
            offers.put(id, offer);
            event(entry, EventType.OFFER_CREATED, now, employeeId, id, "Simultaneous in-app offer created; score is informational.");
        }
    }

    private void setCandidates(Entry entry, ScoringResult.Ranking ranking) {
        var candidates = new LinkedHashMap<String, ScoringResult>();
        for (var group : List.of(ranking.eligibleCandidates(), ranking.conditionalCandidates(), ranking.excludedCandidates())) {
            for (var candidate : group) {
                candidates.put(candidate.employeeId(), candidate);
            }
        }
        entry.candidates = candidates;
    }

    private void expire(Entry entry, Instant now) {
        if (!active(entry)) {
            return;
        }
        for (var offer : entry.offers) {
            if (offer.offer().expireIfNeeded(now)) {
                event(entry, EventType.OFFER_EXPIRED, now, offer.employeeId(), offer.offer().getId(), "Offer deadline reached.");
            }
        }
        escalateIfEmpty(entry, now);
    }

    private void escalateIfEmpty(Entry entry, Instant now) {
        if (entry.recovery.status() == RecoveryRequest.Status.OPEN && !hasPending(entry)) {
            entry.recovery = entry.recovery.escalate();
            event(entry, EventType.ESCALATED, now, null, null, "No actionable offers remain; manager review required.");
        }
    }

    private boolean hasPending(Entry entry) {
        return entry.offers.stream().anyMatch(offer -> offer.offer().getStatus() == ShiftOffer.Status.PENDING);
    }

    private boolean active(Entry entry) {
        return entry.recovery.status() == RecoveryRequest.Status.OPEN || entry.recovery.status() == RecoveryRequest.Status.ESCALATED;
    }

    private void close(Entry entry, ManagedOffer offer, Instant now, String message) {
        if (offer.offer().close()) {
            event(entry, EventType.OFFER_CLOSED, now, offer.employeeId(), offer.offer().getId(), message);
        }
    }

    private void event(Entry entry, EventType type, Instant now, String employeeId, String offerId, String message) {
        entry.events.add(new RecoveryView.Event(type, now, Optional.ofNullable(employeeId), Optional.ofNullable(offerId), message));
    }

    private Instant deadline(Instant now, Shift shift, RecoveryConfig config) {
        // Avoid overflow for very large configured lifetimes; deadline never extends past shift start.
        return config.offerLifetime().compareTo(Duration.between(now, shift.window().start())) >= 0
                ? shift.window().start() : now.plus(config.offerLifetime());
    }

    private Instant now() {
        Instant now = clock.instant();
        if (now.isBefore(lastObservedTime)) {
            throw new IllegalStateException("Recovery clock moved backwards; refusing state changes");
        }
        lastObservedTime = now;
        return now;
    }

    private Result terminal(Entry entry) {
        return result(entry.recovery.status() == RecoveryRequest.Status.COVERED ? Code.ALREADY_FILLED : Code.RECOVERY_CANCELLED,
                "Recovery is " + entry.recovery.status() + ".", entry, null);
    }

    private Result result(Code code, String message, Entry entry, EligibilityResult eligibility) {
        var view = new RecoveryView(entry.recovery, entry.offers.stream().map(ManagedOffer::view).toList(),
                List.copyOf(entry.candidates.values()), entry.events, Optional.ofNullable(assignments.get(entry.recovery.shift().id())));
        return new Result(code, message, Optional.of(view), Optional.ofNullable(eligibility));
    }

    private Result error(Code code, String message) {
        return new Result(code, message, Optional.empty(), Optional.empty());
    }

    private <T> T transact(Function<RecoveryService,T> operation) {
        return store.transact(state -> {
            var working = new RecoveryService(clock, managerIds);
            working.restore(state);
            T result = operation.apply(working);
            return new RecoveryStore.Change<>(working.state(), result);
        });
    }

    private RecoveryState state() {
        return new RecoveryState(recoveries.values().stream().map(entry -> new RecoveryState.SavedRecovery(entry.recovery,
                entry.policy, entry.config, List.copyOf(entry.inputs.values()), List.copyOf(entry.candidates.values()),
                entry.offers.stream().map(ManagedOffer::view).toList(), entry.events,entry.sourceRevision)).toList(),
                List.copyOf(assignments.values()), List.copyOf(approvalLedger.values()), List.copyOf(authoritativeSources.values()), lastObservedTime, nextOfferId);
    }

    private void restore(RecoveryState state) {
        lastObservedTime = state.lastObservedTime();
        nextOfferId = state.nextOfferId();
        for(var approval:state.approvals()) {
            if(approvalLedger.putIfAbsent(approval.id(),approval)!=null) invalidState("Duplicate approval identity");
        }
        for(var facts:state.sources()) {
            if(authoritativeSources.putIfAbsent(new SourceKey(facts.employee().id(),facts.period()),facts)!=null) invalidState("Duplicate authoritative source");
        }
        for (var shift : state.assignments()) {
            if (shift.assignedEmployeeId().isEmpty() || assignments.putIfAbsent(shift.id(), shift) != null) invalidState("Invalid assignment identity");
        }
        assignments.values().stream().map(shift -> shift.assignedEmployeeId().orElseThrow()).distinct()
                .forEach(employee -> new EmployeeSchedule(employee, assignments.values().stream()
                        .filter(shift -> shift.assignedEmployeeId().orElseThrow().equals(employee)).toList()));
        for (var saved : state.recoveries()) {
            var r = saved.recovery();
            var entry = new Entry(r, saved.policy(), saved.config());
            entry.sourceRevision = saved.sourceRevision();
            if (recoveries.putIfAbsent(r.id(),entry) != null || r.shift().assignedEmployeeId().isPresent()
                    || !entry.policy.accountingPeriod().contains(r.shift().window())) invalidState("Invalid recovery definition");
            if (active(entry) && activeByShift.putIfAbsent(r.shift().id(),r.id()) != null) invalidState("Duplicate active recovery");
            for(var input:saved.inputs()) {
                if(!input.eligibility().targetShift().equals(r.shift()) || !input.eligibility().policy().equals(entry.policy)
                        || entry.inputs.putIfAbsent(input.eligibility().employee().id(),input)!=null) invalidState("Invalid candidate source");
                if(!authoritativeSources.containsKey(new SourceKey(input.eligibility().employee().id(),entry.policy.accountingPeriod()))) invalidState("Missing authoritative source");
            }
            for(var candidate:saved.candidates()) {
                if(!entry.inputs.containsKey(candidate.employeeId()) || entry.candidates.putIfAbsent(candidate.employeeId(),candidate)!=null) invalidState("Invalid candidate result");
            }
            if (!entry.candidates.keySet().equals(entry.inputs.keySet())) invalidState("Missing candidate evaluation");
            for(var view:saved.offers()) {
                if(!view.recoveryId().equals(r.id()) || !view.shift().equals(r.shift()) || !view.employeeId().equals(view.scoring().employeeId())
                        || !view.scoring().eligibility().isEligible() || !view.expiresAt().isAfter(view.createdAt())
                        || view.expiresAt().isAfter(r.shift().window().start())) invalidState("Invalid offer definition");
                long sequence=Long.parseLong(view.id().substring("offer-".length()));
                if(!view.id().startsWith("offer-") || sequence<=0 || sequence>nextOfferId) invalidState("Invalid offer sequence");
                var model=ShiftOffer.forShift(view.id(),view.employeeId(),view.shift(),
                        view.scoring().overallScore().orElseThrow().setScale(0,RoundingMode.HALF_UP).intValueExact(),view.expiresAt(),
                        view.scoring().factors().stream().map(ScoringResult.FactorScore::explanation).toList());
                switch(view.status()) {
                    case ACCEPTED, DECLINED -> model.respond(view.status(),view.createdAt());
                    case EXPIRED -> model.expireIfNeeded(view.expiresAt());
                    case CLOSED -> model.close();
                    case PENDING -> { if(r.status()!=RecoveryRequest.Status.OPEN || !entry.inputs.containsKey(view.employeeId())) invalidState("Orphan pending offer"); }
                }
                var managed=new ManagedOffer(r.id(),view.employeeId(),view.shift(),view.createdAt(),view.scoring(),model);
                if(offers.putIfAbsent(view.id(),managed)!=null) invalidState("Duplicate offer");
                entry.offers.add(managed);
            }
            entry.events.addAll(saved.events());
            if(entry.events.isEmpty() || entry.events.getFirst().type()!=EventType.CREATED) invalidState("Missing recovery creation event");
            Instant prior=r.requestedAt();
            for(var event:entry.events) {
                if(event.at().isBefore(prior) || event.at().isAfter(lastObservedTime)) invalidState("Invalid event time");
                prior=event.at();
            }
            long winners=entry.offers.stream().filter(offer -> offer.offer().getStatus()==ShiftOffer.Status.ACCEPTED).count();
            if(r.status()==RecoveryRequest.Status.COVERED) {
                var assigned=assignments.get(r.shift().id());
                if(winners!=1 || assigned==null || !assigned.assignedEmployeeId().equals(r.replacementEmployeeId())
                        || entry.offers.stream().noneMatch(offer -> offer.offer().getStatus()==ShiftOffer.Status.ACCEPTED
                                && r.replacementEmployeeId().equals(Optional.of(offer.employeeId())))) invalidState("Inconsistent filled recovery");
            } else if(winners!=0) invalidState("Acceptance without filled recovery");
            if(!active(entry) && hasPending(entry)) invalidState("Terminal recovery has pending offers");
        }
        for(var assignment:assignments.values()) {
            if(recoveries.values().stream().noneMatch(entry -> entry.recovery.status()==RecoveryRequest.Status.COVERED
                    && entry.recovery.shift().id().equals(assignment.id())
                    && entry.recovery.replacementEmployeeId().equals(assignment.assignedEmployeeId()))) invalidState("Orphan assignment");
        }
    }

    private static void invalidState(String message) { throw new PersistenceException("Invalid persisted workflow: " + message); }

    private long nextSourceRevision() {
        return Math.addExact(recoveries.values().stream().mapToLong(entry -> entry.sourceRevision).max().orElse(0), 1);
    }

    private void registerApprovals(Collection<ScoringRequest> inputs) {
        var updated = new LinkedHashMap<>(approvalLedger);
        for (var request : inputs) {
            if (request.eligibility().approvals() == null) continue;
            for (var approval : request.eligibility().approvals()) {
                if (approval == null) continue; // Eligibility reports missing approval records; persistent storage rejects malformed rows.
                var previous = updated.putIfAbsent(approval.id(), approval);
                if (previous != null && !previous.equals(approval)) throw new IllegalArgumentException("Conflicting immutable approval ID: " + approval.id());
            }
        }
        approvalLedger.clear();
        approvalLedger.putAll(updated);
    }

    /** Newest verified facts win across recoveries for the same employee/period. Receipt order breaks equal-cutoff ties. */
    private ScoringRequest authoritativeSource(ScoringRequest request, Instant now, boolean managerInput) {
        var original = request.eligibility();
        var known=authoritativeSources.get(new SourceKey(original.employee().id(),original.policy().accountingPeriod()));
        boolean useKnown=known!=null && (!managerInput || (known.verifiedThrough()!=null && original.verifiedThrough()!=null
                && !known.verifiedThrough().isAfter(now) && known.verifiedThrough().isAfter(original.verifiedThrough())));
        List<OvertimeApproval> approvals = original.approvals();
        if (approvals != null) {
            var merged = new LinkedHashMap<String,OvertimeApproval>();
            for (var decision : approvalLedger.values()) {
                if (decision.employeeId().equals(original.employee().id()) && decision.period().equals(original.policy().accountingPeriod())) merged.put(decision.id(),decision);
            }
            for (var decision : approvals) {
                if (decision == null) return request; // Preserve malformed-input reporting.
                var previous = merged.putIfAbsent(decision.id(),decision);
                if (previous != null && !previous.equals(decision)) throw new IllegalArgumentException("Conflicting immutable approval ID: " + decision.id());
            }
            approvals = List.copyOf(merged.values());
        }
        return new ScoringRequest(new EligibilityRequest(useKnown?known.employee():original.employee(), original.targetShift(),
                useKnown?known.assignments():original.assignments(), useKnown?known.workHours():original.workHours(),
                original.policy(), approvals, useKnown?known.verifiedThrough():original.verifiedThrough(), now),request.history());
    }

    private void registerSources(Collection<ScoringRequest> inputs,long revision) {
        for(var request:inputs) {
            var input=request.eligibility();
            var key=new SourceKey(input.employee().id(),input.policy().accountingPeriod());
            var previous=authoritativeSources.get(key);
            if(previous!=null && previous.verifiedThrough()!=null && input.verifiedThrough()!=null
                    && !previous.verifiedThrough().isAfter(lastObservedTime) && previous.verifiedThrough().isAfter(input.verifiedThrough())) continue;
            authoritativeSources.put(key,new RecoveryState.SourceFacts(input.employee(),input.policy().accountingPeriod(),
                    input.assignments(),input.workHours(),input.verifiedThrough(),revision));
        }
    }
}
