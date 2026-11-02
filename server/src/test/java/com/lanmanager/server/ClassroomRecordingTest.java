package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.classroom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(PostgresTestDatabase.class)
class ClassroomRecordingTest {
  @TempDir Path temp;

  @Test
  void recordsActivitiesIntervalsEvidenceAndOwnership() throws Exception {
    Path config = temp.resolve("server.properties");
    Files.writeString(
        config,
        "http.port=0\nudp.port=0\nactivity.seconds=300\n" + PostgresTestDatabase.properties());
    try (var server = new ServerBackend(new ApplicationConfig(config))) {
      server.accounts.createAccount("teacher", "correct-password", "Cô giáo", "TEACHER");
      server.accounts.createAccount("student", "correct-password", "Sinh viên", "STUDENT");
      server.accounts.createAccount("other-teacher", "correct-password", "Khác", "TEACHER");
      var teacher =
          server.accounts.authenticate(server.accounts.login("teacher", "correct-password"));
      var student =
          server.accounts.authenticate(server.accounts.login("student", "correct-password"));
      var other =
          server.accounts.authenticate(server.accounts.login("other-teacher", "correct-password"));
      long classId = server.classroom.startClass(teacher, "Kiểm tra", "EXAM");
      var machine =
          server.machines.register(
              JsonCodec.object(
                  "machineId", "pc", "machineName", "PC", "os", "Windows", "udpPort", 9879),
              "127.0.0.1");
      long session = server.classroom.joinClass(student, classId, machine);
      long now = System.currentTimeMillis();
      JsonArray processes = new JsonArray();
      processes.add(JsonCodec.object("pid", 123, "name", "sample.exe", "startedAt", 1));
      JsonArray entries = new JsonArray();
      entries.add(
          JsonCodec.object(
              "visitId", 4, "url", "https://example.test", "title", "Bài tập", "visitTime", now));
      JsonArray browsers = new JsonArray();
      browsers.add(JsonCodec.object("browser", "chrome", "entries", entries));
      var recorder = new ActivityRecorder(server.classroomDatabase);
      var payload = JsonCodec.object("processes", processes, "browsers", browsers);
      recorder.record(session, payload);
      recorder.record(session, payload);
      assertEquals(
          2, server.classroom.report(teacher, classId).getAsJsonArray("activities").size());
      recorder.record(
          session, JsonCodec.object("processes", new JsonArray(), "browsers", new JsonArray()));
      assertFalse(
          server
              .classroomDatabase
              .query("SELECT observed_until FROM activity_records WHERE kind='PROCESS'")
              .get(0)
              .getAsJsonObject()
              .get("observed_until")
              .isJsonNull());
      long violation =
          server.classroom.flagViolation(teacher, session, "Đánh dấu để kiểm tra", now);
      byte[] bytes;
      try (var output = new ByteArrayOutputStream()) {
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", output);
        bytes = output.toByteArray();
      }
      var screenshot =
          JsonCodec.object("base64", Base64.getEncoder().encodeToString(bytes), "capturedAt", now);
      var evidence =
          new EvidenceService(server.classroomDatabase, server.classroom, temp.resolve("evidence"));
      long id = evidence.save(teacher, violation, session, screenshot);
      assertEquals(screenshot.get("base64"), evidence.read(teacher, id).get("base64"));
      assertThrows(SecurityException.class, () -> evidence.read(other, id));
      assertThrows(
          SecurityException.class,
          () -> evidence.save(teacher, violation, session + 1, screenshot));
      String filename =
          server
              .classroomDatabase
              .query("SELECT path FROM evidence WHERE id=?", id)
              .get(0)
              .getAsJsonObject()
              .get("path")
              .getAsString();
      Files.writeString(temp.resolve("evidence").resolve(filename), "modified");
      assertThrows(IllegalStateException.class, () -> evidence.read(teacher, id));
      long expired = evidence.save(teacher, violation, session, screenshot);
      String expiredFile =
          server
              .classroomDatabase
              .query("SELECT path FROM evidence WHERE id=?", expired)
              .get(0)
              .getAsJsonObject()
              .get("path")
              .getAsString();
      server.classroomDatabase.execute(
          "UPDATE evidence SET saved_at=? WHERE id=?",
          System.currentTimeMillis() - 3 * 86400000L,
          expired);
      evidence.purgeExpired(1);
      assertFalse(Files.exists(temp.resolve("evidence").resolve(expiredFile)));
      assertTrue(Files.exists(temp.resolve("evidence").resolve(filename)));
      assertThrows(IllegalStateException.class, () -> evidence.read(teacher, expired));
    }
  }
}
