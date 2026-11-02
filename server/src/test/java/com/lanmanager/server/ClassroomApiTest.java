package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(PostgresTestDatabase.class)
class ClassroomApiTest {
  @TempDir Path temp;
  private HttpTransport http;

  private HttpMessage post(String route, JsonObject body, String token) throws Exception {
    return http.send(
        "POST",
        "/api/v1/classroom/" + route,
        body,
        token == null ? Map.of() : Map.of("X-User-Token", token));
  }

  private String login(String name) throws Exception {
    return post("login", JsonCodec.object("username", name, "password", "correct-password"), null)
        .json()
        .get("accessToken")
        .getAsString();
  }

  private ApplicationConfig config() throws Exception {
    Path path = temp.resolve("server.properties");
    Files.writeString(path, "http.port=0\nudp.port=0\n" + PostgresTestDatabase.properties());
    return new ApplicationConfig(path);
  }

  @Test
  void studentClassListIncludesNullableEndTimeAndOnlyOpenClasses() throws Exception {
    try (var server = new ServerBackend(config())) {
      http = new HttpTransport("127.0.0.1", server.http.port());
      server.accounts.createAccount("teacher", "correct-password", "Giáo viên", "TEACHER");
      server.accounts.createAccount("student", "correct-password", "Sinh viên", "STUDENT");
      String teacher = login("teacher"), student = login("student");
      long open =
          post("classes/start", JsonCodec.object("name", "Buổi đang mở", "type", "CLASS"), teacher)
              .json()
              .get("id")
              .getAsLong();
      long closed =
          post("classes/start", JsonCodec.object("name", "Buổi đã đóng", "type", "EXAM"), teacher)
              .json()
              .get("id")
              .getAsLong();
      assertEquals(200, post("classes/end", JsonCodec.object("classId", closed), teacher).status());

      var response = post("classes/list", JsonCodec.object(), student);
      assertEquals(200, response.status());
      JsonArray classes = response.json().getAsJsonArray("classes");
      assertEquals(1, classes.size());
      JsonObject row = classes.get(0).getAsJsonObject();
      assertEquals(open, row.get("id").getAsLong());
      assertEquals("Buổi đang mở", row.get("name").getAsString());
      assertEquals("CLASS", row.get("type").getAsString());
      assertTrue(row.get("started_at").getAsLong() > 0);
      assertTrue(row.has("ended_at"));
      assertTrue(row.get("ended_at").isJsonNull());

      assertEquals(200, post("classes/end", JsonCodec.object("classId", open), teacher).status());
      assertTrue(
          post("classes/list", JsonCodec.object(), student)
              .json()
              .getAsJsonArray("classes")
              .isEmpty());
    }
  }

