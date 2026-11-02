package com.lanmanager.client.collector;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.sqlite.SQLiteConnection;

public final class BrowserHistoryReader {
  public static long epochMillis(long chromeTime) {
    return chromeTime / 1000 - 11644473600000L;
  }

  public JsonObject read(JsonObject request) throws Exception {
    String browser = JsonCodec.requiredText(request, "browser", 10);
    if (!Set.of("chrome", "edge").contains(browser))
      throw new IllegalArgumentException("INVALID_BROWSER");
    int limit = request.has("limit") ? (int) JsonCodec.requiredLong(request, "limit", 1, 500) : 100;
    long since =
        request.has("sinceEpochMs")
            ? JsonCodec.requiredLong(request, "sinceEpochMs", 0, Long.MAX_VALUE)
            : 0;
    String local = System.getenv("LOCALAPPDATA");
    if (local == null) throw new IllegalStateException("BROWSER_NOT_FOUND");
    Path source =
        Path.of(
            local,
            browser.equals("chrome")
                ? "Google/Chrome/User Data/Default/History"
                : "Microsoft/Edge/User Data/Default/History");
    if (!Files.isRegularFile(source)) throw new IllegalStateException("BROWSER_NOT_FOUND");
    return readFromSource(source, request);
  }

  public JsonObject readFromSource(Path source, JsonObject request) throws Exception {
    String browser = JsonCodec.requiredText(request, "browser", 10);
    int limit = request.has("limit") ? (int) JsonCodec.requiredLong(request, "limit", 1, 500) : 100;
    long since =
        request.has("sinceEpochMs")
            ? JsonCodec.requiredLong(request, "sinceEpochMs", 0, Long.MAX_VALUE)
            : 0;
    long afterId =
        request.has("afterVisitId")
            ? JsonCodec.requiredLong(request, "afterVisitId", 0, Long.MAX_VALUE)
            : 0;
    Path temp = Files.createTempFile("lan-history-", ".db");
    try {
      // API sao lưu SQLite tạo ảnh chụp nhất quán, không chép riêng tệp đang ghi.
      try (Connection connection =
          DriverManager.getConnection("jdbc:sqlite:" + source.toUri() + "?mode=ro")) {
        int code =
            ((SQLiteConnection) connection).getDatabase().backup("main", temp.toString(), null);
        if (code != 0) throw new SQLException("BROWSER_BUSY");
      }
      JsonArray entries = new JsonArray();
      boolean truncated = false;
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temp);
          PreparedStatement sql =
              connection.prepareStatement(
                  "SELECT u.url,u.title,v.visit_time,v.id FROM visits v JOIN urls u ON u.id=v.url"
                      + " WHERE v.visit_time>=? AND (v.visit_time/1000>? OR v.id>?) ORDER BY"
                      + " v.visit_time/1000,v.id LIMIT ?")) {
        long chromeMillis = Math.addExact(since, 11644473600000L);
        sql.setLong(1, Math.multiplyExact(chromeMillis, 1000));
        sql.setLong(2, chromeMillis);
        sql.setLong(3, afterId);
        sql.setInt(4, limit + 1);
        try (ResultSet rows = sql.executeQuery()) {
          while (rows.next()) {
            if (entries.size() == limit) {
              truncated = true;
              break;
            }
            entries.add(
                JsonCodec.object(
                    "url",
                    rows.getString(1),
                    "title",
                    rows.getString(2),
                    "visitTime",
                    epochMillis(rows.getLong(3)),
                    "visitId",
                    rows.getLong(4)));
            if (JsonCodec.bytes(entries).length > 950000) {
              entries.remove(entries.size() - 1);
              truncated = true;
              break;
            }
          }
        }
      }
      return JsonCodec.object("browser", browser, "entries", entries, "truncated", truncated);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
