package com.lanmanager.server;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.service.MachineRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.extension.ExtendWith(PostgresTestDatabase.class)
class BackendTest {
  @TempDir Path temp;

  @Test
  void stateBoundaryAndOldSession() {
    AtomicLong time = new AtomicLong(1000);
    List<Boolean> changes = new ArrayList<>();
    var registry = new MachineRegistry(time::get, 10000, 60000, m -> changes.add(m.online()));
    var data =
        JsonCodec.object("machineId", "a", "machineName", "PC", "os", "Windows", "udpPort", 9879);
    var first = registry.register(data, "127.0.0.1");
    time.addAndGet(9999);
    registry.checkConnectionTimeouts();
    assertTrue(registry.get("a").online());
    time.incrementAndGet();
    registry.checkConnectionTimeouts();
    registry.checkConnectionTimeouts();
    assertEquals(List.of(true, false), changes);
    assertTrue(registry.recordHeartbeat("a", first.sessionId(), "127.0.0.1", 1));
    var second = registry.register(data, "127.0.0.2");
    registry.unregister(first.sessionId());
    assertTrue(registry.get("a").online());
    assertFalse(registry.recordHeartbeat("a", first.sessionId(), "127.0.0.1", 2));
    assertEquals(second.sessionId(), registry.get("a").sessionId());
  }

  @Test
  void realHttpUdpAgentCommandAndDatabase() throws Exception {
    Path serverConfig = temp.resolve("server.properties");
    Files.writeString(
        serverConfig,
        "http.port=0\nudp.port=0\nheartbeat.seconds=1\nping.seconds=1\n"
            + PostgresTestDatabase.properties());
    try (var backend = new ServerBackend(new ApplicationConfig(serverConfig))) {
      var transport = new HttpTransport("127.0.0.1", backend.http.port());
      assertEquals(200, transport.send("GET", "/api/v1/health", null, Map.of()).status());
      assertEquals(0, transport.send("HEAD", "/api/v1/health", null, Map.of()).body().length);
      assertEquals(
          403,
          transport
              .send("POST", "/api/v1/register", JsonCodec.object(), Map.of("X-Lan-Token", "wrong"))
              .status());
      Path clientConfig = temp.resolve("client.properties");
      Files.writeString(
          clientConfig,
          "server.host=127.0.0.1\nserver.http.port="
              + backend.http.port()
              + "\nserver.udp.port="
              + backend.udp.port()
              + "\nudp.client.port=0\ndryRun=true\nallowWebHistory=false\n");
      String id = new ApplicationConfig(clientConfig).machineId();
      String classpath =
          System.getProperty("java.class.path")
              + java.io.File.pathSeparator
              + Path.of("../client/target/classes").toAbsolutePath();
      Process agent =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  classpath,
                  "com.lanmanager.client.ClientAgentApplication",
                  clientConfig.toString())
              .redirectErrorStream(true)
              .redirectOutput(temp.resolve("agent.log").toFile())
              .start();
      try {

        await(() -> backend.machines.get(id) != null && backend.machines.get(id).seq() >= 1);
        var machine = backend.machines.get(id);
        assertTrue(machine.online());
        var processes =
            backend
                .requests
                .send(machine, "GET_PROCESSES", JsonCodec.object())
                .get(6, TimeUnit.SECONDS);
        assertTrue(processes.get("ok").getAsBoolean());
        assertTrue(processes.getAsJsonObject("payload").getAsJsonArray("processes").size() > 0);
        var disabled =
            backend
                .requests
                .send(machine, "GET_WEB_HISTORY", JsonCodec.object("browser", "chrome", "limit", 1))
                .get(6, TimeUnit.SECONDS);
        assertFalse(disabled.get("ok").getAsBoolean());
        assertEquals("DISABLED", disabled.getAsJsonObject("error").get("code").getAsString());
        if (System.getProperty("os.name").contains("Windows")) {
          var power =
              backend
                  .requests
                  .send(machine, "LOCK_SCREEN", JsonCodec.object())
                  .get(6, TimeUnit.SECONDS);
          assertTrue(power.get("ok").getAsBoolean());
          assertTrue(
              power.getAsJsonObject("payload").get("detail").getAsString().contains("Chế độ thử"));
        }
        await(() -> backend.machines.get(id).rttMs() != null);
        assertEquals(
            204,
            transport
                .send(
                    "POST",
                    "/api/v1/unregister",
                    JsonCodec.object(),
                    Map.of("X-Lan-Session", machine.sessionId()))
                .status());
      } finally {
        agent.destroy();
        if (!agent.waitFor(5, TimeUnit.SECONDS)) agent.destroyForcibly();
      }
      assertFalse(backend.machines.get(id).online());
      await(
          () -> {
            try {
              return backend.events.history(id).size() == 2;
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
    assertTrue(condition.getAsBoolean(), "Không đạt điều kiện trong 8 giây");
  }
}
