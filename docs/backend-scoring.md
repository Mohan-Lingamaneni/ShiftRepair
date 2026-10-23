# ScoringEngine (Batch 3)

This is an example suitability model, not a final business policy or an assignment
decision. `ScoringEngine.score(ScoringRequest, ScoringConfig)` always runs the existing
EligibilityEngine first. Ineligible employees receive the complete eligibility
reasons and **no score or factor breakdown**. Fully eligible and approval-required
employees can be scored; their full eligibility result is preserved unchanged.

`rank(Collection<ScoringRequest>, ScoringConfig)` evaluates the candidates and returns
three separate immutable lists: eligible, conditional and excluded. The first two
are independently sorted by descending score, then case-sensitive employee ID.
Excluded candidates have no score and are listed by ID only. Equal-score IDs provide
stable presentation order, not a suitability advantage; ties retain identical scores.
Do not treat this ordering as an offer sequence. Conditional candidates never enter
the eligible list, even with a higher score. No notifications, distribution strategy,
assignment, persistence or JavaFX/demo wiring are included.

## Configuration and exact formula

Weights are finite `BigDecimal` percentages, each in [0,100], summing exactly to 100.
Zero weights disable a factor's contribution; all four factors are still explained.
BigDecimal cannot represent NaN/infinity. There is no implicit weight normalization.

`ScoringConfig.provisionalExample()` uses **provisional, non-final** example weights:

| Factor | Weight |
|---|---:|
| Availability fit | 30% |
| Workload balance | 30% |
| Fairness history | 25% |
| Response history | 15% |

It also uses a provisional 28-day history lookback and **no availability buffer
preference** (zero). Both are explicit configurable inputs, not approved company rules.

For each factor, compute its score in [0,100] and round HALF_UP to six decimal places.
Contribution = rounded factor score × percentage weight / 100, using exact decimal
arithmetic. Overall score = sum of the four contributions, with no further rounding.
Every contribution, factor, weight and total is validated, and the factor breakdown
must reconcile exactly to the overall score. The same inputs produce identical
results; the engine does not read a clock or random generator.

### Availability fit

With a zero buffer preference, give every scored candidate 100 and mark the factor
`NOT_DIFFERENTIATED`. Passing mandatory availability is not treated as evidence that
one candidate is better. This is the default because availability windows do not
establish an employee's preference for additional flexibility.

A company may opt into a positive surrounding-time buffer B. Merge adjacent/overlapping
availability windows and find the connected interval containing the target. Clip
the interval to evaluation time and the closest committed assignments before/after
the target. Let L and R be usable elapsed seconds before and after the shift:

`availability = 100 × (min(L,B) + min(R,B)) / (2 × B)`

This measures declared scheduling flexibility, not mandatory rest/travel time, a
request to work longer or new working hours. An exactly fitting window scores zero
under this optional preference while remaining eligible. Disconnected windows never
provide buffer credit. Broad availability can reach full credit, without unbounded
advantages. Enabling this factor preference needs company agreement; it may favor
employees able to declare broader windows and is not proof of willingness to extend.

### Workload balance

Let H be EligibilityEngine's projected period hours including the target, and T the
company's explicit regular-hours threshold:

`workload = 100 × max(0, T − H) / T`

The score rewards remaining workload headroom. At or above T the score is zero;
permitted/approved overtime does not become ineligible through scoring. The engine
uses the existing projection, including verified actuals, conservatively reserved
unverified elapsed time and only future scheduled work. It does not reconstruct
hours from scheduled totals, double-count elapsed schedules or guess actual work.
Eligibility's upward-rounded reported projection is used conservatively for this
preference; exact eligibility threshold comparisons remain EligibilityEngine's job.

Ranked candidates must share the same target snapshot, policy/accounting period and
evaluation time. Duplicate employee IDs or mixed comparison contexts are rejected.
This avoids comparing different denominators, workweeks or shift requests as though
they were one ranking. Assignments crossing period boundaries retain EligibilityEngine's
clipping semantics; a target crossing periods remains excluded under Batch 2 rules.

### Fairness history

Only use a caller-supplied, complete genuine history for that employee covering
`[evaluatedAt − historyLookback, evaluatedAt)`. Let O be recorded offers created in
that window and A be recorded recovery coverage assignment events in that window:

`fairness = 100 / (1 + O + A)`

This is a provisional recent-opportunity exposure measure. It is not a final fairness
definition, payroll record or attendance judgment. Offer delivery and a resulting
assignment count as two events deliberately; pending offers count as received
opportunities. Events do not imply an acceptance preference. The formula currently
does not adjust for unequal opportunity, part-time contracts, offer duration, shift
length, or distribution policy. Broadcast strategies could substantially change
offer exposure, so calibrate this factor before any production rollout.

### Response history

An offer is completed for measurement when a response is recorded or its deadline
has passed (including deadline exactly at evaluation). Pending unanswered offers
are excluded from the denominator. Let C be completed offers and R be responses
strictly before their deadlines:

`response = 100 × R / C` when C > 0; otherwise 100.

Acceptance and decline both count as timely responses. Late responses and missing
responses on expired offers receive no timely credit; a response exactly at the
deadline is late, consistent with ShiftOffer's expiry boundary. An early response
to an offer with a still-future deadline is already measurable. The engine does not
measure device use, response speed, willingness to accept or personal attributes.

## Historical inputs and missing data

The minimal `ScoringHistory` contains an employee ID, complete historical window,
offer event IDs/timestamps/deadlines/optional responses, and coverage assignment
event IDs/timestamps. These are read-only factual scoring inputs, not an offer
service or duplicate employee/shift models. Constructors reject missing identities,
impossible event times, duplicate IDs and events outside the history window.
Lists are defensively copied. No history is generated from demo data, scheduled
shifts or an employee's name.

Absent history gives fairness and response **equal full credit (100)** with
`MISSING_HISTORY`. A genuine empty window gives fairness 100 with `AVAILABLE`;
response with no completed offers gives 100 with `NO_COMPLETED_OFFERS`.
Employee/window mismatches, stale or incomplete windows and future response timestamps
make the history unusable: both factors get 100 with `INVALID_HISTORY` and an
explanation. These indicators remain visible even when a factor has zero weight.

This deliberately avoids an automatic penalty for new employees or missing records,
but can advantage unknown history over known imperfect history. Scores with different
data coverage should therefore be reviewed with their indicators; they are not
confidence-adjusted probabilities. Future callers must provide complete trusted
records and monitor gaps rather than selectively omit unfavorable events. Timestamps
and counts alone cannot prove source authenticity or completeness.

Malformed scoring API/configuration inputs throw clear constructor/API exceptions.
Eligibility data failures still use EligibilityEngine's structured ineligible result.
No method accepts a caller-provided eligibility status or external score for ranking,
avoiding accidental use of forged or unrelated results.

## Tests and next decisions

Tests cover configuration/bounds, exact contributions, workload reuse, historical
inputs, missing/invalid data, optional availability margins, ties/order independence,
weight-driven ranking changes, conditional separation, eligibility exclusions,
immutability and cross-period calculation. Run `mvn -B clean compile` and `mvn -B test`
with a compatible JDK; the project release remains 23.

Before using this operationally, agree on weights, availability preference, fairness
meaning/window and missing-data treatment. Validate historical data contracts and
versioned eligibility/approval snapshots. Re-evaluate eligibility at acceptance and
coordinate assignments atomically in a later batch. None of these suitability
preferences chooses simultaneous, sequential or tiered notification behavior.
