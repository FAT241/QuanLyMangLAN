package com.lanmanager.common.ui;

import com.google.gson.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.swing.table.AbstractTableModel;

/** Bảng đọc dữ liệu, giữ mã bản ghi để thao tác không phụ thuộc thứ tự dòng. */
public final class JsonTableModel extends AbstractTableModel {
  private final String[] fields, labels;
  private JsonArray rows = new JsonArray();

  public JsonTableModel(String[] fields, String[] labels) {
    this.fields = fields;
    this.labels = labels;
  }

  public void setRows(JsonArray rows) {
    this.rows = rows;
    fireTableDataChanged();
  }

  public JsonObject row(int index) {
    if (index < 0 || index >= rows.size())
      throw new IllegalArgumentException("Chọn một dòng trong bảng");
    return rows.get(index).getAsJsonObject();
  }

  public int getRowCount() {
    return rows.size();
  }

  public int getColumnCount() {
    return fields.length;
  }

  public String getColumnName(int column) {
    return labels[column];
  }

  public Object getValueAt(int row, int column) {
    String field = fields[column];
    JsonElement value = row(row).get(field);
    if (value == null || value.isJsonNull()) return "—";
    if (field.endsWith("_at")
        || field.equals("observed_until")
        || field.equals("at")
        || field.equals("visitTime")
        || field.equals("startedAt")) {
      try {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.ofEpochMilli(value.getAsLong()));
      } catch (Exception ignored) {
        return value.toString();
      }
    }
    return value.isJsonPrimitive() ? translate(value.getAsString()) : value.toString();
  }

  private String translate(String text) {
    return switch (text) {
      case "TEACHER" -> "Giáo viên";
      case "STUDENT" -> "Sinh viên";
      case "SUBMITTED" -> "Đã nộp bài";
      case "ACCOUNT_DISABLED" -> "Tài khoản bị khóa";
      case "PASSWORD_RESET" -> "Đặt lại mật khẩu";
      case "PENDING" -> "Đang chờ";
      case "COMPLETED" -> "Đã nhận kết quả";
      case "FAILED" -> "Không thành công";
      case "DISABLED" -> "Máy con đã tắt tính năng này";
      case "BROWSER_NOT_FOUND" -> "Chưa có lịch sử ở hồ sơ trình duyệt";
      case "HISTORY_CHROME" -> "Đọc lịch sử Chrome";
      case "HISTORY_EDGE" -> "Đọc lịch sử Edge";
      case "CLASS" -> "Buổi học";
      case "EXAM" -> "Kiểm tra";
      case "PROCESS" -> "Ứng dụng";
      case "WEB" -> "Truy cập web";
      case "FLAGGED" -> "Đã đánh dấu";
      case "CONFIRMED" -> "Đã xác nhận";
      case "DISMISSED" -> "Đã bỏ qua";
      case "SIGNED_OUT" -> "Đăng xuất";
      case "CONNECTION_LOST" -> "Mất liên lạc";
      case "CLASS_ENDED" -> "Kết thúc buổi";
      case "SERVER_INTERRUPTED" -> "Máy chủ gián đoạn";
      default -> text;
    };
  }
}
