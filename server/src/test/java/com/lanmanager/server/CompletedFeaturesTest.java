package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.classroom.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(PostgresTestDatabase.class)
class CompletedFeaturesTest {
  @TempDir Path temp;
  private ServerBackend server;
  private User teacher, student, otherTeacher;
  private long classId, sessionId;

  @BeforeEach
  void open() throws Exception {
    Path config = temp.resolve("server.properties");
    Files.writeString(
        config,
        "http.port=0\nudp.port=0\nactivity.seconds=300\n" + PostgresTestDatabase.properties());
    server = new ServerBackend(new ApplicationConfig(config));
    server.accounts.createAccount("teacher", "correct-password", "Giáo viên", "TEACHER");
    server.accounts.createAccount("student", "correct-password", "Sinh viên", "STUDENT");
    server.accounts.createAccount("other", "correct-password", "Giáo viên khác", "TEACHER");
    teacher = server.accounts.authenticate(server.accounts.login("teacher", "correct-password"));
    student = server.accounts.authenticate(server.accounts.login("student", "correct-password"));
    otherTeacher = server.accounts.authenticate(server.accounts.login("other", "correct-password"));
    classId = server.classroom.startClass(teacher, "Kiểm tra", "EXAM");
    var machine =
        server.machines.register(
            JsonCodec.object(
                "machineId", "pc", "machineName", "PC", "os", "Windows", "udpPort", 9879),
            "127.0.0.1");
    sessionId = server.classroom.joinClass(student, classId, machine);
  }

  @AfterEach
  void close() throws Exception {
    if (server != null) server.close();
  }

