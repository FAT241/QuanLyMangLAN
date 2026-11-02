package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.sql.SQLException;
import java.util.*;

/** Ghi dữ liệu do Agent thu thập, không tự suy diễn hoạt động thành vi phạm. */
public final class ActivityRecorder {
  private final ClassroomDatabase database;

  public ActivityRecorder(ClassroomDatabase database) {
    this.database = database;
  }

  public void record(long sessionId, JsonObject payload) throws SQLException {
    long now = System.currentTimeMillis();
    database.transaction(
        () -> {
          var sessions =
              database.query(
                  "SELECT signed_in_at FROM student_sessions WHERE id=? AND ended_at IS NULL",
                  sessionId);
          if (sessions.isEmpty()) return;
          long start = sessions.get(0).getAsJsonObject().get("signed_in_at").getAsLong();
          Set<String> present = new HashSet<>();
          JsonArray processes = payload.getAsJsonArray("processes");
          if (processes == null || processes.size() > 500)
            throw new IllegalArgumentException("Dữ liệu tiến trình không hợp lệ");
          for (var item : processes) {
            var p = item.getAsJsonObject();
            String key =
                "process:"
                    + JsonCodec.requiredLong(p, "pid", 1, Long.MAX_VALUE)
                    + ":"
                    + (p.get("startedAt").isJsonNull() ? 0 : p.get("startedAt").getAsLong());
            JsonCodec.requiredText(p, "name", 300);
            present.add(key);
            var open =
                database.query(
                    "SELECT id FROM activity_records WHERE student_session_id=? AND kind='PROCESS'"
                        + " AND source_key LIKE ? AND observed_until IS NULL",
                    sessionId,
                    key + ":%");
            if (open.isEmpty())
              database.execute(
                  "INSERT INTO"
                      + " activity_records(student_session_id,kind,source_key,details,source_at,observed_at,last_observed_at)"
                      + " VALUES(?,'PROCESS',?,?,?,?,?)",
                  sessionId,
                  key + ":" + now,
                  p.toString(),
                  now,
                  now,
                  now);
            else
              database.execute(
                  "UPDATE activity_records SET last_observed_at=? WHERE id=?",
                  now,
                  open.get(0).getAsJsonObject().get("id").getAsLong());
          }
          for (var item :
              database.query(
                  "SELECT id,source_key FROM activity_records WHERE student_session_id=? AND"
                      + " kind='PROCESS' AND observed_until IS NULL",
                  sessionId)) {
            var row = item.getAsJsonObject();
            String key = row.get("source_key").getAsString();
            key = key.substring(0, key.lastIndexOf(':'));
            if (!present.contains(key)
                && (!payload.has("processesTruncated")
                    || !payload.get("processesTruncated").getAsBoolean()))
              database.execute(
                  "UPDATE activity_records SET observed_until=? WHERE id=?",
                  now,
                  row.get("id").getAsLong());
          }
          for (var browserItem : payload.getAsJsonArray("browsers")) {
            var browser = browserItem.getAsJsonObject();
            String name = JsonCodec.requiredText(browser, "browser", 10);
            if (browser.has("error")) {
              recordError(
                  sessionId,
                  "HISTORY_" + name.toUpperCase(Locale.ROOT),
                  browser.get("error").getAsString(),
                  false);
              continue;
            }
            recordError(
                sessionId,
                "HISTORY_" + name.toUpperCase(Locale.ROOT),
                "Đã đọc lại được lịch sử",
                true);
            JsonArray entries = browser.getAsJsonArray("entries");
            if (entries.size() > 500) throw new IllegalArgumentException("Quá nhiều lượt truy cập");
            for (var entryItem : entries) {
              var entry = entryItem.getAsJsonObject();
              long at = JsonCodec.requiredLong(entry, "visitTime", 0, now + 300000);
              if (at < start) continue;
              JsonCodec.requiredText(entry, "url", 12000);
              database.execute(
                  "INSERT INTO"
                      + " activity_records(student_session_id,kind,source_key,details,source_at,observed_at,last_observed_at)"
                      + " VALUES(?,'WEB',?,?,?,?,?) ON CONFLICT(student_session_id,kind,source_key)"
                      + " DO NOTHING",
                  sessionId,
                  name + ":" + JsonCodec.requiredLong(entry, "visitId", 1, Long.MAX_VALUE),
                  entry.toString(),
                  at,
                  now,
                  now);
            }
          }
          AlertRuleService.evaluate(database, sessionId);
        });
  }

  public void recordError(long sessionId, String code, String message, boolean resolved)
      throws SQLException {
    synchronized (database) {
      long now = System.currentTimeMillis();
      if (resolved)
        database.execute(
            "UPDATE system_errors SET resolved_at=? WHERE student_session_id=? AND code=? AND"
                + " resolved_at IS NULL",
            now,
            sessionId,
            code);
      else if (database.execute(
              "UPDATE system_errors SET"
                  + " last_seen_at=?,occurrence_count=occurrence_count+1,message=? WHERE"
                  + " student_session_id=? AND code=? AND resolved_at IS NULL",
              now,
              message,
              sessionId,
              code)
          == 0)
        database.execute(
            "INSERT INTO system_errors(student_session_id,code,message,first_seen_at,last_seen_at)"
                + " VALUES(?,?,?,?,?)",
            sessionId,
            code,
            message,
            now,
            now);
    }
  }

  public void closeFinishedIntervals() throws SQLException {
    database.execute(
        "UPDATE activity_records SET observed_until=(SELECT ended_at FROM student_sessions WHERE"
            + " id=student_session_id) WHERE kind='PROCESS' AND observed_until IS NULL AND"
            + " student_session_id IN (SELECT id FROM student_sessions WHERE ended_at IS NOT"
            + " NULL)");
  }
}