  @Test
  void rolesStudentActivityReviewAndErrorIntervals() throws Exception {
    try (var server = new ServerBackend(config())) {
      http = new HttpTransport("127.0.0.1", server.http.port());
      server.accounts.createAccount("teacher", "correct-password", "Giáo viên", "TEACHER");
      server.accounts.createAccount("student", "correct-password", "Sinh viên", "STUDENT");
      server.accounts.createAccount(
          "other-teacher", "correct-password", "Giáo viên khác", "TEACHER");
      String teacher = login("teacher"),
          student = login("student"),
          otherTeacher = login("other-teacher");
      assertEquals(
          403,
          post("login", JsonCodec.object("username", "teacher", "password", "bad-password"), null)
              .status());
      assertEquals(
          403,
          post("classes/start", JsonCodec.object("name", "Thi", "type", "EXAM"), student).status());
      long classId =
          post("classes/start", JsonCodec.object("name", "Thực hành", "type", "CLASS"), teacher)
              .json()
              .get("id")
              .getAsLong();
      var machine =
          server.machines.register(
              JsonCodec.object(
                  "machineId", "pc", "machineName", "PC", "os", "Windows", "udpPort", 9879),
              "127.0.0.1");
      var join =
          http.send(
              "POST",
              "/api/v1/classroom/students/join",
              JsonCodec.object("classId", classId),
              Map.of("X-User-Token", student, "X-Lan-Session", machine.sessionId()));
      assertEquals(201, join.status());
      long sessionId = join.json().get("id").getAsLong();
      var visit =
          JsonCodec.object(
              "studentSessionId",
              sessionId,
              "kind",
              "WEB",
              "sourceKey",
              "chrome:123",
              "details",
              "https://youtube.com",
              "sourceAt",
              System.currentTimeMillis());
      assertEquals(201, post("activities/record", visit, student).status());
      long visitId = post("activities/record", visit, student).json().get("id").getAsLong();
      assertTrue(visitId > 0);
      var flag =
          JsonCodec.object(
              "studentSessionId",
              sessionId,
              "details",
              "Xem video trong giờ thực hành",
              "occurredAt",
              System.currentTimeMillis());
      assertEquals(403, post("violations/flag", flag, student).status());
      long violationId = post("violations/flag", flag, teacher).json().get("id").getAsLong();
      var monitorBody =
          JsonCodec.object(
              "studentSessionId",
              sessionId,
              "type",
              "GET_PROCESSES",
              "payload",
              JsonCodec.object());
      assertEquals(403, post("monitor/request", monitorBody, student).status());
      String monitorId =
          post("monitor/request", monitorBody, teacher).json().get("requestId").getAsString();
      assertEquals(
          "PENDING",
          post("monitor/result", JsonCodec.object("requestId", monitorId), teacher)
              .json()
              .get("status")
              .getAsString());
      assertEquals(
          403,
          post("monitor/result", JsonCodec.object("requestId", monitorId), otherTeacher).status());
      var command = server.requests.poll(machine.sessionId());
      assertTrue(
          server.requests.result(
              machine.sessionId(),
              JsonCodec.object(
                  "commandId",
                  command.get("commandId").getAsString(),
                  "ok",
                  true,
                  "payload",
                  JsonCodec.object("processes", new JsonArray()))));
      assertEquals(
          "COMPLETED",
          post("monitor/result", JsonCodec.object("requestId", monitorId), teacher)
              .json()
              .get("status")
              .getAsString());
      var review =
          JsonCodec.object(
              "violationId",
              violationId,
              "status",
              "CONFIRMED",
              "points",
              2,
              "note",
              "Đã kiểm tra bằng chứng");
      assertEquals(403, post("violations/review", review, otherTeacher).status());
      assertEquals(200, post("violations/review", review, teacher).status());
      review.addProperty("status", "DISMISSED");
      review.addProperty("points", 0);
      review.addProperty("note", "Không tính vi phạm");
      assertEquals(200, post("violations/review", review, teacher).status());
      var error =
          JsonCodec.object(
              "studentSessionId",
              sessionId,
              "code",
              "BROWSER_BUSY",
              "message",
              "Trình duyệt đang bận",
              "resolved",
              false);
      post("errors/record", error, student);
      post("errors/record", error, student);
      error.addProperty("resolved", true);
      post("errors/record", error, student);
      var report = post("classes/report", JsonCodec.object("classId", classId), teacher).json();
      assertEquals(1, report.getAsJsonArray("activities").size());
      assertEquals(3, report.getAsJsonArray("actions").size());
      var recordedError = report.getAsJsonArray("errors").get(0).getAsJsonObject();
      assertEquals(2, recordedError.get("occurrence_count").getAsInt());
      assertFalse(recordedError.get("resolved_at").isJsonNull());
      assertEquals(
          403, post("classes/report", JsonCodec.object("classId", classId), student).status());
      assertEquals(
          200, post("classes/end", JsonCodec.object("classId", classId), teacher).status());
      assertEquals(403, post("activities/record", visit, student).status());
      assertEquals(
          "CLASS_ENDED",
          server
              .classroom
              .report(server.accounts.authenticate(teacher), classId)
              .getAsJsonArray("students")
              .get(0)
              .getAsJsonObject()
              .get("end_reason")
              .getAsString());
      post("logout", JsonCodec.object(), teacher);
      assertEquals(403, post("classes/list", JsonCodec.object(), teacher).status());
      assertFalse(Files.readString(temp.resolve("server.properties")).contains("correct-password"));
    }
  }

  @Test
  void migrationPreservesAccountsAndMarksInterruptedSession() throws Exception {
    ApplicationConfig config = config();
    long id;
    try (var server = new ServerBackend(config)) {
      server.accounts.createAccount("teacher", "correct-password", "Cô giáo", "TEACHER");
      server.accounts.createAccount("student", "correct-password", "Sinh viên", "STUDENT");
      var teacher =
          server.accounts.authenticate(server.accounts.login("teacher", "correct-password"));
      var student =
          server.accounts.authenticate(server.accounts.login("student", "correct-password"));
      long classId = server.classroom.startClass(teacher, "Thi", "EXAM");
      var machine =
          server.machines.register(
              JsonCodec.object(
                  "machineId", "pc", "machineName", "PC", "os", "Windows", "udpPort", 9879),
              "127.0.0.1");
      id = server.classroom.joinClass(student, classId, machine);
    }
    try (var server = new ServerBackend(config)) {
      assertNotNull(server.accounts.login("teacher", "correct-password"));
      var session =
          server
              .classroomDatabase
              .query("SELECT end_reason FROM student_sessions WHERE id=?", id)
              .get(0)
              .getAsJsonObject();
      assertEquals("SERVER_INTERRUPTED", session.get("end_reason").getAsString());
      var account =
          server
              .classroomDatabase
              .query("SELECT password_hash,salt FROM accounts WHERE username='teacher'")
              .get(0)
              .getAsJsonObject();
      assertNotEquals("correct-password", account.get("password_hash").getAsString());
    }
  }
}
