package com.lanmanager.common.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ApplicationConfig {
  private final Properties values = new Properties();
  private final Path path;

  public static Path prepareClientConfig() throws IOException {
    Path example = findProjectConfig("client.properties");
    Path local = example.resolveSibling("client-local.properties");
    if (!Files.exists(local)) {
      Files.createDirectories(local.toAbsolutePath().getParent());
      if (Files.exists(example)) Files.copy(example, local);
      else Files.createFile(local);
    }
    return local;
  }

  /** Tìm cấu hình từ mô-đun IDE lên thư mục dự án, không phụ thuộc chỗ bấm Run. */
  public static Path findProjectConfig(String filename) {
    String application = filename.startsWith("client") ? "client" : "server";
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("config").resolve(filename);
      if (Files.isRegularFile(candidate)) return candidate;
      candidate = directory.resolve(application).resolve("config").resolve(filename);
      if (Files.isRegularFile(candidate)) return candidate;
      directory = directory.getParent();
    }
    return Path.of(application, "config", filename);
  }

  public static Path serverConfigPath() {
    Path example = findProjectConfig("server.properties");
    Path local = example.resolveSibling("server-local.properties");
    return Files.isRegularFile(local) ? local : example;
  }

  public ApplicationConfig(Path path) throws IOException {
    this.path = path;
    if (Files.exists(path))
      try (Reader reader = Files.newBufferedReader(path)) {
        values.load(reader);
      }
  }

  public String text(String key, String fallback) {
    return values.getProperty(key, fallback);
  }

  /** Đường dẫn dữ liệu thuộc ứng dụng, không phụ thuộc working directory của IDE. */
  public Path resolvePath(String key, String fallback) {
    Path value = Path.of(text(key, fallback));
    if (value.isAbsolute()) return value;
    Path parent = path.toAbsolutePath().normalize().getParent();
    Path base =
        parent != null && parent.getFileName().toString().equals("config")
            ? parent.getParent()
            : parent;
    return (base == null ? Path.of("").toAbsolutePath() : base).resolve(value).normalize();
  }

  public int number(String key, int fallback, int min, int max) {
    int value = Integer.parseInt(text(key, "" + fallback));
    if (value < min || value > max) throw new IllegalArgumentException("Cấu hình sai: " + key);
    return value;
  }

  public boolean flag(String key, boolean fallback) {
    String value = text(key, "" + fallback);
    if (!value.equals("true") && !value.equals("false"))
      throw new IllegalArgumentException("Cấu hình sai: " + key);
    return Boolean.parseBoolean(value);
  }

  public String machineId() throws IOException {
    if (!values.containsKey("machine.id")) {
      values.setProperty("machine.id", UUID.randomUUID().toString());
      Files.createDirectories(path.toAbsolutePath().getParent());
      try (Writer writer = Files.newBufferedWriter(path)) {
        values.store(writer, "Ma may co dinh");
      }
    }
    return values.getProperty("machine.id");
  }
}
