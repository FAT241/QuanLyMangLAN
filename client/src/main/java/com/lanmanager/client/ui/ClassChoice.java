package com.lanmanager.client.ui;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.util.ArrayList;
import java.util.List;

/** Buổi đang mở để sinh viên chọn, không chứa thông tin đăng nhập. */
record ClassChoice(long id, String name, boolean exam) {
  /** Server cũ chỉ trả buổi đang mở và có thể bỏ trường ended_at. */
  static List<ClassChoice> openClasses(JsonArray rows) {
    if (rows == null)
      throw new IllegalArgumentException("Chưa nhận được danh sách buổi học. Hãy tải lại.");
    List<ClassChoice> choices = new ArrayList<>();
    for (JsonElement item : rows) {
      if (!item.isJsonObject())
        throw new IllegalArgumentException("Dữ liệu buổi học chưa đầy đủ. Hãy tải lại danh sách.");
      JsonObject row = item.getAsJsonObject();
      JsonElement endedAt = row.get("ended_at");
      if (endedAt != null && !endedAt.isJsonNull()) continue;
      try {
        long id = JsonCodec.requiredLong(row, "id", 1, Long.MAX_VALUE);
        String name = JsonCodec.requiredText(row, "name", 200);
        String type = JsonCodec.requiredText(row, "type", 20);
        if (!type.equals("CLASS") && !type.equals("EXAM"))
          throw new IllegalArgumentException("Loại buổi học không hợp lệ");
        choices.add(new ClassChoice(id, name, type.equals("EXAM")));
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException(
            "Dữ liệu buổi học chưa đầy đủ. Hãy tải lại danh sách.", error);
      }
    }
    return choices;
  }

  String kind() {
    return exam ? "Kiểm tra" : "Buổi học";
  }

  @Override
  public String toString() {
    return name;
  }
}
