package com.lanmanager.server;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.extension.*;

/** Mỗi bài thử sở hữu một schema mới; tuyệt đối không dùng schema ứng dụng. */
public final class PostgresTestDatabase implements BeforeEachCallback, AfterEachCallback {
  private static final ThreadLocal<String> SCHEMA = new ThreadLocal<>();

  private static String setting(String key, String fallback) {
    String value = System.getenv(key);
    return value == null ? fallback : value;
  }

  private static String url() {
    return setting("TEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/lan_manager_test");
  }

  private static String user() {
    return setting("TEST_DB_USER", "lan_manager_test");
  }

  private static String password() {
    return setting("TEST_DB_PASSWORD", "");
  }

  public void beforeEach(ExtensionContext context) throws Exception {
    String schema = "lan_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url(), user(), password());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    SCHEMA.set(schema);
  }

  public static String properties() {
    String schema = SCHEMA.get();
    if (schema == null) throw new IllegalStateException("Thiếu PostgresTestDatabase extension");
    return "database.url="
        + url()
        + (url().contains("?") ? "&" : "?")
        + "currentSchema="
        + schema
        + "\ndatabase.user="
        + user()
        + "\ndatabase.password="
        + password()
        + "\n";
  }

  public void afterEach(ExtensionContext context) throws Exception {
    String schema = SCHEMA.get();
    SCHEMA.remove();
    if (schema == null || !schema.matches("lan_test_[a-f0-9]{32}")) return;
    try (var connection = DriverManager.getConnection(url(), user(), password());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
