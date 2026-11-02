package com.lanmanager.server.storage;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/** Chuẩn bị database mới, không đổi mật khẩu hay ghi đè database đã có. */
public final class DatabaseSetupService {
  public record SetupPlan(
      String host, int port, String database, String username, String password) {
    public SetupPlan {
      if (host == null || !host.matches("[A-Za-z0-9.:-]{1,200}") || port < 1 || port > 65535)
        throw new IllegalArgumentException("Địa chỉ hoặc cổng PostgreSQL không hợp lệ");
      if (database == null
          || !database.matches("[a-z_][a-z0-9_]{0,62}")
          || username == null
          || !username.matches("[a-z_][a-z0-9_]{0,62}"))
        throw new IllegalArgumentException(
            "Tên database/tài khoản dùng chữ thường, số, gạch dưới; tối đa 63 ký tự");
      if (password == null
          || password.length() < 10
          || password.length() > 200
          || password.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Mật khẩu database cần 10–200 ký tự");
    }

    public DatabaseSettings settings() {
      String address = host.contains(":") ? "[" + host + "]" : host;
      return new DatabaseSettings(
          "jdbc:postgresql://" + address + ":" + port + "/" + database, username, password);
    }

    @Override
    public String toString() {
      return "SetupPlan[database=" + database + ", username=" + username + ", password=đã ẩn]";
    }
  }

  public String randomPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String createRoleSql(SetupPlan plan) {
    return "CREATE ROLE "
        + identifier(plan.username())
        + " LOGIN PASSWORD "
        + literal(plan.password())
        + ";\n";
  }

  public String createDatabaseSql(SetupPlan plan) {
    return "CREATE DATABASE "
        + identifier(plan.database())
        + " OWNER "
        + identifier(plan.username())
        + ";\n";
  }

  public void createDatabase(DatabaseSettings administrator, SetupPlan plan) throws SQLException {
    try (Connection connection = administrator.open()) {
      connection.setAutoCommit(true);
      if (exists(connection, "SELECT 1 FROM pg_database WHERE datname=?", plan.database()))
        throw new IllegalStateException(
            "Database đã tồn tại; chọn tên mới hoặc chỉ kiểm tra kết nối");
      if (exists(connection, "SELECT 1 FROM pg_roles WHERE rolname=?", plan.username()))
        throw new IllegalStateException(
            "Tài khoản PostgreSQL đã tồn tại; chọn tên mới, không tự thay đổi tài khoản cũ");
      try (Statement statement = connection.createStatement()) {
        statement.execute(createRoleSql(plan));
        try {
          statement.execute(createDatabaseSql(plan));
        } catch (SQLException e) {
          try {
            statement.execute("DROP ROLE " + identifier(plan.username()));
          } catch (SQLException cleanup) {
            e.addSuppressed(cleanup);
          }
          throw e;
        }
      }
    }
  }

  public void initializeSchema(SetupPlan plan) throws Exception {
    try (var database = new ClassroomDatabase(plan.settings())) {
      database.query("SELECT current_database()");
    }
  }

  public void saveConfiguration(Path configFile, SetupPlan plan) throws IOException {
    Properties properties = new Properties();
    Path sample = configFile.resolveSibling("server.properties");
    Path source = Files.isRegularFile(configFile) ? configFile : sample;
    if (Files.isRegularFile(source))
      try (Reader reader = Files.newBufferedReader(source)) {
        properties.load(reader);
      }
    properties.setProperty("database.url", plan.settings().url());
    properties.setProperty("database.user", plan.username());
    properties.setProperty("database.password", plan.password());
    Files.createDirectories(configFile.toAbsolutePath().getParent());
    Path temporary =
        Files.createTempFile(configFile.toAbsolutePath().getParent(), "database-config-", ".tmp");
    try {
      try (Writer writer = Files.newBufferedWriter(temporary)) {
        properties.store(writer, "Cau hinh PostgreSQL rieng cho project");
      }
      Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public List<Path> exportSql(Path directory, SetupPlan plan) throws IOException {
    Files.createDirectories(directory);
    Path role = directory.resolve("01_create_role-local.sql"),
        database = directory.resolve("02_create_database-local.sql");
    Files.writeString(
        role,
        "-- Mo Query Tool bang ket noi pgAdmin da dang nhap. Chay rieng file nay.\n"
            + createRoleSql(plan),
        StandardOpenOption.CREATE_NEW);
    try {
      Files.writeString(
          database,
          "-- Chay rieng file nay voi Auto-commit bat. Khong chay chung trong transaction.\n"
              + createDatabaseSql(plan),
          StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      Files.deleteIfExists(role);
      throw e;
    }
    return List.of(role, database);
  }

  private boolean exists(Connection connection, String query, String value) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(query)) {
      statement.setString(1, value);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private String identifier(String value) {
    return "\"" + value + "\"";
  }

  private String literal(String value) {
    return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
  }
}
