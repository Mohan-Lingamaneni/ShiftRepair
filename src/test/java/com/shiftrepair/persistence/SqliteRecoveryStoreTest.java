package com.shiftrepair.persistence;

import com.shiftrepair.model.*;
import com.shiftrepair.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.shiftrepair.service.RecoveryService.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteRecoveryStoreTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00.123456789Z");
    private static final TimeWindow PERIOD = new TimeWindow(Instant.parse("2026-10-19T05:00:00Z"), Instant.parse("2026-10-26T05:00:00Z"));
    private static final Qualification ROLE = new Qualification(Qualification.Type.ROLE,"cashier");
    private MutableClock clock;
    private Path database;
    private RecoveryService service;
    private EligibilityPolicy policy;
    private RecoveryConfig config;
    private Shift target;

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone(){ return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone){ return this; }
        @Override public Instant instant(){ return now.get(); }
    }

    @BeforeEach void setup() {
        clock=new MutableClock(); database=directory.resolve("isolated.db");
        policy=new EligibilityPolicy(PERIOD,new OvertimePolicy(OvertimePolicy.Mode.PROHIBITED,new BigDecimal("40")),
                Duration.ofMinutes(15),Duration.ofHours(1),Set.of("manager-1"),Map.of());
        config=new RecoveryConfig(Duration.ofMinutes(30),ScoringConfig.provisionalExample());
        target=new Shift("shift-1","store-1",new TimeWindow(Instant.parse("2026-10-23T21:00:00Z"),Instant.parse("2026-10-24T03:00:00Z")),Set.of(ROLE),Optional.empty());
        service=reopen();
    }
    private RecoveryService reopen(){ return RecoveryService.open(database,clock,Set.of("manager-1")); }
    private ScoringRequest candidate(String id){ return candidate(id,target,"18"); }
    private ScoringRequest candidate(String id,Shift shift,String actual) {
        var employee=new Employee(id,"Synthetic employee "+id,Employee.EmploymentStatus.ACTIVE,
                Set.of(new EmployeeQualification(ROLE,Optional.of(PERIOD.end()))),new Availability(List.of(PERIOD)));
        return new ScoringRequest(new EligibilityRequest(employee,shift,List.of(),new WorkHourSummary(id,PERIOD,BigDecimal.ZERO,new BigDecimal(actual)),
                policy,List.of(),clock.instant(),clock.instant()),Optional.empty());
    }
    private RecoveryService.Result create(String id,List<ScoringRequest> candidates){ return service.create(id,"manager-1",target,policy,candidates,config); }
    private RecoveryView view(String id){ return service.get("manager-1",id).recovery().orElseThrow(); }
    private Connection connection() throws SQLException {
        var c=DriverManager.getConnection("jdbc:sqlite:"+database);
        try(var s=c.createStatement()){ s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private long count(String table) throws SQLException {
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){ r.next();return r.getLong(1); }
    }
    private void sql(String statement) throws SQLException { try(var c=connection();var s=c.createStatement()){ s.execute(statement); } }

    @Test void freshInitializationIsIdempotentAndDoesNotSeedDemoData() throws Exception {
        assertTrue(Files.exists(database));
        assertEquals(0,count("recoveries")); assertEquals(0,count("employees"));
        service=reopen(); service=reopen();
        assertEquals(0,count("recoveries")); assertEquals(1,count("metadata"));
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){ r.next();assertEquals(1,r.getInt(1)); }
    }

    @Test void createReloadAndAcceptAfterRestart() throws Exception {
        assertEquals(CREATED,create("r1",List.of(candidate("a"),candidate("b"))).code());
        var initial=view("r1");
        var id=service.pendingOffers("b").getFirst().id();
        service=reopen();
        assertEquals(initial,view("r1"));
        var accepted=service.accept("b",id);
        assertEquals(ACCEPTED,accepted.code());
        service=reopen();
        var filled=view("r1");
        assertEquals(RecoveryRequest.Status.COVERED,filled.recovery().status());
        assertEquals(1,service.assignmentsFor("b").size());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(ShiftOffer.Status.CLOSED,filled.offers().stream().filter(o->o.employeeId().equals("a")).findFirst().orElseThrow().status());
        assertEquals(ALREADY_FILLED,service.accept("b",id).code());
        assertEquals(1,count("assigned_shifts"));
    }

    @Test void employeeQualificationAvailabilityAndScheduleRelationshipsRoundTrip() throws Exception {
        var request=candidate("a"); var input=request.eligibility();
        var scheduled=new Shift("scheduled-1","other-store",new TimeWindow(NOW.minusSeconds(86400),NOW.minusSeconds(64800)),Set.of(ROLE),Optional.of("a"));
        request=new ScoringRequest(new EligibilityRequest(input.employee(),target,List.of(scheduled),
                new WorkHourSummary("a",PERIOD,new BigDecimal("6"),new BigDecimal("18.25")),policy,List.of(),NOW,NOW),Optional.empty());
        create("r1",List.of(request));
        service=reopen();
        assertEquals(1,service.pendingOffers("a").size());
        assertEquals(1,count("employee_qualifications")); assertEquals(1,count("availability_windows"));
        assertEquals(1,count("schedule_assignments")); assertEquals(1,count("work_hours")); assertEquals(2,count("shifts"));
        var state=new SqliteRecoveryStore(database).transact(value->new RecoveryStore.Change<>(value,value));
        assertEquals(request,state.recoveries().getFirst().inputs().getFirst());
    }

    @Test void declineExpirationAndEscalationSurviveRestart() {
        create("r1",List.of(candidate("a"),candidate("b")));
        var a=service.pendingOffers("a").getFirst(); var b=service.pendingOffers("b").getFirst();
        assertEquals(DECLINED,service.decline("a",a.id()).code());
        service=reopen(); assertTrue(service.pendingOffers("a").isEmpty());
        clock.now.set(b.expiresAt()); service=reopen();
        assertTrue(service.pendingOffers("b").isEmpty());
        assertEquals(OFFER_EXPIRED,service.accept("b",b.id()).code());
        service=reopen(); assertEquals(RecoveryRequest.Status.ESCALATED,view("r1").recovery().status());
        assertEquals(ShiftOffer.Status.DECLINED,view("r1").offers().stream().filter(o->o.employeeId().equals("a")).findFirst().orElseThrow().status());
    }

    @Test void cancelledRecoveryAndClosedOffersCannotReopenOnRestart() {
        create("r1",List.of(candidate("a"))); var id=service.pendingOffers("a").getFirst().id();
        service.cancel("manager-1","r1"); service=reopen();
        assertEquals(RECOVERY_CANCELLED,service.accept("a",id).code());
        assertEquals(RecoveryRequest.Status.CANCELLED,view("r1").recovery().status());
        assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(RECOVERY_CANCELLED,service.reevaluate("manager-1","r1",List.of(candidate("a"))).code());
    }

    private void approvalPolicy() {
        policy=new EligibilityPolicy(PERIOD,new OvertimePolicy(OvertimePolicy.Mode.MANAGER_APPROVAL_REQUIRED,new BigDecimal("40")),
                policy.maxSnapshotAge(),policy.approvalValidity(),policy.authorizedManagerIds(),Map.of());
    }
    private ScoringRequest approved(ScoringRequest input,String id,OvertimeApproval.Decision decision) {
        var e=input.eligibility();
        var approval=new OvertimeApproval(id,e.employee().id(),target.id(),PERIOD,new BigDecimal("10"),decision,"manager-1",clock.instant(),"Recorded manager decision");
        return new ScoringRequest(new EligibilityRequest(e.employee(),target,e.assignments(),e.workHours(),policy,List.of(approval),
                e.verifiedThrough(),e.evaluatedAt()),Optional.empty());
    }

    @Test void conditionalApprovalsAndManagerReevaluationSurviveRestart() throws Exception {
        approvalPolicy(); create("r1",List.of(candidate("a",target,"38")));
        service=reopen(); assertTrue(service.pendingOffers("a").isEmpty());
        assertEquals(EligibilityResult.Status.REQUIRES_APPROVAL,view("r1").candidates().getFirst().eligibility().status());
        clock.now.set(NOW.plusSeconds(1));
        service.reevaluate("manager-1","r1",List.of(approved(candidate("a",target,"38"),"approval-1",OvertimeApproval.Decision.APPROVED)));
        service=reopen(); assertEquals(1,count("approvals"));
        assertEquals(ACCEPTED,service.accept("a",service.pendingOffers("a").getFirst().id()).code());
        assertEquals(1,count("assigned_shifts"));
    }

    @Test void approvalHistoryIsImmutableAndRetainedWhenSourcesRefresh() throws Exception {
        approvalPolicy(); create("r1",List.of(approved(candidate("a",target,"38"),"denial-1",OvertimeApproval.Decision.DENIED)));
        clock.now.set(NOW.plusSeconds(1));
        service.reevaluate("manager-1","r1",List.of(approved(candidate("a",target,"38"),"approval-2",OvertimeApproval.Decision.APPROVED)));
        assertEquals(2,count("approvals"));
        assertThrows(SQLException.class,()->sql("UPDATE approvals SET decision='APPROVED' WHERE id='denial-1'"));
        assertThrows(SQLException.class,()->sql("DELETE FROM approvals WHERE id='denial-1'"));
        service=reopen(); assertEquals(1,service.pendingOffers("a").size());
    }

    @Test void originalVerificationTimesAndStaleEligibilityArePreserved() throws Exception {
        create("r1",List.of(candidate("a"))); var offered=service.pendingOffers("a").getFirst();
        clock.now.set(NOW.plusSeconds(901)); service=reopen();
        var result=service.accept("a",offered.id());
        assertEquals(ELIGIBILITY_CHANGED,result.code());
        assertEquals(EligibilityResult.Code.STALE_DATA,result.eligibility().orElseThrow().reasons().getFirst().code());
        assertEquals(0,count("assigned_shifts"));
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT verified_through FROM candidate_sources")) {
            r.next();assertEquals(NOW.toString(),r.getString(1));
        }
    }

    @Test void existingServiceInstanceSeesLatestCommittedManagerRefresh() {
        create("r1",List.of(candidate("a"))); var offer=service.pendingOffers("a").getFirst();
        var second=reopen(); var request=candidate("a"); var e=request.eligibility().employee();
        var inactive=new Employee(e.id(),e.name(),Employee.EmploymentStatus.INACTIVE,e.qualifications(),e.availability());
        var input=request.eligibility();
        second.reevaluate("manager-1","r1",List.of(new ScoringRequest(new EligibilityRequest(inactive,target,List.of(),input.workHours(),
                policy,List.of(),NOW,NOW),Optional.empty())));
        assertEquals(OFFER_NOT_PENDING,service.accept("a",offer.id()).code());
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test void duplicateRecoveryAndDuplicateShiftClaimArePreventedAcrossRestarts() {
        create("r1",List.of(candidate("a"))); service=reopen();
        assertEquals(DUPLICATE_RECOVERY,create("r2",List.of(candidate("b"))).code());
        service.accept("a",service.pendingOffers("a").getFirst().id());service=reopen();
        assertEquals(ALREADY_FILLED,create("r3",List.of(candidate("b"))).code());
    }

    @Test void transactionFailureDuringAssignmentRollsBackEveryWinningStateChange() throws Exception {
        create("r1",List.of(candidate("a"),candidate("b"))); var before=view("r1");
        var id=service.pendingOffers("a").getFirst().id();
        sql("CREATE TRIGGER simulated_failure BEFORE INSERT ON assigned_shifts BEGIN SELECT RAISE(ABORT,'simulated disk write failure'); END");
        assertThrows(PersistenceException.class,()->service.accept("a",id));
        service=reopen(); assertEquals(before,view("r1"));
        assertEquals(0,count("assigned_shifts")); assertEquals(2,service.pendingOffers("a").size()+service.pendingOffers("b").size());
        sql("DROP TRIGGER simulated_failure");
        assertEquals(ACCEPTED,service.accept("a",id).code());
    }

    @Test void transactionFailureDuringCreationLeavesNoPartialRecords() throws Exception {
        sql("CREATE TRIGGER simulated_failure BEFORE INSERT ON offers BEGIN SELECT RAISE(ABORT,'simulated offer write failure'); END");
        assertThrows(PersistenceException.class,()->create("r1",List.of(candidate("a"))));
        assertEquals(0,count("recoveries"));assertEquals(0,count("offers"));assertEquals(0,count("employees"));assertEquals(0,count("shifts"));
        sql("DROP TRIGGER simulated_failure");
        assertEquals(CREATED,create("r1",List.of(candidate("a"))).code());
    }

    @Test void foreignKeysUniquenessAndDeferredClaimRelationshipsAreEnforced() throws Exception {
        create("r1",List.of(candidate("a"),candidate("b")));
        assertThrows(SQLException.class,()->sql("INSERT INTO candidate_approvals VALUES('r1','a',0,'missing')"));
        assertThrows(SQLException.class,()->sql("INSERT INTO recoveries SELECT 'duplicate',shift_id,manager_id,requested_at,status,replacement_id,policy,config,source_revision FROM recoveries WHERE id='r1'"));
        assertThrows(SQLException.class,()->sql("UPDATE offers SET status='ACCEPTED',claim_recovery_id=recovery_id WHERE employee_id='a'"));
        assertEquals(ShiftOffer.Status.PENDING,service.pendingOffers("a").getFirst().status());
        service.accept("a",service.pendingOffers("a").getFirst().id());
        assertThrows(SQLException.class,()->sql("UPDATE recoveries SET status='OPEN',replacement_id=NULL WHERE id='r1'"));
        assertThrows(SQLException.class,()->sql("UPDATE offers SET status='PENDING',claim_recovery_id=NULL WHERE employee_id='a'"));
        assertThrows(SQLException.class,()->sql("DELETE FROM assigned_shifts WHERE recovery_id='r1'"));
        assertThrows(SQLException.class,()->sql("UPDATE recovery_events SET message='rewritten' WHERE recovery_id='r1'"));
    }

    @Test void historyAndNanosecondTimestampsSurviveRepeatedReopening() {
        create("r1",List.of(candidate("a"),candidate("b")));
        clock.now.set(NOW.plusNanos(1));service.decline("a",service.pendingOffers("a").getFirst().id());
        var expected=view("r1"); service=reopen();service=reopen();
        assertEquals(expected,view("r1"));
        assertEquals(NOW,view("r1").events().getFirst().at());
        assertTrue(view("r1").events().stream().anyMatch(e->e.at().equals(NOW.plusNanos(1))));
    }

    @Test void policyQualificationSubstitutionsAndScoringHistoryRoundTrip() {
        var substitute=new Qualification(Qualification.Type.ROLE,"lead|extended");
        policy=new EligibilityPolicy(PERIOD,policy.overtime(),policy.maxSnapshotAge(),policy.approvalValidity(),policy.authorizedManagerIds(),Map.of(ROLE,Set.of(substitute)));
        var input=candidate("a").eligibility();
        var old=input.employee();
        var employee=new Employee(old.id(),old.name(),old.status(),Set.of(new EmployeeQualification(substitute,Optional.empty())),old.availability());
        var history=new ScoringHistory("a",new TimeWindow(NOW.minus(config.scoring().historyLookback()),NOW),
                List.of(new ScoringHistory.OfferEvent("past-1",NOW.minusSeconds(7200),NOW.minusSeconds(3600),Optional.of(NOW.minusSeconds(7000)))),List.of());
        var request=new ScoringRequest(new EligibilityRequest(employee,target,List.of(),input.workHours(),policy,List.of(),NOW,NOW),Optional.of(history));
        var result=create("r1",List.of(request)); assertEquals(CREATED,result.code());
        var initial=view("r1");service=reopen();assertEquals(initial,view("r1"));
        assertEquals(ACCEPTED,service.accept("a",service.pendingOffers("a").getFirst().id()).code());
    }

    @Test void missingRequiredMetadataFailsClosedWithoutReinitializing() throws Exception {
        create("r1",List.of(candidate("a")));sql("DELETE FROM metadata");
        service=reopen();assertThrows(PersistenceException.class,()->service.pendingOffers("a"));
        assertEquals(1,count("recoveries"));assertEquals(0,count("metadata"));
    }

    @Test void malformedStoredJsonFailsClosedWithoutDiscardingRecords() throws Exception {
        create("r1",List.of(candidate("a")));sql("UPDATE recoveries SET config='{}' WHERE id='r1'");
        service=reopen();assertThrows(PersistenceException.class,()->service.pendingOffers("a"));assertEquals(1,count("offers"));
    }

    @Test void brokenForeignKeyRecordsAreDetectedEvenIfExternalWriterDisabledEnforcement() throws Exception {
        create("r1",List.of(candidate("a")));
        try(var c=connection();var s=c.createStatement()){s.execute("PRAGMA foreign_keys=OFF");s.execute("DELETE FROM employees WHERE id='a'");}
        service=reopen();assertThrows(PersistenceException.class,()->service.pendingOffers("a"));assertEquals(1,count("offers"));
    }

    @Test void unsupportedSchemaVersionIsRejectedWithoutResettingData() throws Exception {
        create("r1",List.of(candidate("a")));sql("PRAGMA user_version=99");
        assertThrows(PersistenceException.class,this::reopen);assertEquals(1,count("recoveries"));
    }

    @Test void unknownExistingDatabaseIsNotAdoptedOrCleared() throws Exception {
        var unknown=directory.resolve("unrelated.db");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+unknown);var s=c.createStatement()){s.execute("CREATE TABLE unrelated(value TEXT)");s.execute("INSERT INTO unrelated VALUES('keep me')");}
        assertThrows(PersistenceException.class,()->new SqliteRecoveryStore(unknown));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+unknown);var s=c.createStatement();var r=s.executeQuery("SELECT value FROM unrelated")){r.next();assertEquals("keep me",r.getString(1));}
    }

    @Test void nonDatabaseFileIsNotOverwritten() throws Exception {
        var invalid=directory.resolve("not-a-db.db");Files.writeString(invalid,"keep this original text");
        assertThrows(PersistenceException.class,()->new SqliteRecoveryStore(invalid));
        assertEquals("keep this original text",Files.readString(invalid));
    }

    @RepeatedTest(5) void independentServiceInstancesCannotBothAcceptOneShift() throws Exception {
        create("r1",List.of(candidate("a"),candidate("b")));
        var a=service.pendingOffers("a").getFirst();var b=service.pendingOffers("b").getFirst();var second=reopen();
        var outcomes=race(()->service.accept("a",a.id()),()->second.accept("b",b.id()));
        assertEquals(1,outcomes.stream().filter(r->r.code()==ACCEPTED).count());
        assertEquals(1,outcomes.stream().filter(r->r.code()==ALREADY_FILLED).count());
        service=reopen();assertEquals(1,count("assigned_shifts"));
        assertTrue(service.pendingOffers("a").isEmpty());assertTrue(service.pendingOffers("b").isEmpty());
    }

    @RepeatedTest(3) void independentServiceInstancesCannotCreateDuplicateActiveRecoveries() throws Exception {
        var second=reopen();var input=List.of(candidate("a"));
        var outcomes=race(()->create("r1",input),()->second.create("r2","manager-1",target,policy,input,config));
        assertEquals(1,outcomes.stream().filter(r->r.code()==CREATED).count());
        assertEquals(1,outcomes.stream().filter(r->r.code()==DUPLICATE_RECOVERY).count());
        assertEquals(1,count("recoveries"));
    }

    @RepeatedTest(3) void concurrentClaimsAcrossRecoveriesSeeCommittedAssignments() throws Exception {
        var other=new Shift("shift-2",target.locationId(),new TimeWindow(target.window().start().plusSeconds(3600),target.window().end().plusSeconds(3600)),Set.of(ROLE),Optional.empty());
        create("r1",List.of(candidate("a")));service.create("r2","manager-1",other,policy,List.of(candidate("a",other,"18")),config);
        var offered=service.pendingOffers("a");var second=reopen();
        var outcomes=race(()->service.accept("a",offered.getFirst().id()),()->second.accept("a",offered.getLast().id()));
        assertEquals(1,outcomes.stream().filter(r->r.code()==ACCEPTED).count());
        assertEquals(1,outcomes.stream().filter(r->r.code()==ELIGIBILITY_CHANGED).count());
        assertEquals(1,count("assigned_shifts"));
    }

    @Test void latestEmployeeFactsFromAnotherRecoveryAreUsedBeforeAssignment() {
        create("r1",List.of(candidate("a")));var offered=service.pendingOffers("a").getFirst();
        var other=new Shift("shift-2",target.locationId(),new TimeWindow(target.window().start().plusSeconds(86400),target.window().end().plusSeconds(86400)),Set.of(ROLE),Optional.empty());
        var input=candidate("a",other,"18").eligibility();var e=input.employee();
        var inactive=new Employee(e.id(),e.name(),Employee.EmploymentStatus.INACTIVE,e.qualifications(),e.availability());
        var source=new ScoringRequest(new EligibilityRequest(inactive,other,List.of(),input.workHours(),policy,List.of(),NOW,NOW),Optional.empty());
        assertEquals(CREATED,service.create("r2","manager-1",other,policy,List.of(source),config).code());
        service=reopen();var result=service.accept("a",offered.id());
        assertEquals(ELIGIBILITY_CHANGED,result.code());
        assertTrue(result.eligibility().orElseThrow().reasons().stream().anyMatch(r->r.code()==EligibilityResult.Code.INACTIVE_EMPLOYEE));
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test void latestVerifiedHoursFromAnotherRecoveryOverrideOlderStoredHours() {
        create("r1",List.of(candidate("a")));var offered=service.pendingOffers("a").getFirst();
        var other=new Shift("shift-2",target.locationId(),new TimeWindow(target.window().start().plusSeconds(86400),target.window().end().plusSeconds(86400)),Set.of(ROLE),Optional.empty());
        service.create("r2","manager-1",other,policy,List.of(candidate("a",other,"38")),config);
        service=reopen();var result=service.accept("a",offered.id());
        assertEquals(ELIGIBILITY_CHANGED,result.code());
        assertTrue(result.eligibility().orElseThrow().reasons().stream().anyMatch(r->r.code()==EligibilityResult.Code.OVERTIME_PROHIBITED));
        assertTrue(service.assignmentsFor("a").isEmpty());
    }

    @Test void omittedNewerDenialCannotBeReplacedByOlderApprovalAfterRestart() throws Exception {
        approvalPolicy();var initial=approved(candidate("a",target,"38"),"approval-1",OvertimeApproval.Decision.APPROVED);
        create("r1",List.of(initial));clock.now.set(NOW.plusSeconds(1));
        service.reevaluate("manager-1","r1",List.of(approved(initial,"denial-2",OvertimeApproval.Decision.DENIED)));
        service=reopen();var result=service.reevaluate("manager-1","r1",List.of(initial));
        var evaluated=result.recovery().orElseThrow().candidates().getFirst();
        assertEquals(EligibilityResult.Status.INELIGIBLE,evaluated.eligibility().status());
        assertTrue(evaluated.eligibility().reasons().stream().anyMatch(r->r.code()==EligibilityResult.Code.APPROVAL_DENIED));
        assertTrue(service.pendingOffers("a").isEmpty());assertEquals(2,count("approvals"));
    }

    @Test void missingCandidateEvaluationIsDetectedRatherThanSilentlyRecreated() throws Exception {
        create("r1",List.of(candidate("a")));sql("UPDATE candidate_sources SET score=NULL,score_ordinal=NULL WHERE employee_id='a'");
        service=reopen();assertThrows(PersistenceException.class,()->service.pendingOffers("a"));
        assertEquals(1,count("offers"));
    }

    @Test void terminalDatabaseConstraintsRejectASecondWinningOffer() throws Exception {
        create("r1",List.of(candidate("a"),candidate("b")));
        service.accept("a",service.pendingOffers("a").getFirst().id());
        assertThrows(SQLException.class,()->sql("UPDATE offers SET status='ACCEPTED',claim_recovery_id=recovery_id WHERE employee_id='b'"));
        assertEquals(1,count("assigned_shifts"));
        assertEquals(1,view("r1").offers().stream().filter(o->o.status()==ShiftOffer.Status.ACCEPTED).count());
    }

    @Test void durableOfferCounterDoesNotReuseIdsAfterRestart() {
        create("r1",List.of(candidate("a")));String first=service.pendingOffers("a").getFirst().id();
        service.cancel("manager-1","r1");service=reopen();create("r2",List.of(candidate("a")));
        assertNotEquals(first,service.pendingOffers("a").getFirst().id());
    }

    @Test void unknownVersionZeroDatabaseWithRecognizedTablesIsNotReinitialized() throws Exception {
        create("r1",List.of(candidate("a")));sql("PRAGMA user_version=0");
        assertThrows(PersistenceException.class,this::reopen);assertEquals(1,count("recoveries"));
    }

    @Test void removingCandidateDoesNotEraseLatestKnownEmployeeFacts() {
        create("r1",List.of(candidate("a")));var offer=service.pendingOffers("a").getFirst();
        var other=new Shift("shift-2",target.locationId(),new TimeWindow(target.window().start().plusSeconds(86400),target.window().end().plusSeconds(86400)),Set.of(ROLE),Optional.empty());
        var input=candidate("a",other,"18").eligibility();var old=input.employee();
        var inactive=new Employee(old.id(),old.name(),Employee.EmploymentStatus.INACTIVE,old.qualifications(),old.availability());
        service.create("r2","manager-1",other,policy,List.of(new ScoringRequest(new EligibilityRequest(inactive,other,List.of(),input.workHours(),policy,List.of(),NOW,NOW),Optional.empty())),config);
        service.reevaluate("manager-1","r2",List.of());
        service=reopen();var rejected=service.accept("a",offer.id());
        assertEquals(ELIGIBILITY_CHANGED,rejected.code());
        assertTrue(rejected.eligibility().orElseThrow().reasons().stream().anyMatch(r->r.code()==EligibilityResult.Code.INACTIVE_EMPLOYEE));
    }

    @Test void missingAuthoritativeSourceFailsClosedInsteadOfFallingBackToOldSnapshot() throws Exception {
        create("r1",List.of(candidate("a")));sql("DELETE FROM authoritative_sources WHERE employee_id='a'");
        service=reopen();assertThrows(PersistenceException.class,()->service.pendingOffers("a"));assertEquals(1,count("offers"));
    }

    @Test void failureAfterWritingAssignmentEventsAndRecoveryStillRollsBackCompletely() throws Exception {
        create("r1",List.of(candidate("a"),candidate("b")));var before=view("r1");var id=service.pendingOffers("a").getFirst().id();
        sql("CREATE TRIGGER fail_last_write BEFORE UPDATE ON metadata BEGIN SELECT RAISE(ABORT,'simulated final write failure'); END");
        assertThrows(PersistenceException.class,()->service.accept("a",id));
        sql("DROP TRIGGER fail_last_write");service=reopen();assertEquals(before,view("r1"));assertEquals(0,count("assigned_shifts"));
        assertEquals(ACCEPTED,service.accept("a",id).code());
    }

    @Test void employeeIdentifiersAreBoundAsValuesNotExecutableSql() throws Exception {
        String id="employee'); DROP TABLE recoveries; --";
        assertEquals(CREATED,create("r1",List.of(candidate(id))).code());service=reopen();
        assertEquals(id,service.pendingOffers(id).getFirst().employeeId());assertEquals(1,count("recoveries"));
    }

    private <T> List<T> race(Callable<T> a,Callable<T> b) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(a,b).stream().map(task->pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Race timeout");return task.call();})).toList();
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            return List.of(tasks.getFirst().get(10,TimeUnit.SECONDS),tasks.getLast().get(10,TimeUnit.SECONDS));
        }
    }
}
