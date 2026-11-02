package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(PostgresTestDatabase.class)
class ClassDeletionTest {
  @TempDir Path temp;
  private HttpTransport http;

  private ServerBackend server() throws Exception {
    Path config = temp.resolve("server.properties");
    Files.writeString(
        config,
        "http.port=0\nudp.port=0\nactivity.seconds=300\n" + PostgresTestDatabase.properties());
    var server = new ServerBackend(new ApplicationConfig(config));
    http = new HttpTransport("127.0.0.1", server.http.port());
    return server;
  }

  private HttpMessage post(String route, JsonObject body, String token) throws Exception {
    return http.send("POST", "/api/v1/classroom/" + route, body, Map.of("X-User-Token", token));
  }

  private String account(ServerBackend server, String name, String role) throws Exception {
    server.accounts.createAccount(name, "test-password-123", name, role);
    return server.accounts.login(name, "test-password-123");
  }

  private long startClass(String token, String name, String type) throws Exception {
    return post("classes/start", JsonCodec.object("name", name, "type", type), token)
        .json()
        .get("id")
        .getAsLong();
  }

  @Test
  void onlyOwnerWithConfirmationCanDeleteAnUnusedClassAndItsRules() throws Exception {
    try (var server = server()) {
      String teacher = account(server, "teacher", "TEACHER");
      String other = account(server, "other", "TEACHER");
      String student = account(server, "student", "STUDENT");
      long id = startClass(teacher, "Buổi tạo nhầm", "CLASS");
      long unrelated = startClass(other, "Buổi của giáo viên khác", "CLASS");
      assertEquals(
          201,
          post(
                  "rules/add",
                  JsonCodec.object("classId", id, "kind", "WEB", "pattern", "youtube.com"),
                  teacher)
              .status());
      JsonObject deletion = JsonCodec.object("classId", id, "confirmed", true);
      assertEquals(400, post("classes/delete", JsonCodec.object("classId", id), teacher).status());
      assertEquals(
          400,
          post("classes/delete", JsonCodec.object("classId", id, "confirmed", false), teacher)
              .status());
      assertEquals(403, post("classes/delete", deletion, student).status());
      assertEquals(403, post("classes/delete", deletion, other).status());
      assertEquals(
          1,
          server
              .classroomDatabase
              .query("SELECT id FROM monitor_rules WHERE class_id=?", id)
              .size());

      assertEquals(200, post("classes/delete", deletion, teacher).status());
      assertTrue(
          server.classroomDatabase.query("SELECT id FROM class_sessions WHERE id=?", id).isEmpty());
      assertTrue(
          server
              .classroomDatabase
              .query("SELECT id FROM monitor_rules WHERE class_id=?", id)
              .isEmpty());
      assertEquals(
          1,
          server
              .classroomDatabase
              .query("SELECT id FROM class_sessions WHERE id=?", unrelated)
              .size());

      long ended = startClass(teacher, "Buổi chưa ai tham gia", "CLASS");
      assertEquals(200, post("classes/end", JsonCodec.object("classId", ended), teacher).status());
      assertEquals(
          200,
          post("classes/delete", JsonCodec.object("classId", ended, "confirmed", true), teacher)
              .status());
    }
  }

  @Test
  void classWithAttendanceAndExamHistoryCannotBeDeletedEvenAfterItEnds() throws Exception {
    try (var server = server()) {
      String teacher = account(server, "teacher", "TEACHER");
      String student = account(server, "student", "STUDENT");
      long id = startClass(teacher, "Bài kiểm tra", "EXAM");
      assertEquals(
          201,
          post(
                  "rules/add",
                  JsonCodec.object("classId", id, "kind", "WEB", "pattern", "youtube.com"),
                  teacher)
              .status());
      var machine =
          server.machines.register(
              JsonCodec.object("machineId", "test-pc", "machineName", "PC thử", "udpPort", 9879),
              "127.0.0.1");
      long session =
          http.send(
                  "POST",
                  "/api/v1/classroom/students/join",
                  JsonCodec.object("classId", id),
                  Map.of("X-User-Token", student, "X-Lan-Session", machine.sessionId()))
              .json()
              .get("id")
              .getAsLong();
      assertEquals(
          200,
          post("exams/start", JsonCodec.object("studentSessionId", session), student).status());
      long violation =
          post(
                  "violations/flag",
                  JsonCodec.object(
                      "studentSessionId",
                      session,
                      "details",
                      "Sự việc mẫu",
                      "occurredAt",
                      System.currentTimeMillis()),
                  teacher)
              .json()
              .get("id")
              .getAsLong();
      assertEquals(
          200,
          post(
                  "violations/review",
                  JsonCodec.object(
                      "violationId",
                      violation,
                      "status",
                      "CONFIRMED",
                      "points",
                      2,
                      "note",
                      "Đã xem xét"),
                  teacher)
              .status());
      JsonObject deletion = JsonCodec.object("classId", id, "confirmed", true);
      assertEquals(400, post("classes/delete", deletion, teacher).status());
      assertEquals(200, post("classes/end", JsonCodec.object("classId", id), teacher).status());
      var rejected = post("classes/delete", deletion, teacher);
      assertEquals(400, rejected.status());
      assertTrue(
          rejected
              .json()
              .getAsJsonObject("error")
              .get("message")
              .getAsString()
              .contains("không thể xóa"));
      assertEquals(
          1,
          server
              .classroomDatabase
              .query("SELECT id FROM student_sessions WHERE class_id=?", id)
              .size());
      assertEquals(
          1,
          server
              .classroomDatabase
              .query("SELECT id FROM exam_attempts WHERE class_id=?", id)
              .size());
      assertEquals(
          1,
          server
              .classroomDatabase
              .query("SELECT id FROM monitor_rules WHERE class_id=?", id)
              .size());
      assertEquals(
          2,
          server
              .classroomDatabase
              .query("SELECT penalty_points FROM violations WHERE id=?", violation)
              .get(0)
              .getAsJsonObject()
              .get("penalty_points")
              .getAsInt());
      assertEquals(
          1,
          post("classes/list", JsonCodec.object(), teacher)
              .json()
              .getAsJsonArray("classes")
              .size());
      assertTrue(
          post("classes/list", JsonCodec.object(), student)
              .json()
              .getAsJsonArray("classes")
              .isEmpty());
    }
  }
}
