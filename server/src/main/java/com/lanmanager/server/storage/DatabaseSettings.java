package com.lanmanager.server.storage;

import com.lanmanager.common.config.ApplicationConfig;
import java.sql.*;
import java.util.Properties;

/** Thông tin kết nối PostgreSQL, không đưa mật khẩu vào URL hoặc nhật ký. */
public record DatabaseSettings(String url, String username, String password) {
  public static DatabaseSettings from(ApplicationConfig config) {
    return new DatabaseSettings(
        config.text("database.url", "jdbc:postgresql://127.0.0.1:5432/lan_manager"),
        environment("LAN_DB_USER", config.text("database.user", "lan_manager")),
        environment("LAN_DB_PASSWORD", config.text("database.password", "")));
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null ? fallback : value;
  }

  public Connection open() throws SQLException {
    if (!url.startsWith("jdbc:postgresql:"))
      throw new IllegalArgumentException("Máy chủ dùng PostgreSQL; kiểm tra database.url");
    Properties properties = new Properties();
    properties.setProperty("user", username);
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("socketTimeout", "30");
    properties.setProperty("ApplicationName", "QuanLyPhongMay");
    return DriverManager.getConnection(url, properties);
  }

  @Override
  public String toString() {
    return "DatabaseSettings[PostgreSQL, thông tin đăng nhập đã ẩn]";
  }
}
