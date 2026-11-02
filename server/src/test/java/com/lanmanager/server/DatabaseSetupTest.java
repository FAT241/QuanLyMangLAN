package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.server.storage.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseSetupTest {
  @TempDir Path temp;

  private DatabaseSettings administrator() {
    return new DatabaseSettings(
        env(
            "TEST_SETUP_DB_URL",
            env("TEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/lan_manager_test")),
        env("TEST_SETUP_DB_USER", env("TEST_DB_USER", "lan_manager_test")),
        env("TEST_SETUP_DB_PASSWORD", env("TEST_DB_PASSWORD", "")));
  }

  private String env(String key, String fallback) {
    return System.getenv(key) == null ? fallback : System.getenv(key);
  }

  private DatabaseSetupService.SetupPlan plan() {
    URI address = URI.create(administrator().url().substring(5));
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    return new DatabaseSetupService.SetupPlan(
        address.getHost(),
        address.getPort() < 0 ? 5432 : address.getPort(),
        "lan_setup_" + suffix,
        "lan_role_" + suffix,
        "Mật'khẩu\\Postgres123");
  }

  @Test
  void createsFreshDatabaseWithoutReplacingExistingAndSavesConfiguration() throws Exception {
    var setup = new DatabaseSetupService();
    var plan = plan();
    try {
      setup.createDatabase(administrator(), plan);
      setup.initializeSchema(plan);
      assertThrows(IllegalStateException.class, () -> setup.createDatabase(administrator(), plan));
      try (var database = new ClassroomDatabase(plan.settings())) {
        assertEquals(5, database.query("SELECT * FROM classroom_migrations").size());
        assertTrue(database.query("SELECT * FROM v_student_sessions").isEmpty());
      }
      Path config = temp.resolve("server-local.properties");
      Files.writeString(config, "token=keep-token\noffline.seconds=12\n");
      setup.saveConfiguration(config, plan);
      var saved = new ApplicationConfig(config);
      assertEquals("keep-token", saved.text("token", ""));
      assertEquals(plan.password(), saved.text("database.password", ""));
      try (var connection = DatabaseSettings.from(saved).open()) {
        assertEquals(plan.username(), connection.getMetaData().getUserName());
      }
      assertFalse(plan.toString().contains(plan.password()));
    } finally {
      cleanup(plan);
    }
  }

  @Test
  void exportedPgAdminSqlWorksWithQuotesUnicodeAndBackslashes() throws Exception {
    var setup = new DatabaseSetupService();
    var plan = plan();
    try {
      var files = setup.exportSql(temp.resolve("sql"), plan);
      assertEquals(2, files.size());
      try (Connection connection = administrator().open();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(true);
        for (Path file : files) statement.execute(Files.readString(file));
      }
      setup.initializeSchema(plan);
      try (var connection = plan.settings().open();
          var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT current_database()")) {
        assertTrue(rows.next());
        assertEquals(plan.database(), rows.getString(1));
      }
      assertThrows(
          java.nio.file.FileAlreadyExistsException.class,
          () -> setup.exportSql(temp.resolve("sql"), plan));
      assertTrue(Files.isRegularFile(files.get(1)));
    } finally {
      cleanup(plan);
    }
  }

  private void cleanup(DatabaseSetupService.SetupPlan plan) throws SQLException {
    if (!plan.database().matches("lan_setup_[a-f0-9]{20}")
        || !plan.username().matches("lan_role_[a-f0-9]{20}"))
      throw new IllegalArgumentException("Không phải database do bài thử tạo");
    try (Connection connection = administrator().open();
        Statement sql = connection.createStatement()) {
      connection.setAutoCommit(true);
      sql.execute("DROP DATABASE IF EXISTS \"" + plan.database() + "\" WITH (FORCE)");
      sql.execute("DROP ROLE IF EXISTS \"" + plan.username() + "\"");
    }
  }
}
