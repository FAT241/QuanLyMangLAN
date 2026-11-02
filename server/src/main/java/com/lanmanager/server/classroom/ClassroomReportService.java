package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.sql.SQLException;

/** Báo cáo xem nhanh có giới hạn, xuất tổng hợp lấy từng trang đầy đủ. */
public final class ClassroomReportService {
  private final ClassroomDatabase database;
  private final ClassroomService classroom;
  private static final String STUDENTS =
      "SELECT s.*,a.username,a.full_name,e.started_at AS exam_started_at,e.submitted_at AS"
          + " exam_submitted_at,e.closed_at AS exam_closed_at,e.end_reason AS"
          + " exam_end_reason,(SELECT COUNT(*) FROM violations v WHERE v.student_session_id=s.id)"
          + " AS flag_count,(SELECT COUNT(*) FROM violations v WHERE v.student_session_id=s.id AND"
          + " v.status='CONFIRMED') AS confirmed_count,(SELECT COALESCE(SUM(penalty_points),0) FROM"
          + " violations v WHERE v.student_session_id=s.id AND v.status='CONFIRMED') AS"
          + " penalty_total FROM student_sessions s JOIN accounts a ON a.id=s.account_id LEFT JOIN"
          + " exam_attempts e ON e.class_id=s.class_id AND e.account_id=s.account_id WHERE"
          + " s.class_id=?";

  public ClassroomReportService(ClassroomDatabase database, ClassroomService classroom) {
    this.database = database;
    this.classroom = classroom;
  }

  public JsonObject studentsPage(User teacher, long classId, long beforeId) throws SQLException {
    classroom.requireClassTeacher(teacher, classId);
    JsonArray rows =
        database.query(STUDENTS + " AND s.id<? ORDER BY s.id DESC LIMIT 101", classId, beforeId);
    boolean more = rows.size() > 100;
    if (more) rows.remove(100);
    return JsonCodec.object(
        "rows",
        rows,
        "hasMore",
        more,
        "nextBeforeId",
        rows.isEmpty() ? 0 : rows.get(rows.size() - 1).getAsJsonObject().get("id").getAsLong());
  }

  public JsonObject report(User teacher, long classId) throws SQLException {
    classroom.requireClassTeacher(teacher, classId);
    JsonObject report =
        JsonCodec.object(
            "students", database.query(STUDENTS + " ORDER BY s.id DESC LIMIT 200", classId),
            "activities",
                database.query(
                    "SELECT r.* FROM activity_records r JOIN student_sessions s ON"
                        + " s.id=r.student_session_id WHERE s.class_id=? ORDER BY r.id DESC LIMIT"
                        + " 200",
                    classId),
            "violations",
                database.query(
                    "SELECT v.* FROM violations v JOIN student_sessions s ON"
                        + " s.id=v.student_session_id WHERE s.class_id=? ORDER BY v.id DESC LIMIT"
                        + " 200",
                    classId),
            "errors",
                database.query(
                    "SELECT e.* FROM system_errors e JOIN student_sessions s ON"
                        + " s.id=e.student_session_id WHERE s.class_id=? ORDER BY e.id DESC LIMIT"
                        + " 200",
                    classId),
            "actions",
                database.query(
                    "SELECT t.* FROM teacher_actions t JOIN violations v ON v.id=t.violation_id"
                        + " JOIN student_sessions s ON s.id=v.student_session_id WHERE s.class_id=?"
                        + " ORDER BY t.id DESC LIMIT 200",
                    classId),
            "evidence",
                database.query(
                    "SELECT e.* FROM evidence e JOIN violations v ON v.id=e.violation_id JOIN"
                        + " student_sessions s ON s.id=v.student_session_id WHERE s.class_id=?"
                        + " ORDER BY e.id DESC LIMIT 200",
                    classId),
            "controls",
                database.query(
                    "SELECT a.* FROM control_actions a JOIN student_sessions s ON"
                        + " s.id=a.student_session_id WHERE s.class_id=? ORDER BY a.id DESC LIMIT"
                        + " 200",
                    classId),
            "rules",
                database.query("SELECT * FROM monitor_rules WHERE class_id=? ORDER BY id", classId),
            "alerts",
                database.query(
                    "SELECT w.*,r.student_session_id,r.kind,r.details,m.pattern FROM rule_alerts w"
                        + " JOIN activity_records r ON r.id=w.activity_id JOIN student_sessions s"
                        + " ON s.id=r.student_session_id JOIN monitor_rules m ON m.id=w.rule_id"
                        + " WHERE s.class_id=? ORDER BY w.id DESC LIMIT 200",
                    classId));
    boolean trimmed = false;
    while (JsonCodec.bytes(report).length > 850000) {
      String biggest = null;
      int size = 0;
      for (String key :
          new String[] {
            "activities", "violations", "errors", "actions", "evidence", "controls", "alerts"
          }) {
        JsonArray array = report.getAsJsonArray(key);
        int bytes = JsonCodec.bytes(array).length;
        if (!array.isEmpty() && bytes > size) {
          biggest = key;
          size = bytes;
        }
      }
      if (biggest == null) break;
      JsonArray array = report.getAsJsonArray(biggest);
      array.remove(array.size() - 1);
      trimmed = true;
    }
    report.addProperty("previewLimit", 200);
    report.addProperty("trimmedBySize", trimmed);
    return report;
  }
}
