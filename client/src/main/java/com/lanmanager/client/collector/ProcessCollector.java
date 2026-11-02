package com.lanmanager.client.collector;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ProcessCollector {
  public JsonObject collect() {
    JsonArray processes = new JsonArray();
    boolean truncated;
    try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
      var snapshot = all.limit(501).toList();
      truncated = snapshot.size() > 500;
      snapshot.stream()
          .limit(500)
          .forEach(
              process -> {
                var info = process.info();
                info.command()
                    .ifPresent(
                        command ->
                            processes.add(
                                JsonCodec.object(
                                    "pid",
                                    process.pid(),
                                    "name",
                                    Path.of(command).getFileName().toString(),
                                    "startedAt",
                                    info.startInstant()
                                        .map(java.time.Instant::toEpochMilli)
                                        .orElse(null))));
              });
    }
    return JsonCodec.object(
        "observedAt",
        System.currentTimeMillis(),
        "processes",
        processes,
        "processesTruncated",
        truncated);
  }
}
