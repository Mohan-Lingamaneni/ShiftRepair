# Reliable local persistence (Batch 5)

SQLite/JDBC is the durable local desktop-demo store. Domain models, EligibilityEngine,
ScoringEngine and the existing JavaFX demo remain unchanged. The existing in-memory
RecoveryService constructor remains available; persistent mode uses the **same**
workflow implementation inside a database unit of work, not a parallel recovery engine.

## Opening and initialization

```java
RecoveryService service = RecoveryService.open(
        SqliteRecoveryStore.defaultDatabasePath(),
        Clock.systemUTC(),
        Set.of("configured-manager-id"));
```

Supply real authenticated manager configuration, not this illustrative ID. Alternatively
pass an explicit local `Path` to `open`. `defaultDatabasePath()` only resolves
`%LOCALAPPDATA%\ShiftRepair\shiftrepair.db`; it does not create anything by itself.
The test suite uses exclusively JUnit temporary directories. No real user database
has been created, modified or seeded during this batch.

`open` creates missing parent directories and initializes a genuinely empty database
with application ID and schema version 1 (`PRAGMA user_version`). Initialization is
transactional and idempotent. Unknown existing databases, non-database files, version
zero with existing tables, and unsupported schema versions are rejected without a
reset. There is no DROP/recreate or automatic demo seeding. Version 0 empty -> 1 is
the only migration currently implemented; future migrations must be explicit and
transactional, with backup/upgrade tests. JSON value encodings are part of version 1.

An open service owns no permanent JDBC connection and needs no close method. Every
operation opens/closes its connection. No repository, service or UI silently switches
to memory mode after a database error.

## Layers and source of truth

- Existing `model` types and eligibility/scoring services contain business rules.
- `RecoveryService` owns recovery orchestration and exposes the existing command/query
  APIs plus an explicit durable factory/constructor.
- `RecoveryStore` defines a transaction boundary; `RecoveryState` transfers immutable
  state using existing domain objects. It is not a competing domain model.
- `SqliteRecoveryStore` implements JDBC, schema initialization, relational mapping,
  constraints, rollback and typed value encoding.
- Future scheduling/timekeeping adapters must provide authenticated, genuinely
  verified snapshots. Persistence is not an external source adapter or time clock.

In persistent mode **every operation**, including reads that may expire offers,
uses `BEGIN IMMEDIATE` before loading current committed state. The operation runs
on a private short-lived RecoveryService, then saves and commits before returning
its result. A long-lived facade holds no authoritative cached recoveries or offers.
Two facades on the same local database therefore see each other's committed changes.
Business synchronization is retained, but database locking/constraints provide the
cross-connection protection. The callback performs no network or external IO.

Foreign keys are enabled on every connection, synchronous durability is FULL, and
lock contention waits up to five seconds before a clear storage failure. SQLite's
default rollback journal is used; this batch does not require WAL. Operations are
not automatically replayed after a storage error. A caller may retry an identified
command; duplicate creation and already-filled acceptance remain safe.

## Tables

| Table | Purpose |
|---|---|
| `metadata` | Durable offer sequence and last observed service time |
| `employees` | Stable employee identity registry; names/status belong to verified source snapshots |
| `shifts` | Immutable coverage-position definitions with existing typed Shift values |
| `qualifications` | Company qualification type/code dictionary |
| `authoritative_sources` | Latest retained employee/schedule/hour facts per employee and explicit accounting period |
| `recoveries` | Shift ownership, state, manager, creation time, immutable policy/configuration, source receipt revision |
| `candidate_sources` | Per-recovery input snapshots, original verification/evaluation times, history, and latest result |
| `employee_qualifications` | Qualification/expiry relationships for candidate snapshots |
| `availability_windows` | Ordered declared availability intervals |
| `schedule_assignments` | Ordered supplied schedule relationships and assignees |
| `work_hours` | Explicit period, scheduled total and actual-worked total, stored as exact decimal text |
| `approvals` | Immutable attributed approval/denial ledger, retained even after candidate refresh/removal |
| `candidate_approvals` | Decisions included in an individual supplied candidate snapshot |
| `offers` | Durable inbox/status/deadline and original informational scoring result |
| `assigned_shifts` | One accepted coverage assignment per shift/recovery, linked to its winning offer |
| `recovery_events` | Ordered append-only historical events, original timestamps and messages |

Primary/foreign keys, partial unique indexes, status checks, inbox/schedule/approval
indexes and terminal-state triggers enforce relationships. Policies, configuration,
history, scores, immutable shift definitions and canonical source values use typed
JSON columns rather than custom byte serialization or ORM entity classes. Jackson's
time/Optional modules preserve existing records, nanos and decimal values. No
polymorphic class-name deserialization is enabled. Source-fact schedule definitions
are checked against stored shift identities during restoration. Per-recovery input
snapshots are historical attestations; `authoritative_sources` is the current
authority for employee/period facts, not a second live copy of each recovery.

