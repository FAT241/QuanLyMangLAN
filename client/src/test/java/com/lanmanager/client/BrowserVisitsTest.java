package com.lanmanager.client;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.client.collector.BrowserHistoryReader;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserVisitsTest {
  @TempDir Path temp;

  @Test
  void liveWalBackupReadsIndividualVisitsAndPagesEqualTimestamps() throws Exception {
    Path source = temp.resolve("History");
    long epoch = 1700000000000L;
    long chrome = (epoch + 11644473600000L) * 1000;
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + source);
        Statement sql = db.createStatement()) {
      sql.execute("PRAGMA journal_mode=WAL");
      sql.execute("PRAGMA wal_autocheckpoint=0");
      sql.execute("CREATE TABLE urls(id INTEGER PRIMARY KEY,url TEXT,title TEXT)");
      sql.execute("CREATE TABLE visits(id INTEGER PRIMARY KEY,url INTEGER,visit_time INTEGER)");
      sql.execute("INSERT INTO urls VALUES(1,'https://example.test','Bài học')");
      sql.execute(
          "INSERT INTO visits VALUES(1,1,"
              + chrome
              + "),(2,1,"
              + (chrome + 200)
              + "),(3,1,"
              + (chrome + 1000)
              + ")");
      var reader = new BrowserHistoryReader();
      var first =
          reader.readFromSource(
              source, JsonCodec.object("browser", "chrome", "limit", 1, "sinceEpochMs", epoch));
      assertEquals(1, first.getAsJsonArray("entries").size());
      assertTrue(first.get("truncated").getAsBoolean());
      var second =
          reader.readFromSource(
              source,
              JsonCodec.object(
                  "browser", "chrome", "limit", 5, "sinceEpochMs", epoch, "afterVisitId", 1));
      assertEquals(2, second.getAsJsonArray("entries").size());
      assertEquals(
          2, second.getAsJsonArray("entries").get(0).getAsJsonObject().get("visitId").getAsLong());
      assertEquals(
          "Bài học",
          second.getAsJsonArray("entries").get(0).getAsJsonObject().get("title").getAsString());
    }
  }
}
