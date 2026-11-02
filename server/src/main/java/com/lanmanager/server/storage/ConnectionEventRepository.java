package com.lanmanager.server.storage;

import java.sql.SQLException;
import java.util.*;

/** Lưu kết nối máy trên cùng PostgreSQL với nghiệp vụ lớp học. */
public final class ConnectionEventRepository implements AutoCloseable {
  private final ClassroomDatabase database;

  public ConnectionEventRepository(ClassroomDatabase database) {
    this.database = database;
  }

  public void append(String id, String name, String ip, String event, long at) throws SQLException {
    database.execute(
        "INSERT INTO connection_events(machine_id,machine_name,ip,event,at_epoch_ms)"
            + " VALUES(?,?,?,?,?)",
        id,
        name,
        ip,
        event,
        at);
  }

  public List<Map<String, Object>> history(String id) throws SQLException {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var item :
        database.query(
            "SELECT event,at_epoch_ms,ip FROM connection_events WHERE machine_id=? ORDER BY id DESC"
                + " LIMIT 500",
            id)) {
      var row = item.getAsJsonObject();
      result.add(
          Map.of(
              "event",
              row.get("event").getAsString(),
              "at",
              row.get("at_epoch_ms").getAsLong(),
              "ip",
              row.get("ip").getAsString()));
    }
    return result;
  }

  /** ServerBackend đóng kết nối dùng chung sau khi hoàn thành các tác vụ ghi. */
  public void close() {}
}
