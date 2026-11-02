package com.lanmanager.server.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassroomReportExporterTest {
  @TempDir Path temp;

  @Test
  void exportsServerTotalsAndExamTimesWithoutDependingOnPreviewViolations() throws Exception {
    JsonArray students = new JsonArray();
    students.add(
        JsonCodec.object(
            "id",
            1,
            "username",
            "=1+1",
            "full_name",
            "Sinh viên, \"A\"",
            "machine_id",
            "pc",
            "signed_in_at",
            1700000000000L,
            "ended_at",
            1700000004000L,
            "end_reason",
            "SIGNED_OUT",
            "exam_started_at",
            1700000001000L,
            "exam_submitted_at",
            1700000003000L,
            "exam_closed_at",
            1700000003000L,
            "flag_count",
            250,
            "confirmed_count",
            210,
            "penalty_total",
            9));
    Path file = temp.resolve("report.csv");
    new ClassroomReportExporter().export(file, JsonCodec.object("students", students));
    String csv = Files.readString(file);
    assertEquals('\ufeff', csv.charAt(0));
    assertTrue(csv.contains("\"'=1+1\""));
    assertTrue(csv.contains("Sinh viên, \"\"A\"\""));
    assertTrue(csv.contains("\"250\",\"210\",\"9\""));
    assertTrue(csv.contains("Nộp bài"));
  }
}
