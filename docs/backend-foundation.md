# Backend foundation

Batch 2 adds the independent EligibilityEngine described in
[backend-eligibility.md](backend-eligibility.md). The Batch 1 history and design
notes below remain as context; the frontend still uses its original demo behavior.

## Existing application

`App` loads `app-shell.fxml`. `AppShellController` replaces the shell's center
with FXML pages and sets navigation state; pages are loaded anew on navigation.
`ThemeManager` manages the light/dark stylesheets. FXML and controller-local
JavaFX row classes supply fictional employees, schedules, recovery counts,
eligibility labels, candidate scores and reasons. Filters and manager recovery
buttons operate on that local demonstration state, not a shared backend.

`ShiftOffer` is the existing Java-only shared model. It implements one-shot
accept/decline, deadline expiry, countdown rounding and overnight shift duration.
`IncomingShiftOfferController` owns a static demo offer, uses a JavaFX Timeline
to refresh expiry, and exposes offer/response callbacks. `EmployeeViewController`
reads that same demo offer. This survives page navigation, not process restart.
Manager recovery actions do not update that employee offer or a shared schedule.
Availability selection updates display labels only. There were no automated tests.

## Domain boundaries

New types live alongside `ShiftOffer` in `com.shiftrepair.model` and use no JavaFX.
Records are immutable snapshots with defensive collection copies. Existing
controllers and resources are unchanged and do not yet consume these models.
Controller-private row types remain presentation objects, not competing domain models.

- `Employee`: stable ID, display name, active/inactive status, qualifications and availability.
- `Qualification`: company-defined, case-sensitive role/skill/certification code.
  `EmployeeQualification` adds an optional expiry; validity must cover the entire shift.
- `TimeWindow`: absolute instants with positive duration and half-open boundaries.
  Adjacent shifts are allowed; overnight shifts require no special case. Location
  time zones must be applied when creating/displaying instants; no host-zone default.
- `Availability`: explicit positive windows. Adjacent/overlapping windows can combine;
  a gap or empty list does not cover a shift. It does not imply eligibility.
- `Shift`: one coverage position with location ID, requirements and optional assigned
  employee ID. Open shifts have no assignee. Multi-person shifts can later group positions.
- `EmployeeSchedule`: validated assignments for one employee, rejecting wrong assignees,
  duplicate shift IDs and overlaps. This is a snapshot, not a transactional assignment service.
- `RecoveryRequest`: open, covered or cancelled; terminal transitions are rejected.
  It retains the original shift snapshot and records the replacement separately.
  `cover` records a result only; it does not prove eligibility, modify schedules or send offers.
- `WorkHourSummary`: separate nonnegative decimal scheduled and actual-worked totals
  for an explicit accounting period. Actual hours may exceed scheduled hours. These
  are supplied summaries, not clock-in/out records or payroll calculations.
- `OvertimePolicy`: prohibited, manager-approval-required or permitted, with an explicit
  positive regular-hours limit. Threshold comparison does not grant eligibility.
- `OvertimeApproval`: immutable approved/denied manager decision, attributed to an
  employee, shift and accounting period, with positive overtime hours, timestamp and reason.
  Absence of a record means no decision recorded; approval never alters hour totals.

Identifiers must be nonblank, but reference existence and cross-snapshot consistency
need a future service/repository. No global uniqueness, locking, persistence,
manager authorization or production audit guarantees are claimed here. Qualification
expiry is exclusive: expiry exactly at shift end covers the half-open shift. Grants
have no effective-start dates yet; revocations and recurring availability are future work.
Hours are supplied accounting values, not derived from elapsed shift duration: break
rules, accounting zones and period allocation are deliberately not guessed.

## Before EligibilityEngine

Hard checks must independently evaluate active status, all required qualifications
valid throughout the shift, full availability, schedule conflicts and company rules.
An unavailable or unqualified employee must not become eligible through scoring.
Approval-required overtime should remain conditional until a matching authorized
approval exists; prohibited overtime must not be overridden by an approval record.

Decide company accounting period/time zone, daily versus weekly thresholds, which
scheduled/actual projections count (without double-counting already worked scheduled
hours), breaks, rest limits, cross-location travel constraints, and approval scope,
revocation and revalidation after schedule changes. None has a universal default here.
Future scheduling commands must coordinate schedule and recovery updates atomically,
recheck eligibility at acceptance, and prevent two winners or conflicting assignments.

Retain **ScoringEngine** as a separate future component for suitability of candidates
that pass hard requirements. Its factors, weights, fairness rules and explanations
remain undecided; the UI's 92/86/74 scores are still samples. No fake scorer is added.
Offer distribution (sequential, simultaneous, tiered), notification delivery and
winner resolution remain undecided. The manager demo's cascade does not select a
backend policy. `ShiftOffer` remains the single existing offer model; its legacy
display names/date/time fields need an explicit ID/time-zone mapping when connected
to recovery records, rather than a second competing offer type.

No database, external integration, payroll, clock-in/out, eligibility engine,
scoring implementation, notification service or UI wiring is included in this batch.

## Verification

JUnit Jupiter and Surefire are configured only for tests; Java release 23 and JavaFX
23.0.2 are unchanged. Run `mvn clean compile` and `mvn test`. Tests cover domain
validation, immutable snapshots, interval/expiry boundaries, schedule conflicts,
hour separation, policy modes, approval attribution, recovery transitions and the
existing offer's response/expiry/overnight behavior. Start the UI with
`mvn javafx:run`; visual confirmation is separate from successful compilation.
