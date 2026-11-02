package com.lanmanager.server.ui;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class ClassroomReportExporter {
  public void export(Path path, JsonObject report) throws IOException {
    try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      writer.write(
          "Tài khoản,Họ tên,Mã phiên,Máy,Giờ vào,Giờ kết thúc,Bắt đầu làm bài,Nộp bài,Kết thúc lượt"
              + " thi,Lý do,Số đánh dấu,Số xác nhận,Tổng điểm trừ\r\n");
      for (var item : report.getAsJsonArray("students")) {
        var student = item.getAsJsonObject();
        long id = student.get("id").getAsLong();
        long flags = student.get("flag_count").getAsLong(),
            confirmed = student.get("confirmed_count").getAsLong(),
            points = student.get("penalty_total").getAsLong();
        String[] values = {
          student.get("username").getAsString(),
          student.get("full_name").getAsString(),
          "" + id,
          student.get("machine_id").getAsString(),
          time(student.get("signed_in_at")),
          time(student.get("ended_at")),
          time(student.get("exam_started_at")),
          time(student.get("exam_submitted_at")),
          time(student.get("exam_closed_at")),
          student.get("end_reason").isJsonNull()
              ? "Đang tham gia"
              : student.get("end_reason").getAsString(),
          "" + flags,
          "" + confirmed,
          "" + points
        };
        for (int i = 0; i < values.length; i++) {
          if (i > 0) writer.write(',');
          writer.write(cell(values[i]));
        }
        writer.write("\r\n");
      }
    }
  }

  private String time(JsonElement value) {
    return value == null || value.isJsonNull()
        ? ""
        : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.ofEpochMilli(value.getAsLong()));
  }

  private String cell(String value) {
    String clean = value.stripLeading();
    if (!clean.isEmpty() && "=+-@".indexOf(clean.charAt(0)) >= 0
        || value.startsWith("\t")
        || value.startsWith("\r")
        || value.startsWith("\n")) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
