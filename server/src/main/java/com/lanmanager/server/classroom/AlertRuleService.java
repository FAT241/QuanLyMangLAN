package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.net.URI;
import java.sql.SQLException;
import java.util.Locale;

public final class AlertRuleService {
  private final ClassroomDatabase database;
  private final ClassroomService classroom;

  public AlertRuleService(ClassroomDatabase database, ClassroomService classroom) {
    this.database = database;
    this.classroom = classroom;
  }

  public long add(User teacher, long classId, String kind, String pattern) throws SQLException {
    classroom.requireClassTeacher(teacher, classId);
    pattern = pattern.strip().toLowerCase(Locale.ROOT);
    if (!kind.equals("WEB") && !kind.equals("PROCESS"))
      throw new IllegalArgumentException("Loại quy tắc sai");
    if (!pattern.matches("[a-z0-9][a-z0-9._-]{0,199}"))
      throw new IllegalArgumentException(
          "Nhập tên miền hoặc tên tệp ứng dụng, không nhập URL đầy đủ");
    return database.insert(
        "INSERT INTO monitor_rules(class_id,kind,pattern) VALUES(?,?,?) ON"
            + " CONFLICT(class_id,kind,pattern) DO UPDATE SET enabled=1",
        classId,
        kind,
        pattern);
  }

  public void setEnabled(User teacher, long ruleId, boolean enabled) throws SQLException {
    var rows = database.query("SELECT class_id FROM monitor_rules WHERE id=?", ruleId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Không có quy tắc");
    classroom.requireClassTeacher(
        teacher, rows.get(0).getAsJsonObject().get("class_id").getAsLong());
    database.execute("UPDATE monitor_rules SET enabled=? WHERE id=?", enabled ? 1 : 0, ruleId);
  }

  public static void evaluate(ClassroomDatabase database, long sessionId) throws SQLException {
    for (var item :
        database.query(
            "SELECT m.* FROM monitor_rules m JOIN student_sessions s ON s.class_id=m.class_id WHERE"
                + " s.id=? AND m.enabled=1",
            sessionId)) {
      var rule = item.getAsJsonObject();
      long ruleId = rule.get("id").getAsLong();
      long lastId = 0;
      for (var row :
          database.query(
              "SELECT r.id,r.details FROM activity_records r WHERE r.student_session_id=? AND"
                  + " r.kind=? AND r.id>COALESCE((SELECT last_activity_id FROM rule_scan_offsets"
                  + " WHERE rule_id=? AND student_session_id=?),0) ORDER BY r.id LIMIT 2000",
              sessionId,
              rule.get("kind").getAsString(),
              ruleId,
              sessionId)) {
        var activity = row.getAsJsonObject();
        lastId = activity.get("id").getAsLong();
        if (matches(
            rule.get("kind").getAsString(),
            rule.get("pattern").getAsString(),
            activity.get("details").getAsString()))
          database.execute(
              "INSERT INTO rule_alerts(rule_id,activity_id,created_at) VALUES(?,?,?) ON"
                  + " CONFLICT(rule_id,activity_id) DO NOTHING",
              ruleId,
              activity.get("id").getAsLong(),
              System.currentTimeMillis());
      }
      if (lastId > 0)
        database.execute(
            "INSERT INTO rule_scan_offsets(rule_id,student_session_id,last_activity_id)"
                + " VALUES(?,?,?) ON CONFLICT(rule_id,student_session_id) DO UPDATE SET"
                + " last_activity_id=EXCLUDED.last_activity_id",
            ruleId,
            sessionId,
            lastId);
    }
  }

  static boolean matches(String kind, String pattern, String details) {
    try {
      var body = JsonCodec.parse(details.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (kind.equals("PROCESS")) return body.get("name").getAsString().equalsIgnoreCase(pattern);
      String host = URI.create(body.get("url").getAsString()).getHost();
      return host != null
          && (host.equalsIgnoreCase(pattern)
              || host.toLowerCase(Locale.ROOT).endsWith("." + pattern));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
