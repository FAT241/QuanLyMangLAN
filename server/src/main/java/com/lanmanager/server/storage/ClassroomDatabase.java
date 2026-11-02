package com.lanmanager.server.storage;

import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;

/** Một kết nối riêng cho nghiệp vụ lớp học, câu lệnh luôn dùng tham số. */
public final class ClassroomDatabase implements AutoCloseable {
  private final Connection connection;

  public ClassroomDatabase(DatabaseSettings settings) throws Exception {
    connection = settings.open();
    try {
      migrate();
    } catch (Exception e) {
      connection.close();
      throw e;
    }
  }

  private void migrate() throws Exception {
    transaction(
        () -> {
          try (java.sql.Statement statement = connection.createStatement()) {
            // Một tiến trình thay đổi cấu trúc tại một thời điểm trong cùng schema.
            statement.execute("SELECT pg_advisory_xact_lock(hashtext(current_schema()))");
          }
          execute("CREATE TABLE IF NOT EXISTS classroom_migrations(version INTEGER PRIMARY KEY)");
          String[] files = {
            "V001__classroom.sql",
            "V002__classroom.sql",
            "V003__classroom_features.sql",
            "V004__evidence_retention.sql",
            "V005__report_views.sql"
          };
          for (int i = 0; i < files.length; i++) {
            int version = i + 1;
            if (!query("SELECT version FROM classroom_migrations WHERE version=?", version)
                .isEmpty()) continue;
            try (var stream =
                ClassroomDatabase.class.getResourceAsStream("/db/migration/" + files[i])) {
              if (stream == null)
                throw new java.io.IOException("Thiếu tệp thay đổi cấu trúc: " + files[i]);
              String sql =
                  new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
              for (String command : sql.split(";")) if (!command.isBlank()) execute(command);
              execute("INSERT INTO classroom_migrations(version) VALUES(?)", version);
            } catch (java.io.IOException e) {
              throw new SQLException("Không đọc được cấu trúc cơ sở dữ liệu", e);
            }
          }
        });
  }

  public synchronized int execute(String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      return statement.executeUpdate();
    }
  }

  public synchronized void acquireServerLock() throws SQLException {
    var row =
        query(
                "SELECT"
                    + " pg_try_advisory_lock(hashtext(current_database()),hashtext(current_schema()))"
                    + " AS locked")
            .get(0)
            .getAsJsonObject();
    if (!row.get("locked").getAsString().equals("true"))
      throw new IllegalStateException("Đã có máy chủ khác dùng cơ sở dữ liệu này");
  }

  public synchronized JsonArray query(String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      JsonArray rows = new JsonArray();
      try (ResultSet result = statement.executeQuery()) {
        ResultSetMetaData meta = result.getMetaData();
        while (result.next()) {
          JsonObject row = new JsonObject();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = result.getObject(i);
            String name = meta.getColumnLabel(i);
            if (value == null) row.add(name, JsonNull.INSTANCE);
            else if (value instanceof Number number) row.addProperty(name, number);
            else row.addProperty(name, value.toString());
          }
          rows.add(row);
        }
      }
      return rows;
    }
  }

  public synchronized long insert(String sql, Object... values) throws SQLException {
    return query(sql + " RETURNING id", values).get(0).getAsJsonObject().get("id").getAsLong();
  }

  /** Giữ thay đổi nghiệp vụ và nhật ký trong cùng giao dịch. */
  public synchronized void transaction(SqlWork work) throws SQLException {
    connection.setAutoCommit(false);
    try {
      work.run();
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  @FunctionalInterface
  public interface SqlWork {
    void run() throws SQLException;
  }

  private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
  }

  public synchronized void close() throws SQLException {
    connection.close();
  }
}
