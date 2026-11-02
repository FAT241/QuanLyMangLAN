package com.lanmanager.server.storage;

import com.google.gson.JsonArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class CsvExporter {
  public void export(Path target, JsonArray entries) throws IOException {
    try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      writer.write('\ufeff');
      writer.write("URL,Tiêu đề,Thời gian UTC (ms)\r\n");
      for (var element : entries) {
        var entry = element.getAsJsonObject();
        writer.write(cell(entry.get("url").getAsString()));
        writer.write(',');
        writer.write(cell(entry.get("title").isJsonNull() ? "" : entry.get("title").getAsString()));
        writer.write(',');
        writer.write(entry.get("visitTime").getAsString());
        writer.write("\r\n");
      }
    }
  }

  private String cell(String value) {
    String stripped = value.stripLeading();
    if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0
        || value.startsWith("\t")
        || value.startsWith("\r")
        || value.startsWith("\n")) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
