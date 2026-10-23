package com.shiftrepair.persistence;

import com.shiftrepair.model.Availability;
import com.shiftrepair.model.Employee;
import com.shiftrepair.model.EmployeeQualification;
import com.shiftrepair.model.OvertimeApproval;
import com.shiftrepair.model.Qualification;
import com.shiftrepair.model.RecoveryRequest;
import com.shiftrepair.model.Shift;
import com.shiftrepair.model.ShiftOffer;
import com.shiftrepair.model.TimeWindow;
import com.shiftrepair.model.WorkHourSummary;
import com.shiftrepair.service.EligibilityPolicy;
import com.shiftrepair.service.EligibilityRequest;
import com.shiftrepair.service.RecoveryConfig;
import com.shiftrepair.service.RecoveryView;
import com.shiftrepair.service.ScoringHistory;
import com.shiftrepair.service.ScoringRequest;
import com.shiftrepair.service.ScoringResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/** JDBC unit of work. A database write reservation precedes every load/evaluate/save cycle. */
public final class SqliteRecoveryStore implements RecoveryStore {
    private static final int VERSION = 1;
    private static final int APPLICATION_ID = 1397900849;
    private final Path path;
    private final JsonCodec json = new JsonCodec();

    public SqliteRecoveryStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            if (this.path.getParent() != null) Files.createDirectories(this.path.getParent());
            try (var connection = connect()) {
                execute(connection, "BEGIN IMMEDIATE");
                try {
                    int version = pragma(connection, "PRAGMA user_version");
                    if (version == 0 && rows(connection, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").isEmpty()) {
                        try (var stream = SqliteRecoveryStore.class.getResourceAsStream("/com/shiftrepair/persistence/schema-v1.sql")) {
                            if (stream == null) throw new PersistenceException("Missing schema resource");
                            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                            for (String statement : schema.split(";\\s*(?:\\r?\\n|$)")) {
                                if (!statement.isBlank()) execute(connection, statement);
                            }
                        }
                        execute(connection, "INSERT INTO metadata VALUES(1,?,0)", Instant.MIN.toString());
                        execute(connection, "PRAGMA user_version=1");
                        execute(connection, "PRAGMA application_id=1397900849");
                    }
                    compatibility(connection);
                    execute(connection, "COMMIT");
                } catch (Exception error) {
                    rollback(connection, error);
                    throw error;
                }
            }
        } catch (Exception error) { throw failure("Cannot initialize SQLite database", error); }
    }

    /** Suggested Windows location. Resolving this path does not create a database or seed data. */
    public static Path defaultDatabasePath() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) throw new PersistenceException("LOCALAPPDATA is unavailable; supply an explicit database path");
        return Path.of(local, "ShiftRepair", "shiftrepair.db");
    }

    @Override public <T> T transact(Function<RecoveryState, Change<T>> operation) {
        try (var connection = connect()) {
            execute(connection, "BEGIN IMMEDIATE");
            try {
                compatibility(connection);
                integrity(connection);
                var previous = load(connection);
                var change = Objects.requireNonNull(operation.apply(previous), "change");
                save(connection, previous, change.state());
                integrity(connection);
                execute(connection, "COMMIT");
                return change.result();
            } catch (Exception error) {
                rollback(connection, error);
                throw error;
            }
        } catch (Exception error) { throw failure("SQLite recovery operation failed; reload authoritative state before retrying", error); }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            execute(connection, "PRAGMA foreign_keys=ON");
            execute(connection, "PRAGMA busy_timeout=5000");
            execute(connection, "PRAGMA synchronous=FULL");
            return connection;
        } catch (Exception error) { connection.close(); throw error; }
    }

    private void compatibility(Connection connection) throws SQLException {
        if (pragma(connection, "PRAGMA user_version") != VERSION || pragma(connection, "PRAGMA application_id") != APPLICATION_ID) {
            throw new PersistenceException("Unsupported or unrecognized database schema; database was not reset");
        }
    }

    private void integrity(Connection connection) throws SQLException {
        if (!rows(connection, "PRAGMA foreign_key_check").isEmpty()) throw new PersistenceException("Database contains broken foreign-key relationships");
    }

    private RecoveryState load(Connection c) throws SQLException {
        var metadata = one(c, "SELECT * FROM metadata WHERE id=1");
        var saved = new ArrayList<RecoveryState.SavedRecovery>();
        for (var row : rows(c, "SELECT * FROM recoveries ORDER BY requested_at,id")) {
            String id = row.get("id");
            var shift = shift(c, row.get("shift_id"), null);
            var recovery = new RecoveryRequest(id, shift, row.get("manager_id"), instant(row.get("requested_at")),
                    RecoveryRequest.Status.valueOf(row.get("status")), Optional.ofNullable(row.get("replacement_id")));
            var policy = json.read(row.get("policy"), EligibilityPolicy.class);
            var config = json.read(row.get("config"), RecoveryConfig.class);
            var inputs = new ArrayList<ScoringRequest>();
            var scoresByOrder = new TreeMap<Integer, ScoringResult>();
            for (var source : rows(c, "SELECT * FROM candidate_sources WHERE recovery_id=? ORDER BY ordinal", id)) {
                String employeeId = source.get("employee_id");
                var qualifications = new HashSet<EmployeeQualification>();
                for (var q : rows(c, "SELECT * FROM employee_qualifications WHERE recovery_id=? AND employee_id=?", id, employeeId)) {
                    qualifications.add(new EmployeeQualification(new Qualification(Qualification.Type.valueOf(q.get("type")), q.get("code")),
                            q.get("expires_at").isEmpty() ? Optional.empty() : Optional.of(instant(q.get("expires_at")))));
                }
                var availability = new ArrayList<TimeWindow>();
                for (var window : rows(c, "SELECT * FROM availability_windows WHERE recovery_id=? AND employee_id=? ORDER BY ordinal", id, employeeId)) {
                    availability.add(new TimeWindow(instant(window.get("starts_at")), instant(window.get("ends_at"))));
                }
                var employee = new Employee(employeeId, source.get("name"), Employee.EmploymentStatus.valueOf(source.get("employment_status")),
                        qualifications, new Availability(availability));
                List<Shift> schedule = null;
                if (source.get("schedule_known").equals("1")) {
                    schedule = new ArrayList<>();
                    for (var assignment : rows(c, "SELECT * FROM schedule_assignments WHERE recovery_id=? AND employee_id=? ORDER BY ordinal", id, employeeId)) {
                        schedule.add(shift(c, assignment.get("shift_id"), assignment.get("assignee_id")));
                    }
                }
                WorkHourSummary hours = null;
                var hourRows = rows(c, "SELECT * FROM work_hours WHERE recovery_id=? AND employee_id=?", id, employeeId);
                if (!hourRows.isEmpty()) {
                    var h = hourRows.getFirst();
                    hours = new WorkHourSummary(h.get("owner_id"), new TimeWindow(instant(h.get("period_start")), instant(h.get("period_end"))),
                            new BigDecimal(h.get("scheduled")), new BigDecimal(h.get("actual")));
                }
                List<OvertimeApproval> approvals = null;
                if (source.get("approvals_known").equals("1")) {
                    approvals = new ArrayList<>();
                    for (var a : rows(c, "SELECT a.* FROM candidate_approvals ca JOIN approvals a ON a.id=ca.approval_id WHERE ca.recovery_id=? AND ca.employee_id=? ORDER BY ca.ordinal", id, employeeId)) {
                        approvals.add(approval(a));
                    }
                }
                var input = new EligibilityRequest(employee, shift, schedule, hours, policy, approvals,
                        source.get("verified_through") == null ? null : instant(source.get("verified_through")), instant(source.get("evaluated_at")));
                inputs.add(new ScoringRequest(input, Optional.ofNullable(source.get("history")).map(value -> json.read(value, ScoringHistory.class))));
                if (source.get("score") != null) {
                    var score = json.read(source.get("score"), ScoringResult.class);
                    if (!score.employeeId().equals(employeeId)) throw new PersistenceException("Stored candidate score identity mismatch");
                    if (scoresByOrder.put(Integer.parseInt(source.get("score_ordinal")), score) != null) throw new PersistenceException("Duplicate candidate score order");
                }
            }
            var offers = new ArrayList<RecoveryView.Offer>();
            for (var offer : rows(c, "SELECT * FROM offers WHERE recovery_id=? ORDER BY CAST(substr(id,7) AS INTEGER)", id)) {
                offers.add(new RecoveryView.Offer(offer.get("id"), id, offer.get("employee_id"), shift,
                        ShiftOffer.Status.valueOf(offer.get("status")), instant(offer.get("created_at")), instant(offer.get("expires_at")),
                        json.read(offer.get("score"), ScoringResult.class)));
            }
            var events = new ArrayList<RecoveryView.Event>();
            for (var event : rows(c, "SELECT * FROM recovery_events WHERE recovery_id=? ORDER BY ordinal", id)) {
                if (Integer.parseInt(event.get("ordinal")) != events.size()) throw new PersistenceException("Recovery event history has a gap");
                events.add(new RecoveryView.Event(RecoveryView.EventType.valueOf(event.get("type")), instant(event.get("occurred_at")),
                        Optional.ofNullable(event.get("employee_id")), Optional.ofNullable(event.get("offer_id")), event.get("message")));
            }
            saved.add(new RecoveryState.SavedRecovery(recovery, policy, config, inputs, List.copyOf(scoresByOrder.values()), offers, events,Long.parseLong(row.get("source_revision"))));
        }
        var assignments = new ArrayList<Shift>();
        for (var row : rows(c, "SELECT * FROM assigned_shifts ORDER BY shift_id")) assignments.add(shift(c, row.get("shift_id"), row.get("employee_id")));
        var approvals=new ArrayList<OvertimeApproval>();
        for(var row:rows(c,"SELECT * FROM approvals ORDER BY decided_at,id")) approvals.add(approval(row));
        var sources=new ArrayList<RecoveryState.SourceFacts>();
        for(var row:rows(c,"SELECT * FROM authoritative_sources ORDER BY employee_id,period_start,period_end")) {
            var facts=json.read(row.get("facts"),RecoveryState.SourceFacts.class);
            if(!facts.employee().id().equals(row.get("employee_id")) || !facts.period().start().toString().equals(row.get("period_start"))
                    || !facts.period().end().toString().equals(row.get("period_end"))) throw new PersistenceException("Authoritative source identity mismatch");
            if(facts.assignments()!=null) for(var assigned:facts.assignments()) {
                if(!shift(c,assigned.id(),assigned.assignedEmployeeId().orElse(null)).equals(assigned)) throw new PersistenceException("Authoritative schedule definition mismatch");
            }
            sources.add(facts);
        }
        return new RecoveryState(saved, assignments, approvals, sources, instant(metadata.get("last_observed")), Long.parseLong(metadata.get("next_offer_id")));
    }

    private void save(Connection c, RecoveryState before, RecoveryState after) throws SQLException {
        var previous = new HashMap<String, RecoveryState.SavedRecovery>();
        before.recoveries().forEach(value -> previous.put(value.recovery().id(), value));
        if (!after.recoveries().stream().map(value -> value.recovery().id()).toList().containsAll(previous.keySet())
                || !after.assignments().containsAll(before.assignments()) || !after.approvals().containsAll(before.approvals())) throw new PersistenceException("Workflow history, decisions or assignments cannot be removed");
        for(var old:before.sources()) {
            if(after.sources().stream().noneMatch(value -> value.employee().id().equals(old.employee().id()) && value.period().equals(old.period()))) throw new PersistenceException("Authoritative source cannot be removed");
        }
        // Register all identities/definitions first. Candidate facts remain explicitly scoped, verified snapshots.
        for (var saved : after.recoveries()) {
            putShift(c, saved.recovery().shift());
            for (var source : saved.inputs()) {
                var input = source.eligibility();
                identity(c, input.employee().id());
                if (input.assignments() != null) for (var shift : input.assignments()) { putShift(c, shift); shift.assignedEmployeeId().ifPresent(id -> identity(c, id)); }
                if (input.workHours() != null) identity(c, input.workHours().employeeId());
                if (input.approvals() != null) for (var approval : input.approvals()) {
                    identity(c, approval.employeeId());
                    // An irrelevant approval must still refer to a known shift, not an invented placeholder.
                    if (rows(c, "SELECT id FROM shifts WHERE id=?", approval.shiftId()).isEmpty()) throw new PersistenceException("Approval references an unknown shift: " + approval.shiftId());
                    putApproval(c, approval);
                }
            }
            for (var offer : saved.offers()) identity(c, offer.employeeId());
            var r = saved.recovery();
            execute(c, "INSERT OR IGNORE INTO recoveries(id,shift_id,manager_id,requested_at,status,replacement_id,policy,config,source_revision) VALUES(?,?,?,?,?,?,?,?,?)",
                    r.id(), r.shift().id(), r.requestedById(), r.requestedAt(), r.status(), r.replacementEmployeeId().orElse(null), json.write(saved.policy()), json.write(saved.config()),saved.sourceRevision());
        }
        for(var approval:after.approvals()) { identity(c,approval.employeeId()); putApproval(c,approval); }
        for(var facts:after.sources()) {
            identity(c,facts.employee().id());
            execute(c,"INSERT INTO authoritative_sources VALUES(?,?,?,?) ON CONFLICT(employee_id,period_start,period_end) DO UPDATE SET facts=excluded.facts",
                    facts.employee().id(),facts.period().start(),facts.period().end(),json.write(facts));
        }
        // Offers close before the terminal recovery transition. Deferred FKs tie winner, assignment and recovery together at commit.
        for (var saved : after.recoveries()) for (var offer : saved.offers()) {
            var oldRecovery = previous.get(saved.recovery().id());
            var old = oldRecovery == null ? Optional.<RecoveryView.Offer>empty() : oldRecovery.offers().stream().filter(value -> value.id().equals(offer.id())).findFirst();
            if (old.isEmpty()) {
                execute(c, "INSERT INTO offers(id,recovery_id,employee_id,status,created_at,expires_at,score) VALUES(?,?,?,?,?,?,?)",
                        offer.id(), offer.recoveryId(), offer.employeeId(), offer.status(), offer.createdAt(), offer.expiresAt(), json.write(offer.scoring()));
            } else if (!old.get().equals(offer)) {
                var prior=old.get();
                if (!prior.employeeId().equals(offer.employeeId()) || !prior.shift().equals(offer.shift())
                        || !prior.scoring().equals(offer.scoring()) || !prior.createdAt().equals(offer.createdAt()) || !prior.expiresAt().equals(offer.expiresAt())) {
                    throw new PersistenceException("Immutable offer facts changed");
                }
                execute(c,"UPDATE offers SET status=?,claim_recovery_id=? WHERE id=?",offer.status(),
                        offer.status()==ShiftOffer.Status.ACCEPTED ? offer.recoveryId() : null,offer.id());
            }
        }
        for (var shift : after.assignments()) {
            var owner = after.recoveries().stream().filter(value -> value.recovery().shift().id().equals(shift.id())
                    && value.recovery().status() == RecoveryRequest.Status.COVERED).findFirst().orElseThrow();
            var winner = owner.offers().stream().filter(value -> value.status() == ShiftOffer.Status.ACCEPTED).findFirst().orElseThrow();
            execute(c, "INSERT OR IGNORE INTO assigned_shifts(shift_id,recovery_id,employee_id,winning_offer_id) VALUES(?,?,?,?)",
                    shift.id(), owner.recovery().id(), shift.assignedEmployeeId().orElseThrow(), winner.id());
        }
        for (var saved : after.recoveries()) {
            var old = previous.get(saved.recovery().id());
            var r = saved.recovery();
            execute(c, "UPDATE recoveries SET status=?,replacement_id=?,source_revision=? WHERE id=?", r.status(), r.replacementEmployeeId().orElse(null),saved.sourceRevision(), r.id());
            if (old == null || !old.inputs().equals(saved.inputs()) || !old.candidates().equals(saved.candidates())) saveSources(c, saved);
            if (old != null && (saved.events().size() < old.events().size()
                    || !saved.events().subList(0, old.events().size()).equals(old.events()))) throw new PersistenceException("Recovery events cannot be rewritten");
            int first = old == null ? 0 : old.events().size();
            for (int i = first; i < saved.events().size(); i++) {
                var event = saved.events().get(i);
                execute(c, "INSERT INTO recovery_events VALUES(?,?,?,?,?,?,?)", r.id(), i, event.type(), event.at(),
                        event.employeeId().orElse(null), event.offerId().orElse(null), event.message());
            }
        }
        execute(c, "UPDATE metadata SET last_observed=?,next_offer_id=? WHERE id=1", after.lastObservedTime(), after.nextOfferId());
    }

    private void saveSources(Connection c, RecoveryState.SavedRecovery saved) throws SQLException {
        String recoveryId = saved.recovery().id();
        execute(c, "DELETE FROM candidate_sources WHERE recovery_id=?", recoveryId);
        var scores = new HashMap<String, ScoringResult>();
        var order = new HashMap<String, Integer>();
        for (int i = 0; i < saved.candidates().size(); i++) { var score = saved.candidates().get(i); scores.put(score.employeeId(), score); order.put(score.employeeId(), i); }
        for (int i = 0; i < saved.inputs().size(); i++) {
            var source = saved.inputs().get(i);
            var input = source.eligibility();
            var employee = input.employee();
            String id = employee.id();
            var score = scores.get(id);
            execute(c, "INSERT INTO candidate_sources VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", recoveryId, id, i, employee.name(), employee.status(),
                    input.verifiedThrough(), input.evaluatedAt(), input.assignments() == null ? 0 : 1, input.approvals() == null ? 0 : 1,
                    source.history().map(json::write).orElse(null), score == null ? null : json.write(score), order.get(id));
            for (var qualification : employee.qualifications()) {
                var q = qualification.qualification();
                execute(c, "INSERT OR IGNORE INTO qualifications VALUES(?,?)", q.type(), q.code());
                execute(c, "INSERT INTO employee_qualifications VALUES(?,?,?,?,?)", recoveryId, id, q.type(), q.code(), qualification.expiresAt().map(Instant::toString).orElse(""));
            }
            for (int j = 0; j < employee.availability().windows().size(); j++) {
                var window = employee.availability().windows().get(j);
                execute(c, "INSERT INTO availability_windows VALUES(?,?,?,?,?)", recoveryId, id, j, window.start(), window.end());
            }
            if (input.assignments() != null) for (int j = 0; j < input.assignments().size(); j++) {
                var shift = input.assignments().get(j);
                execute(c, "INSERT INTO schedule_assignments VALUES(?,?,?,?,?)", recoveryId, id, j, shift.id(), shift.assignedEmployeeId().orElse(null));
            }
            if (input.workHours() != null) {
                var hours = input.workHours();
                execute(c, "INSERT INTO work_hours VALUES(?,?,?,?,?,?,?)", recoveryId, id, hours.employeeId(), hours.period().start(), hours.period().end(), hours.scheduledHours(), hours.actualWorkedHours());
            }
            if (input.approvals() != null) for (int j = 0; j < input.approvals().size(); j++) {
                execute(c, "INSERT INTO candidate_approvals VALUES(?,?,?,?)", recoveryId, id, j, input.approvals().get(j).id());
            }
        }
    }

    private void putApproval(Connection c, OvertimeApproval a) throws SQLException {
        var existing = rows(c, "SELECT * FROM approvals WHERE id=?", a.id());
        if (!existing.isEmpty()) {
            if (!approval(existing.getFirst()).equals(a)) throw new PersistenceException("Conflicting immutable approval ID: " + a.id());
            return;
        }
        execute(c, "INSERT INTO approvals VALUES(?,?,?,?,?,?,?,?,?,?)", a.id(), a.employeeId(), a.shiftId(), a.period().start(), a.period().end(),
                a.overtimeHours(), a.decision(), a.managerId(), a.decidedAt(), a.reason());
    }

    private OvertimeApproval approval(Map<String,String> row) {
        return new OvertimeApproval(row.get("id"), row.get("employee_id"), row.get("shift_id"),
                new TimeWindow(instant(row.get("period_start")), instant(row.get("period_end"))), new BigDecimal(row.get("hours")),
                OvertimeApproval.Decision.valueOf(row.get("decision")), row.get("manager_id"), instant(row.get("decided_at")), row.get("reason"));
    }

    private void identity(Connection c, String id) {
        try { execute(c, "INSERT OR IGNORE INTO employees VALUES(?)", id); }
        catch (SQLException error) { throw failure("Cannot store employee identity", error); }
    }

    private void putShift(Connection c, Shift shift) throws SQLException {
        var definition = new Shift(shift.id(), shift.locationId(), shift.window(), shift.requiredQualifications(), Optional.empty());
        var existing = rows(c, "SELECT definition FROM shifts WHERE id=?", shift.id());
        if (!existing.isEmpty()) {
            if (!json.read(existing.getFirst().get("definition"), Shift.class).equals(definition)) throw new PersistenceException("Conflicting immutable shift ID: " + shift.id());
        } else execute(c, "INSERT INTO shifts VALUES(?,?)", shift.id(), json.write(definition));
    }

    private Shift shift(Connection c, String id, String assignee) throws SQLException {
        var value = json.read(one(c, "SELECT definition FROM shifts WHERE id=?", id).get("definition"), Shift.class);
        if (!value.id().equals(id) || value.assignedEmployeeId().isPresent()) throw new PersistenceException("Invalid stored shift identity/definition");
        return new Shift(id, value.locationId(), value.window(), value.requiredQualifications(), Optional.ofNullable(assignee));
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }

    private static int pragma(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new PersistenceException("Missing PRAGMA result");
            return result.getInt(1);
        }
    }

    private static Map<String,String> one(Connection c, String sql, Object... params) throws SQLException {
        var values = rows(c, sql, params);
        if (values.size() != 1) throw new PersistenceException("Required database row missing or duplicated");
        return values.getFirst();
    }

    private static List<Map<String,String>> rows(Connection c, String sql, Object... params) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            bind(statement, params);
            try (var result = statement.executeQuery()) {
                var values = new ArrayList<Map<String,String>>();
                while (result.next()) {
                    var row = new HashMap<String,String>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        row.put(result.getMetaData().getColumnLabel(i), result.getString(i));
                    }
                    values.add(row);
                }
                return values;
            }
        }
    }

    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            bind(statement, params);
            statement.execute();
        }
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) statement.setNull(i + 1, Types.VARCHAR);
            else statement.setString(i + 1, params[i].toString());
        }
    }

    private static void rollback(Connection c, Exception original) {
        try { execute(c, "ROLLBACK"); }
        catch (SQLException error) { original.addSuppressed(error); }
    }

    private static PersistenceException failure(String message, Exception error) {
        return error instanceof PersistenceException p ? p : new PersistenceException(message, error);
    }
}
