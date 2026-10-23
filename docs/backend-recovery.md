# Recovery workflow and in-app offers (Batch 4)

`RecoveryService` is the shared in-memory workflow owner. Construct **one instance
per application** and pass that instance to every future manager/employee adapter.
Independent instances or application processes do not share state. Nothing is wired
into the current JavaFX demo, which retains its original simulated workflow.

## Implemented flow

1. An authorized manager calls `create` with a unique recovery ID, an explicitly
   unassigned future `Shift`, company `EligibilityPolicy`, candidate `ScoringRequest`
   snapshots and `RecoveryConfig`. The manager attests that the position is genuinely
   uncovered in the source schedule. No external system exists to independently
   verify that assertion. Stable shift IDs identify positions, not display labels.
2. Reject already-assigned positions, invalid periods/time windows, reused recovery
   IDs and duplicate active recoveries for a shift. Escalation remains active and
   reserves the shift; cancellation releases the reservation. A locally filled shift
   cannot be recovered again using a new recovery ID.
3. Rebase evaluation to the service's injected clock and run existing eligibility
   before scoring. Keep the complete results/reasons, including ineligible and
   conditional candidates, in manager snapshots.
4. `SimultaneousOfferPolicy` selects **all fully eligible** recipients. Every offer
   in that broadcast has the same creation/deadline timestamps. Scores are informational;
   they neither suppress recipients nor award the shift. This initial distribution
   policy is provisional, separate from eligibility/scoring, and implements no tiers
   or sequential cascade.
5. Employees poll `pendingOffers(employeeId)` for an immutable in-app inbox. They
   call `accept` or `decline` with their ID and an offer ID. Ownership is checked.
   Unknown offers and attempts to use another employee's offer return clear results.
6. Acceptance checks current recovery/offer state, deadline, and eligibility again,
   using the latest manager-supplied snapshot for this recovery and all assignments
   made by this service. Only the first fully eligible acceptance processed succeeds.
