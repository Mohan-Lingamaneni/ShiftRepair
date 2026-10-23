# EligibilityEngine (Batch 2)

`com.shiftrepair.service.EligibilityEngine` evaluates immutable snapshots without
JavaFX, IO, mutation, scoring, offer dispatch or assignment. It accepts one
`EligibilityRequest` and returns an `EligibilityResult` with `ELIGIBLE`,
`REQUIRES_APPROVAL` or `INELIGIBLE`, stable reason codes/messages and, when the
hard checks and data validation permit calculation, projected hours. Only
`isEligible()` indicates a fully eligible result. Conditional candidates must not
be automatically assigned. Evaluation time is supplied, making tests deterministic.

## Existing model limitations and boundary contract

Batch 1 models and constructors are unchanged. They already validate identities,
time windows, immutable collections and schedule consistency. `EligibilityPolicy`
wraps the existing `OvertimePolicy` with an explicit accounting `TimeWindow`, maximum
snapshot age, approval lifetime, authorized manager IDs and qualification substitutions.
It has no default 40-hour week or default timezone. Construct the period using the
company's actual zoned workweek boundaries, including DST, before passing instants.

`WorkHourSummary` has neither an actual-hours cutoff nor source freshness metadata.
The request's `verifiedThrough` supplies the cutoff: actual hours must account for
the period start through that instant. The caller also attests that employee status,
qualification/availability data, shift requirements, policy and the **complete**
assignment snapshot were verified then. A timestamp alone is not evidence of source
quality; a future integration must establish this contract. Zero is valid verified
actual work; null is missing data. Empty schedules and approvals mean known empty
collections, not a failed lookup. Empty required qualifications means explicitly no
qualification requirements, not unknown requirements.

The engine rejects missing inputs, future/out-of-period verification, stale
snapshots, mismatched employee/period summaries, impossible actual hours, malformed
assignments, duplicate target IDs, ambiguous qualification expiry records and
contradictory approval IDs. Malformed raw assignment lists are checked using the
existing `EmployeeSchedule`, returning structured reasons. Model/policy constructors
continue to throw for impossible individual values; the engine cannot inspect an
object that could not be constructed.

Targets must be explicitly unassigned coverage positions, start at/after evaluation
and fit wholly inside the supplied accounting period. A call-out's original assigned
snapshot must first be mapped to an uncovered position; this batch does not do that
UI/service integration. An in-progress target needs an explicit remaining coverage
position. Targets crossing a workweek boundary are rejected instead of checking
only one portion and potentially authorizing overtime in the other period.

## Hard requirements

- Active employment.
- Every role, skill and certification requirement matches a held qualification
  valid throughout the entire shift. No priority is inferred from a job title.
- A company can explicitly allow a held qualification as a substitute for a
  requirement. Mappings are directional and direct, not symmetric/transitive.
  Substitutes still require full-shift validity. Case-sensitive codes remain unchanged.
- Availability fully covers the requested interval, allowing adjacent windows.
- No overlap with any assignment. Half-open instants permit adjacency, handle
  cross-midnight shifts and avoid time-of-day-only comparisons.

All ordinary hard failures are collected in stable order. Invalid data is rejected
before calculations; no approval or policy mode can override a hard failure.

## Hours, without double counting

Require the summary's scheduled hours to equal the full assignment snapshot's
scheduled duration clipped to the accounting period. This is reconciliation only;
that aggregate is **not** added to actual hours. As corrected in Batch 4, compare
to the aggregate rounded upward to at least 12
decimal hour places (or the summary's greater precision). Repeating decimals such
as one second cannot be stored exactly in decimal hours. Projections and overtime
comparisons continue to use exact seconds, never this rounded aggregate.
An omitted or contradictory
schedule total fails closed. Actual hours must not exceed elapsed time from period
start to the verification cutoff, but may exceed scheduled hours (unscheduled work).

Projection equals:

1. Verified actual hours from period start through `verifiedThrough`.
2. All elapsed time from verification through evaluation, reserved as potentially
   worked time when the snapshot is within the configured freshness tolerance.
3. Only future scheduled duration from evaluation through period end.
4. The full duration of the target coverage position.

Thus a completed scheduled shift is not counted again; an in-progress assignment
contributes only its remaining future portion. The unverified gap is reserved once,
regardless of whether scheduled work existed then. This intentionally conservative
upper bound can produce false negatives; it cannot silently ignore unscheduled work
since verification. Set maximum snapshot age to zero to require current verification.
No fractional actual hours are guessed from past scheduled time.

All durations are treated as potentially worked elapsed time, including breaks.
Until integrations can supply verified paid-duration allocation, this may overstate
future working hours. No payroll/break deductions are guessed. This engine supports
one period and threshold, not simultaneous daily/weekly limits or legal compliance.

Threshold and approval comparisons use exact decimal seconds, including nanoseconds.
Returned projected hours round upward to at least 12 decimal places (preserving
any greater configured threshold precision) for reporting and must
not be used to reconstruct the engine's exact threshold comparisons.

## Overtime decisions

- At/below the configured threshold: eligible after hard checks.
- Above the threshold, prohibited: ineligible even if an approval was supplied.
- Above the threshold, permitted: eligible after hard/data checks, without approval.
- Above the threshold, approval required: conditional until a valid decision exists.

Approval must match employee ID, shift ID and accounting period, be recorded by a
currently authorized manager, not be future-dated, and remain inside the company's
explicit validity interval. Expiry is exclusive at `decidedAt + approvalValidity`.
The approved amount must cover **total projected period overtime**, not just the
incremental overtime attributed to the target. Approvals are never added together.

Latest scoped decision wins; a current denial makes the candidate ineligible.
Conflicting IDs or multiple latest records at the same timestamp fail closed rather
than allowing collection ordering to select a winner. Identical duplicate records
are harmless. Missing, expired, unauthorized, insufficient or irrelevant approvals
leave the candidate conditional, with a specific reason.

Batch 1 approvals have no shift/schedule/policy revision fingerprint. Conservatively,
a decision predating the verified snapshot requires revalidation: the engine will
not assume it approves the latest facts. This can require frequent reapproval until
a future versioned approval scope is designed. Evaluation alone does not verify a
manager's real identity or persist approval/revocation history. Authoritative callers
must supply complete decision history and refresh invalidated facts. Approval is
checked at evaluation; acceptance must recheck it before assignment.

## Before ScoringEngine

Agree on scoring factors, weights, fairness inputs and explanation requirements.
Keep hard eligibility separate: fully eligible candidates can be ranked, while
conditional candidates must retain their approval requirement. Define any real
company rules beyond this single-period foundation before treating engine results
as production-ready (break allocation, rest/travel constraints, qualification grants/
revocations, multiple periods/thresholds, trusted data and versioned approvals).

ScoringEngine must not select sequential, simultaneous or tiered distribution.
No broadcasting, assignment, notification, persistence or frontend wiring is added
here. A future assignment command must re-evaluate and atomically coordinate schedule,
recovery and offer state to prevent stale eligibility and concurrent winners.
