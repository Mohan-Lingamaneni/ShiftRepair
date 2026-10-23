CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), last_observed TEXT NOT NULL, next_offer_id INTEGER NOT NULL CHECK(next_offer_id>=0));
CREATE TABLE employees (id TEXT PRIMARY KEY CHECK(length(trim(id))>0));
CREATE TABLE shifts (id TEXT PRIMARY KEY CHECK(length(trim(id))>0), definition TEXT NOT NULL CHECK(json_valid(definition)));
CREATE TABLE qualifications (type TEXT NOT NULL CHECK(type IN ('ROLE','SKILL','CERTIFICATION')), code TEXT NOT NULL CHECK(length(trim(code))>0), PRIMARY KEY(type,code));
CREATE TABLE authoritative_sources (employee_id TEXT NOT NULL REFERENCES employees(id), period_start TEXT NOT NULL, period_end TEXT NOT NULL, facts TEXT NOT NULL CHECK(json_valid(facts)), PRIMARY KEY(employee_id,period_start,period_end));
CREATE TABLE recoveries (
 id TEXT PRIMARY KEY, shift_id TEXT NOT NULL REFERENCES shifts(id), manager_id TEXT NOT NULL, requested_at TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('OPEN','ESCALATED','COVERED','CANCELLED')), replacement_id TEXT REFERENCES employees(id),
 policy TEXT NOT NULL CHECK(json_valid(policy)), config TEXT NOT NULL CHECK(json_valid(config)), source_revision INTEGER NOT NULL CHECK(source_revision>0),
 CHECK((status='COVERED')=(replacement_id IS NOT NULL)), UNIQUE(id,shift_id,replacement_id,status),
 FOREIGN KEY(id,shift_id,replacement_id) REFERENCES assigned_shifts(recovery_id,shift_id,employee_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX one_active_recovery ON recoveries(shift_id) WHERE status IN ('OPEN','ESCALATED');
CREATE UNIQUE INDEX source_revision_unique ON recoveries(source_revision);
CREATE TABLE candidate_sources (
 recovery_id TEXT NOT NULL REFERENCES recoveries(id), employee_id TEXT NOT NULL REFERENCES employees(id), ordinal INTEGER NOT NULL,
 name TEXT NOT NULL, employment_status TEXT NOT NULL CHECK(employment_status IN ('ACTIVE','INACTIVE')),
 verified_through TEXT, evaluated_at TEXT NOT NULL, schedule_known INTEGER NOT NULL CHECK(schedule_known IN (0,1)),
 approvals_known INTEGER NOT NULL CHECK(approvals_known IN (0,1)), history TEXT CHECK(history IS NULL OR json_valid(history)),
 score TEXT CHECK(score IS NULL OR json_valid(score)), score_ordinal INTEGER,
 PRIMARY KEY(recovery_id,employee_id), UNIQUE(recovery_id,ordinal)
);
CREATE TABLE employee_qualifications (
 recovery_id TEXT NOT NULL, employee_id TEXT NOT NULL, type TEXT NOT NULL, code TEXT NOT NULL, expires_at TEXT NOT NULL,
 PRIMARY KEY(recovery_id,employee_id,type,code,expires_at),
 FOREIGN KEY(recovery_id,employee_id) REFERENCES candidate_sources(recovery_id,employee_id) ON DELETE CASCADE,
 FOREIGN KEY(type,code) REFERENCES qualifications(type,code)
);
CREATE TABLE availability_windows (
 recovery_id TEXT NOT NULL, employee_id TEXT NOT NULL, ordinal INTEGER NOT NULL, starts_at TEXT NOT NULL, ends_at TEXT NOT NULL,
 PRIMARY KEY(recovery_id,employee_id,ordinal),
 FOREIGN KEY(recovery_id,employee_id) REFERENCES candidate_sources(recovery_id,employee_id) ON DELETE CASCADE
);
CREATE TABLE schedule_assignments (
 recovery_id TEXT NOT NULL, employee_id TEXT NOT NULL, ordinal INTEGER NOT NULL, shift_id TEXT NOT NULL REFERENCES shifts(id), assignee_id TEXT REFERENCES employees(id),
 PRIMARY KEY(recovery_id,employee_id,ordinal),
 FOREIGN KEY(recovery_id,employee_id) REFERENCES candidate_sources(recovery_id,employee_id) ON DELETE CASCADE
);
CREATE INDEX schedule_by_employee ON schedule_assignments(employee_id,shift_id);
CREATE TABLE work_hours (
 recovery_id TEXT NOT NULL, employee_id TEXT NOT NULL, owner_id TEXT NOT NULL REFERENCES employees(id), period_start TEXT NOT NULL, period_end TEXT NOT NULL,
 scheduled TEXT NOT NULL, actual TEXT NOT NULL, PRIMARY KEY(recovery_id,employee_id),
 FOREIGN KEY(recovery_id,employee_id) REFERENCES candidate_sources(recovery_id,employee_id) ON DELETE CASCADE
);
CREATE TABLE approvals (
 id TEXT PRIMARY KEY, employee_id TEXT NOT NULL REFERENCES employees(id), shift_id TEXT NOT NULL REFERENCES shifts(id),
 period_start TEXT NOT NULL, period_end TEXT NOT NULL, hours TEXT NOT NULL, decision TEXT NOT NULL CHECK(decision IN ('APPROVED','DENIED')),
 manager_id TEXT NOT NULL, decided_at TEXT NOT NULL, reason TEXT NOT NULL
);
CREATE INDEX approval_scope ON approvals(employee_id,shift_id,period_start,period_end);
CREATE TABLE candidate_approvals (
 recovery_id TEXT NOT NULL, employee_id TEXT NOT NULL, ordinal INTEGER NOT NULL, approval_id TEXT NOT NULL REFERENCES approvals(id),
 PRIMARY KEY(recovery_id,employee_id,ordinal),
 FOREIGN KEY(recovery_id,employee_id) REFERENCES candidate_sources(recovery_id,employee_id) ON DELETE CASCADE
);
CREATE TABLE offers (
 id TEXT PRIMARY KEY, recovery_id TEXT NOT NULL REFERENCES recoveries(id), employee_id TEXT NOT NULL REFERENCES employees(id),
 status TEXT NOT NULL CHECK(status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','CLOSED')), created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
 score TEXT NOT NULL CHECK(json_valid(score) AND json_extract(score,'$.eligibility.status')='ELIGIBLE'), claim_recovery_id TEXT,
 CHECK((status='ACCEPTED')=(claim_recovery_id IS NOT NULL)), CHECK(claim_recovery_id IS NULL OR claim_recovery_id=recovery_id),
 UNIQUE(recovery_id,employee_id), UNIQUE(id,recovery_id,employee_id,status),
 FOREIGN KEY(claim_recovery_id,employee_id) REFERENCES assigned_shifts(recovery_id,employee_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX one_accepted_offer ON offers(recovery_id) WHERE status='ACCEPTED';
CREATE INDEX employee_inbox ON offers(employee_id,status,expires_at);
CREATE TABLE assigned_shifts (
 shift_id TEXT PRIMARY KEY REFERENCES shifts(id), recovery_id TEXT NOT NULL UNIQUE REFERENCES recoveries(id),
 employee_id TEXT NOT NULL REFERENCES employees(id), winning_offer_id TEXT NOT NULL UNIQUE,
 winning_status TEXT NOT NULL DEFAULT 'ACCEPTED' CHECK(winning_status='ACCEPTED'), recovery_status TEXT NOT NULL DEFAULT 'COVERED' CHECK(recovery_status='COVERED'),
 UNIQUE(recovery_id,shift_id,employee_id), UNIQUE(recovery_id,employee_id),
 FOREIGN KEY(winning_offer_id,recovery_id,employee_id,winning_status) REFERENCES offers(id,recovery_id,employee_id,status) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(recovery_id,shift_id,employee_id,recovery_status) REFERENCES recoveries(id,shift_id,replacement_id,status) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE recovery_events (
 recovery_id TEXT NOT NULL REFERENCES recoveries(id), ordinal INTEGER NOT NULL, type TEXT NOT NULL, occurred_at TEXT NOT NULL,
 employee_id TEXT REFERENCES employees(id), offer_id TEXT REFERENCES offers(id), message TEXT NOT NULL, PRIMARY KEY(recovery_id,ordinal)
);
CREATE TRIGGER terminal_recovery BEFORE UPDATE OF status ON recoveries WHEN OLD.status IN ('COVERED','CANCELLED') AND NEW.status<>OLD.status BEGIN SELECT RAISE(ABORT,'Terminal recovery cannot reopen'); END;
CREATE TRIGGER terminal_offer BEFORE UPDATE OF status ON offers WHEN OLD.status<>'PENDING' AND NEW.status<>OLD.status BEGIN SELECT RAISE(ABORT,'Terminal offer cannot reopen'); END;
CREATE TRIGGER completed_recovery_no_pending BEFORE UPDATE OF status ON recoveries WHEN NEW.status IN ('COVERED','CANCELLED') AND EXISTS(SELECT 1 FROM offers WHERE recovery_id=NEW.id AND status='PENDING') BEGIN SELECT RAISE(ABORT,'Terminal recovery has pending offers'); END;
CREATE TRIGGER no_offer_for_terminal BEFORE INSERT ON offers WHEN (SELECT status FROM recoveries WHERE id=NEW.recovery_id) IN ('COVERED','CANCELLED') BEGIN SELECT RAISE(ABORT,'Cannot offer a terminal recovery'); END;
CREATE TRIGGER immutable_approval BEFORE UPDATE ON approvals BEGIN SELECT RAISE(ABORT,'Approval decisions are immutable'); END;
CREATE TRIGGER preserve_approval BEFORE DELETE ON approvals BEGIN SELECT RAISE(ABORT,'Approval history cannot be deleted'); END;
CREATE TRIGGER immutable_event BEFORE UPDATE ON recovery_events BEGIN SELECT RAISE(ABORT,'Events are append only'); END;
CREATE TRIGGER preserve_event BEFORE DELETE ON recovery_events BEGIN SELECT RAISE(ABORT,'Events are append only'); END;