7. Store the assigned `Shift`, mark the existing `RecoveryRequest` **COVERED** (the
   established model's FILLED equivalent), accept the winning `ShiftOffer`, close all
   competing pending offers, release the active reservation and record a FILLED event.
   Later acceptance attempts return `ALREADY_FILLED`, including retries by the winner.
8. Decline affects only that offer. Expiry, withdrawal and cancellation prevent later
   acceptance. When no pending offers remain, transition to `ESCALATED` for manager
   review. No-candidate and conditional-only recoveries escalate immediately.

## Reuse and lifecycle

The existing `ShiftOffer` remains the offer state model, with one additional `CLOSED`
status and a pending-only `close` transition. The original demo constructors and
response/countdown/overnight behavior are unchanged. A new `forShift` factory retains
the exact absolute Shift duration, including multi-day and DST intervals. Its legacy
display date/time getters use UTC; future UI integration should format the absolute
shift instants in the configured location zone instead. The full decimal scoring
result is retained in `RecoveryView.Offer`; the legacy integer coverage score is only
a compatibility projection and never controls assignment.

`RecoveryRequest` adds `ESCALATED`, reopen-from-escalation and cancellation from
escalation. Its existing `COVERED` name/behavior is preserved. Terminal recoveries
cannot reopen, cancel or fill again. Escalated requests reopen only if manager
re-evaluation creates an actionable offer.

`RecoveryView`, its nested `Offer` and `Event`, and command `Result` are detached read
models. No live mutable `ShiftOffer`, mutable store map or mutable event list escapes
the service. A previously returned snapshot does not change after later commands.

## Concurrency and assignment consistency

Every service command and state-reading query is synchronized on the same instance.
The monitor covers duplicate reservation checks, final eligibility evaluation,
assignment validation, winning offer response, assignment recording, recovery state
and competing-offer closure. Queries cannot observe a half-filled recovery. No IO,
callbacks or network requests execute inside the critical section.

All local coverage assignments are retained centrally. They are merged into
candidate schedules before evaluation, including across different recoveries. This
prevents concurrent claims from double-booking one employee or silently exceeding
the overtime threshold on otherwise non-overlapping shifts. Already-present identical
source assignments are not counted twice; conflicting versions fail validation.
Contradictory raw source summaries are not repaired by the merge. Scheduled duration
is summed in exact seconds and rounded once for the decimal-hour summary; eligibility
still uses exact interval durations for projection/threshold decisions.

Two regression tests first demonstrated a Batch 2 defect: strict equality between
seconds and decimal-hour summaries rejected a valid one-second assignment. The small
EligibilityEngine correction reconciles against an upward-rounded aggregate at the
documented precision. It does not change projected hours, thresholds, approvals,
qualification checks or availability rules. A further test covers accumulation of
multiple fractional-duration assignments without per-assignment rounding drift.

These guarantees are **process-local and instance-local**. Use one shared service;
constructing a new service creates a separate empty store. State disappears on exit.
Before persistence, implement equivalent database transactions/locking or conditional
updates, a uniqueness constraint for active recovery/shift ownership, durable command
idempotency and atomic assignment plus offer closure across application processes.

## Current data, overtime and manager review

No source lookup or identity service is invented. Trusted adapters must authenticate
actor IDs before calling this API; knowing an ID is not authentication. The service
checks its configured manager allowlist and offer ownership, but has no login/session
security. Configure both application manager access and company approval authority
explicitly; they are not automatically inferred from an employee role.

`reevaluate` is a manager-only complete candidate refresh for an active recovery.
It accepts genuine already-recorded `OvertimeApproval` information through the existing
eligibility snapshots and runs the existing engines. Newly fully eligible candidates
receive offers; pending offers for removed/ineligible/conditional candidates close.
Conditional candidates retain scores and reasons but never receive claimable offers.
The service does not auto-approve, manufacture approvals, or provide an approval-entry
UI. An approval recording/authorization UI is deferred; the backend re-evaluation path
for valid recorded approval is functional. Expired, insufficient or still-pending
approval fails the acceptance recheck.

The eligibility verification cutoff and freshness rules remain in force. Acceptance
does **not** replace `verifiedThrough` with the current time or pretend old actual
hours were just verified. Future input evaluation timestamps are rejected; slightly
earlier caller evaluation timestamps are safely replaced by the service clock. A
freshness window allows the existing conservative unverified-time reservation.
Current facts not supplied to this recovery cannot be discovered automatically:
adapters must refresh affected recoveries after external employee/schedule changes.
Actual-work completeness remains the source provider's responsibility.

Company policy and shift identity are fixed for a recovery. To change them, cancel
and create a new recovery with authoritative current facts. Approval revision/time
rules remain as documented in Batch 2; new facts may require reapproval. Completed
local assignments cannot be undone by recovery cancellation; corrections/reassignment
need a separate future audited command.

## In-app delivery, expiration and events

Offer lifetime is a positive configurable duration with no invented company default.
The deadline is the earlier of creation + lifetime and shift start. The exact deadline
is exclusive. No offers are issued once the shift starts. Expiration is applied
lazily during inbox queries, manager reads/refreshes and employee/manager commands.
There is no actual background timer, email, SMS, push or closed-application delivery.
Queries see a coherent state but may record expiration/escalation as a side effect.

Timestamped events cover creation, evaluation, offers, decline, expiry, closure,
rejected acceptance, fill, cancellation, escalation and reopening. Command timestamps
come from the injected clock; manager action messages retain the acting manager ID.
Expiry events record when the deadline was observed, while the offer retains the
exact effective expiration time. A backwards-moving clock fails closed before a
mutation. Events are useful process-local history, not a durable audit log.

A candidate is offered at most once per recovery. Refresh does not silently resend
after decline, expiry or withdrawal, and does not extend pending deadlines. A manager
may add new candidates or cancel/recreate an unfilled recovery when a deliberate
new round is needed. This anti-repeat choice and escalation/resend behavior need
review before finalizing manager UX.

## Verification and remaining integration work

Run `mvn -B clean compile` and `mvn -B test` with the compatible JDK. Tests exercise
full workflows and immutable inboxes, eligibility/scoring preservation, approval
refresh, expiry/cancellation/escalation, stale data, retries, ownership checks,
overnight shifts and repeated latch-coordinated races. Races include two acceptors,
duplicate recovery creation, cancellation versus acceptance, same-offer retries and
overlapping recoveries for one employee.

No existing screens, controllers, resources, navigation, sample data, POM release,
ScoringEngine or notification provider were changed. Before frontend integration,
define authenticated session adapters, location-zone rendering, trusted snapshot
refresh, manager approval entry, explicit retry/escalation controls and polling.
Before persistence, add durable transactions, recovery on restart, data retention,
idempotency, schema migration and multi-process concurrency tests. Do not treat this
in-memory workflow as a payroll/timekeeping or company-wide scheduling replacement.
