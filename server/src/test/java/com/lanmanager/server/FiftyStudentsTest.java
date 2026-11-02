package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.HttpTransport;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.protocol.udp.UdpMessages;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(PostgresTestDatabase.class)
class FiftyStudentsTest {
  @TempDir Path temp;

  @Test
  void fiftyAccountsAndSimulatedAgentsRemainDistinct() throws Exception {
    Path config = temp.resolve("server.properties");
    Files.writeString(
        config,
        "http.port=0\nudp.port=0\nactivity.seconds=300\n" + PostgresTestDatabase.properties());
    try (var backend = new ServerBackend(new ApplicationConfig(config));
        var udp = new DatagramSocket()) {
      var transport = new HttpTransport("127.0.0.1", backend.http.port());
      ExecutorService workers = Executors.newFixedThreadPool(8);
      List<Future<JsonObject>> registered = new ArrayList<>();
      try {
        for (int i = 0; i < 50; i++) {
          final int index = i;
          registered.add(
              workers.submit(
                  () -> {
                    var response =
                        transport.send(
                            "POST",
                            "/api/v1/register",
                            JsonCodec.object(
                                "machineId",
                                "pc-" + index,
                                "machineName",
                                "PC " + index,
                                "os",
                                "Windows",
                                "udpPort",
                                udp.getLocalPort()),
                            Map.of("X-Lan-Token", "lan-demo-token"));
                    assertEquals(201, response.status());
                    return response.json();
                  }));
        }
        for (int i = 0; i < 50; i++) {
          String session =
              registered.get(i).get(10, TimeUnit.SECONDS).get("sessionId").getAsString();
          UdpMessages.send(
              udp,
              InetAddress.getLoopbackAddress(),
              backend.udp.port(),
              "HEARTBEAT",
              JsonCodec.object("machineId", "pc-" + i, "sessionId", session, "seq", 1));
          assertEquals(
              204,
              transport
                  .send(
                      "POST",
                      "/api/v1/commands/poll",
                      JsonCodec.object(),
                      Map.of("X-Lan-Session", session))
                  .status());
          backend.accounts.createAccount(
              "student-" + i, "test-password-123", "Sinh viên " + i, "STUDENT");
        }
        assertEquals(50, backend.machines.snapshot().size());
        assertEquals(
            50,
            backend.classroomDatabase.query("SELECT id FROM accounts WHERE role='STUDENT'").size());
        assertEquals(200, transport.send("GET", "/api/v1/health", null, Map.of()).status());
        assertEquals(
            50, backend.machines.snapshot().stream().map(m -> m.sessionId()).distinct().count());
      } finally {
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }
}