Source snapshot child rows are replaced only when input/evaluation state actually
changes. This explicit refresh does not delete recovery events, identities, accepted
assignments or approval history. Normal startup never clears data. Immutable shift
definitions and approval IDs cannot silently acquire different meanings. An approval
referencing an unknown shift is rejected; no fake shift is invented to satisfy a FK.

## Acceptance, atomicity and database constraints

Acceptance loads latest data under the transaction's write reservation, then runs
the existing eligibility-before-scoring workflow. All locally accepted assignments
are merged before final eligibility, including other recoveries. The winning offer,
assigned shift, COVERED recovery (the existing FILLED equivalent), competing closure
and audit events commit together. Nothing reports ACCEPTED before COMMIT succeeds.

- A partial unique index allows one OPEN/ESCALATED recovery per shift.
- A partial unique index allows one ACCEPTED offer per recovery.
- Assignment primary/unique keys prevent a second replacement for the shift/recovery.
- Deferred composite foreign keys link an accepted offer to its assignment, and the
  assignment to the matching COVERED recovery and replacement employee. A partial
  accepted-only state cannot commit without the other records.
- Triggers prohibit terminal offer/recovery reopening and a terminal recovery with
  pending competitors. Approval decisions and events are immutable/append-only.

The transaction rolls back on SQL/constraint/serialization/restoration errors. The
temporary working service is discarded, so rollback cannot leave a winning in-memory
assignment behind. `PersistenceException` means no success was acknowledged; callers
must show a storage failure, not a successful claim. If an OS/driver fault occurs
at COMMIT or connection cleanup, reload by the existing recovery/offer ID before
retrying: the acknowledgement alone cannot prove whether a commit reached disk.
Tests inject failures during offer
creation and assignment insertion and verify the original state after reopening.

## Restarts and data authority

Every read reconstructs existing models from rows and validates workflow consistency.
No created/replied event is fabricated while reconstructing mutable ShiftOffer state.
Absolute deadlines, existing status, accepted assignments, exact timestamp nanos,
informational scores, approval history, source verification cutoffs and ID sequences
survive restart. An expired pending offer is expired before it is listed or accepted.
Cancelled/completed recoveries stay terminal. Missing required rows, invalid typed
values, broken foreign keys, missing candidate evaluations or missing canonical
sources fail closed instead of silently rebuilding a fresh empty workflow.

Latest employee/period source facts are retained even after removal from a candidate
list, so forgetting a candidate cannot resurrect an old active-employment snapshot.
Newer verified cutoffs take precedence over older supplied data; equal cutoffs use
the latest trusted manager receipt revision. A receipt revision is **not** a claim
that actual hours were newly verified. Missing/future verification data remains
invalid to eligibility and can be corrected by a genuine subsequent refresh.

The durable approval ledger is merged during evaluation, so omitting a newer denial
from a later request cannot fall back to an older approval. Existing policy authority,
scope, expiry, amount and snapshot-revalidation rules still apply. No overtime is
automatically approved. No employee profile or historical hours are manufactured.

The database can only know what trusted callers supplied. It does not fetch live
employment/schedules, verify timekeeping completeness, authenticate identity or
infer external changes. Existing policy/shift definitions are fixed per recovery;
policy changes still require cancellation/recreation. Actual worked hours and paid
break rules still belong to external source providers.

## Tests, safety and limits

JUnit tests use isolated temporary files and exercise initialization/reopening,
relational round trips, restoration and acceptance, competing closure, decline,
expiry, cancellation, conditional/approved overtime, audit timestamps, duplicate
protection, immutable histories, corruption/schema rejection, rollback injection,
fresh-data authority and races between independent service connections. All previous
Batch 1-4 tests continue to run unchanged.

Run with the existing compatible JDK:

```text
mvn -B clean compile
mvn -B test
```

The JDBC jar bundles the Windows SQLite native library; no SQLite server/install is
needed. JDK 27 reports a native-access warning on loading it; tests currently pass.
Dependencies are pinned; Java release 23 and JavaFX versions remain unchanged.

This is local demo storage, not production multi-company/multi-device infrastructure.
Tests cover separate connections/service instances on one local file, not remote
devices or network filesystem locking. Large datasets would need incremental reads/
writes, retention, pagination and performance measurements: the simple unit of work
currently restores/validates the complete workflow state. There is no encryption,
backup/restore UI, online migration manager, account authentication, external sync,
background expiry or closed-app notification delivery. OS file permissions matter.

Batch 6 can use the existing create/get/reevaluate/cancel and pending-offer/accept/
decline APIs in durable mode. Run synchronous database work off the JavaFX application
thread, authenticate actor IDs in adapters, map immutable views to presentation data,
format instants in company location zones, and show both business results and storage
failures accessibly. Select an explicit database path and manager configuration;
any demo seeding should be a separate opt-in action that refuses to overwrite data.
