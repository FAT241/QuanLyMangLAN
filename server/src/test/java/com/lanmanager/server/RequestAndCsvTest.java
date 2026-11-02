package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.service.*;
import com.lanmanager.server.storage.CsvExporter;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestAndCsvTest {
  @TempDir Path temp;

  @Test
  void commandDeliveredOnceAndResultsDeduplicated() {
    var registry = new MachineRegistry(System::currentTimeMillis, 10000, 60000, m -> {});
    var machine =
        registry.register(
            JsonCodec.object(
                "machineId", "a", "machineName", "A", "os", "Windows", "udpPort", 9879),
            "127.0.0.1");
    var requests = new RequestService();
    var future =
        requests.send(machine, "GET_PROCESSES", JsonCodec.object("title", "a", "message", "b"));
    var command = requests.poll(machine.sessionId());
    assertNull(requests.poll(machine.sessionId()));
    var result =
        JsonCodec.object(
            "commandId",
            command.get("commandId").getAsString(),
            "ok",
            true,
            "payload",
            JsonCodec.object("detail", "ok"));
    assertFalse(requests.result("wrong-session", result));
    assertTrue(requests.result(machine.sessionId(), result));
    assertTrue(requests.result(machine.sessionId(), result));
    assertEquals(result, future.join());
    var waiting = requests.send(machine, "GET_WEB_HISTORY", JsonCodec.object());
    registry.unregister(machine.sessionId());
    requests.expirePendingRequests(registry);
    assertTrue(waiting.isCompletedExceptionally());
  }

  @Test
  void csvEscapesQuotesAndFormulas() throws Exception {
    var rows = new com.google.gson.JsonArray();
    rows.add(
        JsonCodec.object("url", "https://example.test", "title", "=1+1,\"x\"", "visitTime", 0));
    Path output = temp.resolve("history.csv");
    new CsvExporter().export(output, rows);
    String text = Files.readString(output);
    assertEquals('\ufeff', text.charAt(0));
    assertTrue(text.contains("\"'=1+1,\"\"x\"\"\""));
  }
}
