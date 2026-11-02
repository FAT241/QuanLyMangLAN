package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.sql.SQLException;

/** Phân trang bằng khóa bản ghi; không bỏ mất lịch sử sau 1000 mục. */
public final class HistoryQueryService {
  private final ClassroomDatabase database;
  private final ClassroomService classroom;

  public HistoryQueryService(ClassroomDatabase database, ClassroomService classroom) {
    this.database = database;
    this.classroom = classroom;
  }

  public JsonObject page(User teacher, JsonObject request) throws SQLException {
    long classId = JsonCodec.requiredLong(request, "classId", 1, Long.MAX_VALUE);
    classroom.requireClassTeacher(teacher, classId);
    long sessionId = number(request, "studentSessionId", 0),
        from = number(request, "fromAt", 0),
        to = number(request, "toAt", Long.MAX_VALUE),
        before = number(request, "beforeId", Long.MAX_VALUE),
        snapshot = number(request, "snapshotId", Long.MAX_VALUE);
    if (from > to) throw new IllegalArgumentException("Giờ bắt đầu phải trước giờ kết thúc");
    String category =
        request.has("category") ? JsonCodec.requiredText(request, "category", 20) : "ACTIVITY";
    String kind = request.has("kind") ? JsonCodec.requiredText(request, "kind", 20) : "ALL";
    String keyword = request.has("keyword") ? request.get("keyword").getAsString() : "";
    if (keyword.length() > 200) throw new IllegalArgumentException("Từ khóa quá dài");
    String source =
        switch (category) {
          case "ACTIVITY" ->
              "SELECT r.id,r.student_session_id,r.kind,r.details AS description,r.source_at AS"
                  + " first_at,r.last_observed_at AS last_at,r.observed_until AS ended_at,0 AS"
                  + " points FROM activity_records r";
          case "ERROR" ->
              "SELECT e.id,e.student_session_id,e.code AS kind,e.message AS"
                  + " description,e.first_seen_at AS first_at,e.last_seen_at AS"
                  + " last_at,e.resolved_at AS ended_at,0 AS points FROM system_errors e";
          case "VIOLATION" ->
              "SELECT v.id,v.student_session_id,v.status AS kind,v.details || ' | ' || v.note AS"
                  + " description,v.occurred_at AS first_at,v.updated_at AS last_at,NULL::BIGINT AS"
                  + " ended_at,v.penalty_points AS points FROM violations v";
          case "ALERT" ->
              "SELECT a.id,r.student_session_id,r.kind,'Quy tắc: ' || m.pattern || ' | ' ||"
                  + " r.details AS description,a.created_at AS first_at,a.created_at AS"
                  + " last_at,NULL::BIGINT AS ended_at,0 AS points FROM rule_alerts a JOIN"
                  + " activity_records r ON r.id=a.activity_id JOIN monitor_rules m ON"
                  + " m.id=a.rule_id";
          default -> throw new IllegalArgumentException("Nhóm lịch sử không hợp lệ");
        };
    String query =
        "SELECT h.*,s.machine_id,a.username,a.full_name FROM ("
            + source
            + ") h JOIN student_sessions s ON s.id=h.student_session_id JOIN accounts a ON"
            + " a.id=s.account_id WHERE s.class_id=? AND (?=0 OR s.id=?) AND h.id<? AND h.id<=? AND"
            + " h.first_at<=? AND COALESCE(h.ended_at,h.last_at,h.first_at)>=? AND (?='ALL' OR"
            + " h.kind=?) AND position(lower(?) in lower(h.description))>0 ORDER BY h.id DESC LIMIT"
            + " 101";
    JsonArray rows =
        database.query(
            query, classId, sessionId, sessionId, before, snapshot, to, from, kind, kind, keyword);
    boolean more = rows.size() > 100;
    if (more) rows.remove(100);
    if (snapshot == Long.MAX_VALUE && !rows.isEmpty())
      snapshot = rows.get(0).getAsJsonObject().get("id").getAsLong();
    while (JsonCodec.bytes(rows).length > 800000 && rows.size() > 1) {
      rows.remove(rows.size() - 1);
      more = true;
    }
    long next =
        rows.isEmpty() ? 0 : rows.get(rows.size() - 1).getAsJsonObject().get("id").getAsLong();
    return JsonCodec.object(
        "rows", rows, "hasMore", more, "nextBeforeId", next, "snapshotId", snapshot);
  }

  private long number(JsonObject body, String key, long fallback) {
    return body.has(key) ? JsonCodec.requiredLong(body, key, 0, Long.MAX_VALUE) : fallback;
  }
}
