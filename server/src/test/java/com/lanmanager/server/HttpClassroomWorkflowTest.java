package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.HttpTransport;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.classroom.ActivityMonitor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(PostgresTestDatabase.class)
class HttpClassroomWorkflowTest {
  @TempDir Path temp;
  private HttpTransport http;

  private com.lanmanager.common.protocol.http.HttpMessage post(
      String route, JsonObject body, String token) throws Exception {
    return http.send(
        "POST",
        "/api/v1/classroom/" + route,
        body,
        token == null ? Map.of() : Map.of("X-User-Token", token));
  }

  private String login(String name) throws Exception {
    return post("login", JsonCodec.object("username", name, "password", "test-password-123"), null)
        .json()
        .get("accessToken")
        .getAsString();
  }

  @Test
  void teacherStudentExamCollectionControlsAndEvidenceThroughHttp() throws Exception {
    Path config = temp.resolve("server.properties");
    Files.writeString(
        config,
        "http.port=0\nudp.port=0\nactivity.seconds=300\nevidence.path="
            + temp.resolve("evidence").toString().replace('\\', '/')
            + "\n"
            + PostgresTestDatabase.properties());
    try (var backend = new ServerBackend(new ApplicationConfig(config))) {
      http = new HttpTransport("127.0.0.1", backend.http.port());
      backend.accounts.createAccount("teacher", "test-password-123", "Cô giáo", "TEACHER");
      String teacher = login("teacher");
      assertEquals(
          201,
          post(
                  "accounts/create",
                  JsonCodec.object(
                      "username",
                      "student",
                      "password",
                      "test-password-123",
                      "fullName",
                      "Sinh viên",
                      "role",
                      "STUDENT"),
                  teacher)
              .status());
      String student = login("student");
      var invalidLogin =
          assertThrows(
              com.lanmanager.common.protocol.http.ClassroomClient.RequestException.class,
              () ->
                  com.lanmanager.common.protocol.http.ClassroomClient.call(
                      http, "me", JsonCodec.object(), "expired-token", null));
      assertEquals(403, invalidLogin.statusCode());
      long classId =
          post("classes/start", JsonCodec.object("name", "Bài kiểm tra", "type", "EXAM"), teacher)
              .json()
              .get("id")
              .getAsLong();
      var registration =
          http.send(
              "POST",
              "/api/v1/register",
              JsonCodec.object(
                  "machineId", "pc", "machineName", "PC", "os", "Windows", "udpPort", 9879),
              Map.of("X-Lan-Token", "lan-demo-token"));
      String agent = registration.json().get("sessionId").getAsString();
      long session =
          http.send(
                  "POST",
                  "/api/v1/classroom/students/join",
                  JsonCodec.object("classId", classId),
                  Map.of("X-User-Token", student, "X-Lan-Session", agent))
              .json()
              .get("id")
              .getAsLong();
      assertEquals(
          200,
          post("exams/start", JsonCodec.object("studentSessionId", session), student).status());
      var currentSession =
          post("students/status", JsonCodec.object(), student)
              .json()
              .getAsJsonArray("sessions")
              .get(0)
              .getAsJsonObject();
      assertEquals(session, currentSession.get("id").getAsLong());
      assertTrue(currentSession.get("signed_in_at").getAsLong() > 0);
      assertEquals(
          201,
          post(
                  "rules/add",
                  JsonCodec.object("classId", classId, "kind", "WEB", "pattern", "youtube.com"),
                  teacher)
              .status());
      new ActivityMonitor(backend.classroomDatabase, backend.machines, backend.requests).collect();
      var collection = backend.requests.poll(agent);
      assertEquals("GET_ACTIVITY", collection.get("type").getAsString());
      JsonArray visits = new JsonArray();
      visits.add(
          JsonCodec.object(
              "url",
              "https://www.youtube.com/watch?v=test",
              "title",
              "Ví dụ",
              "visitTime",
              System.currentTimeMillis(),
              "visitId",
              1));
      JsonArray browsers = new JsonArray();
      browsers.add(JsonCodec.object("browser", "chrome", "entries", visits));
      assertEquals(
          200,
          http.send(
                  "POST",
                  "/api/v1/results",
                  JsonCodec.object(
                      "commandId",
                      collection.get("commandId").getAsString(),
                      "ok",
                      true,
                      "payload",
                      JsonCodec.object("processes", new JsonArray(), "browsers", browsers)),
                  Map.of("X-Lan-Session", agent))
              .status());
      assertEquals(
          1,
          post("history/page", JsonCodec.object("classId", classId, "category", "ALERT"), teacher)
              .json()
              .getAsJsonArray("rows")
              .size());
      assertEquals(
          1,
          post("students/page", JsonCodec.object("classId", classId), teacher)
              .json()
              .getAsJsonArray("rows")
              .size());
      long violation =
          post(
                  "violations/flag",
                  JsonCodec.object(
                      "studentSessionId",
                      session,
                      "details",
                      "Cần xem xét",
                      "occurredAt",
                      System.currentTimeMillis()),
                  teacher)
              .json()
              .get("id")
              .getAsLong();
      String screenshotRequest =
          post(
                  "monitor/request",
                  JsonCodec.object(
                      "studentSessionId",
                      session,
                      "type",
                      "GET_SCREENSHOT",
                      "payload",
                      JsonCodec.object()),
                  teacher)
              .json()
              .get("requestId")
              .getAsString();
      var screenshotCommand = backend.requests.poll(agent);
      byte[] image;
      try (var output = new ByteArrayOutputStream()) {
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", output);
        image = output.toByteArray();
      }
      backend.requests.result(
          agent,
          JsonCodec.object(
              "commandId",
              screenshotCommand.get("commandId").getAsString(),
              "ok",
              true,
              "payload",
              JsonCodec.object(
                  "capturedAt",
                  System.currentTimeMillis(),
                  "base64",
                  Base64.getEncoder().encodeToString(image))));
      long evidence =
          post(
                  "evidence/save",
                  JsonCodec.object("violationId", violation, "requestId", screenshotRequest),
                  teacher)
              .json()
              .get("id")
              .getAsLong();
      assertEquals(
          200, post("evidence/read", JsonCodec.object("evidenceId", evidence), teacher).status());
      assertEquals(
          403, post("evidence/read", JsonCodec.object("evidenceId", evidence), student).status());
      var control =
          JsonCodec.object(
              "studentSessionId",
              session,
              "type",
              "LOCK_SCREEN",
              "payload",
              JsonCodec.object(),
              "confirmed",
              false);
      assertEquals(400, post("monitor/control", control, teacher).status());
      control.addProperty("confirmed", true);
      assertEquals(403, post("monitor/control", control, student).status());
      assertEquals(201, post("monitor/control", control, teacher).status());
      var lock = backend.requests.poll(agent);
      backend.requests.result(
          agent,
          JsonCodec.object(
              "commandId",
              lock.get("commandId").getAsString(),
              "ok",
              true,
              "payload",
              JsonCodec.object("detail", "dry-run")));
      var report = post("classes/report", JsonCodec.object("classId", classId), teacher).json();
      assertEquals(
          "COMPLETED",
          report.getAsJsonArray("controls").get(0).getAsJsonObject().get("status").getAsString());
      assertEquals(
          200,
          post("exams/submit", JsonCodec.object("studentSessionId", session), student).status());
      assertEquals(
          200, post("classes/end", JsonCodec.object("classId", classId), teacher).status());
      assertEquals(
          "COMPLETED",
          post("monitor/result", JsonCodec.object("requestId", screenshotRequest), teacher)
              .json()
              .get("status")
              .getAsString());
      assertEquals(
          201,
          post(
                  "evidence/save",
                  JsonCodec.object("violationId", violation, "requestId", screenshotRequest),
                  teacher)
              .status());
      assertFalse(
          post("students/status", JsonCodec.object(), student)
              .json()
              .getAsJsonArray("attempts")
              .get(0)
              .getAsJsonObject()
              .get("submitted_at")
              .isJsonNull());
    }
  }
}
