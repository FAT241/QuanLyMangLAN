package com.lanmanager.server.ui;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Lựa chọn buổi của giáo viên, mặc định lọc các buổi đã kết thúc. */
record TeacherClassChoice(long id, String label, boolean exam) {
  static List<TeacherClassChoice> visibleClasses(JsonArray rows, boolean includeEnded) {
    List<TeacherClassChoice> choices = new ArrayList<>();
    for (JsonElement item : rows) {
      JsonObject row = item.getAsJsonObject();
      JsonElement end = row.get("ended_at");
      boolean ended = end != null && !end.isJsonNull();
      if (ended && !includeEnded) continue;
      boolean exam = row.get("type").getAsString().equals("EXAM");
      choices.add(
          new TeacherClassChoice(
              row.get("id").getAsLong(),
              row.get("name").getAsString()
                  + " · "
                  + (exam ? "Kiểm tra" : "Buổi học")
                  + (ended ? " đã kết thúc" : " đang mở"),
              exam));
    }
    return choices;
  }

  @Override
  public String toString() {
    return label;
  }
}