  @Test
  void secondServerCannotInterruptExistingStudentSession() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> new ServerBackend(new ApplicationConfig(temp.resolve("server.properties"))));
    assertTrue(
        server
            .classroomDatabase
            .query("SELECT ended_at FROM student_sessions WHERE id=?", sessionId)
            .get(0)
            .getAsJsonObject()
            .get("ended_at")
            .isJsonNull());
    assertNotNull(
        server.accounts.authenticate(server.accounts.login("student", "correct-password")));
  }

  @Test
  void examStartSurvivesChangingMachineAndSubmitIsIdempotent() throws Exception {
    var exams = new ExamService(server.classroomDatabase, server.classroom);
    var first = exams.start(student, sessionId);
    assertEquals(first, exams.start(student, sessionId));
    server.classroom.leaveClass(student, sessionId);
    var machine =
        server.machines.register(
            JsonCodec.object(
                "machineId", "pc-two", "machineName", "PC 2", "os", "Windows", "udpPort", 9879),
            "127.0.0.1");
    long rejoined = server.classroom.joinClass(student, classId, machine);
    assertEquals(first.get("started_at"), exams.start(student, rejoined).get("started_at"));
    var submitted = exams.submit(student, rejoined);
    assertEquals(submitted, exams.submit(student, rejoined));
    assertThrows(IllegalStateException.class, () -> exams.start(student, rejoined));
    assertThrows(SecurityException.class, () -> exams.submit(teacher, rejoined));
    var report = server.classroom.report(teacher, classId);
    assertEquals(
        submitted.get("submitted_at"),
        report.getAsJsonArray("students").get(0).getAsJsonObject().get("exam_submitted_at"));
  }

  @Test
  void endingExamClosesWithoutInventingSubmission() throws Exception {
    var exams = new ExamService(server.classroomDatabase, server.classroom);
    exams.start(student, sessionId);
    server.classroom.endClass(teacher, classId);
    var attempt =
        server.classroomDatabase.query("SELECT * FROM exam_attempts").get(0).getAsJsonObject();
    assertTrue(attempt.get("submitted_at").isJsonNull());
    assertFalse(attempt.get("closed_at").isJsonNull());
    assertEquals("CLASS_ENDED", attempt.get("end_reason").getAsString());
  }

  @Test
  void fullPaginationFiltersAndStableExportSnapshot() throws Exception {
    long at = System.currentTimeMillis();
    server.classroomDatabase.execute(
        "INSERT INTO"
            + " activity_records(student_session_id,kind,source_key,details,source_at,observed_at)"
            + " SELECT ?,'WEB','visit:' || n,'https://youtube.com/' || n,?+n,?+n FROM"
            + " generate_series(1,1205) n",
        sessionId,
        at,
        at);
    var history = new HistoryQueryService(server.classroomDatabase, server.classroom);
    JsonObject filter =
        JsonCodec.object(
            "classId",
            classId,
            "studentSessionId",
            sessionId,
            "category",
            "ACTIVITY",
            "kind",
            "WEB",
            "keyword",
            "youtube");
    var first = history.page(teacher, filter);
    long before = first.get("nextBeforeId").getAsLong();
    Set<Long> ids = new HashSet<>();
    for (var row : first.getAsJsonArray("rows"))
      ids.add(row.getAsJsonObject().get("id").getAsLong());
    server.classroomDatabase.execute(
        "INSERT INTO"
            + " activity_records(student_session_id,kind,source_key,details,source_at,observed_at)"
            + " VALUES(?,'WEB','new','https://youtube.com/new',?,?)",
        sessionId,
        at,
        at);
    filter.addProperty("snapshotId", first.get("snapshotId").getAsLong());
    boolean more = first.get("hasMore").getAsBoolean();
    while (more) {
      filter.addProperty("beforeId", before);
      var page = history.page(teacher, filter);
      for (var row : page.getAsJsonArray("rows"))
        assertTrue(ids.add(row.getAsJsonObject().get("id").getAsLong()));
      more = page.get("hasMore").getAsBoolean();
      before = page.get("nextBeforeId").getAsLong();
    }
    assertEquals(1205, ids.size());
    var period =
        JsonCodec.object("classId", classId, "fromAt", at + 5, "toAt", at + 7, "kind", "WEB");
    assertEquals(3, history.page(teacher, period).getAsJsonArray("rows").size());
    assertThrows(SecurityException.class, () -> history.page(otherTeacher, filter));
    assertThrows(SecurityException.class, () -> history.page(student, filter));
  }

  @Test
  void domainRulesDoNotMatchLookalikesAndNeverDeductPoints() throws Exception {
    var rules = new AlertRuleService(server.classroomDatabase, server.classroom);
    rules.add(teacher, classId, "WEB", "google.com");
    rules.add(teacher, classId, "PROCESS", "game.exe");
    JsonArray entries = new JsonArray();
    long now = System.currentTimeMillis();
    for (int i = 0; i < 3; i++)
      entries.add(
          JsonCodec.object(
              "visitId",
              i + 1,
              "visitTime",
              now,
              "title",
              "Test",
              "url",
              new String[] {
                    "https://www.google.com/search",
                    "https://google.com.evil.test/",
                    "https://example.test/?url=google.com"
                  }
                  [i]));
    JsonArray processes = new JsonArray();
    processes.add(JsonCodec.object("pid", 123, "name", "Game.exe", "startedAt", 1));
    JsonArray browsers = new JsonArray();
    browsers.add(JsonCodec.object("browser", "chrome", "entries", entries));
    var recorder = new ActivityRecorder(server.classroomDatabase);
    var data = JsonCodec.object("processes", processes, "browsers", browsers);
    recorder.record(sessionId, data);
    recorder.record(sessionId, data);
    assertEquals(2, server.classroomDatabase.query("SELECT * FROM rule_alerts").size());
    assertEquals(0, server.classroomDatabase.query("SELECT * FROM violations").size());
    var alerts =
        new HistoryQueryService(server.classroomDatabase, server.classroom)
            .page(teacher, JsonCodec.object("classId", classId, "category", "ALERT"));
    assertEquals(2, alerts.getAsJsonArray("rows").size());
  }

  @Test
  void accountLockAndPasswordResetRevokeSessionsAndKeepAudit() throws Exception {
    String token = server.accounts.login("student", "correct-password");
    server.accounts.setEnabled(teacher, student.id(), false);
    assertThrows(SecurityException.class, () -> server.accounts.authenticate(token));
    assertThrows(
        SecurityException.class, () -> server.accounts.login("student", "correct-password"));
    assertThrows(
        IllegalArgumentException.class,
        () -> server.accounts.setEnabled(teacher, teacher.id(), false));
    assertEquals(
        "ACCOUNT_DISABLED",
        server
            .classroomDatabase
            .query("SELECT end_reason FROM student_sessions WHERE id=?", sessionId)
            .get(0)
            .getAsJsonObject()
            .get("end_reason")
            .getAsString());
    server.accounts.setEnabled(teacher, student.id(), true);
    server.accounts.resetPassword(teacher, student.id(), "changed-password");
    assertThrows(
        SecurityException.class, () -> server.accounts.login("student", "correct-password"));
    assertNotNull(server.accounts.login("student", "changed-password"));
    assertEquals(3, server.classroomDatabase.query("SELECT * FROM account_actions").size());
  }
}
