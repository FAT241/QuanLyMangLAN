package com.lanmanager.client;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.client.handler.ProcessControl;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProcessControlTest {
  @Test
  void dryRunThenClosesOnlyMatchingHarmlessChild() throws Exception {
    boolean windows = System.getProperty("os.name").contains("Windows");
    Process child =
        (windows
                ? new ProcessBuilder("ping.exe", "-n", "60", "127.0.0.1")
                : new ProcessBuilder("sleep", "60"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      var info = child.info();
      long started = info.startInstant().orElseThrow().toEpochMilli();
      String name = Path.of(info.command().orElseThrow()).getFileName().toString();
      var payload = JsonCodec.object("pid", child.pid(), "name", name, "startedAt", started);
      assertTrue(
          new ProcessControl(true)
              .terminate(payload)
              .get("detail")
              .getAsString()
              .contains("Chế độ thử"));
      assertTrue(child.isAlive());
      payload.addProperty("startedAt", started + 1);
      assertThrows(
          IllegalArgumentException.class, () -> new ProcessControl(false).terminate(payload));
      payload.addProperty("startedAt", started);
      new ProcessControl(false).terminate(payload);
      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void cannotKillAgentItself() {
    assertThrows(
        SecurityException.class,
        () ->
            new ProcessControl(false)
                .terminate(
                    JsonCodec.object(
                        "pid",
                        ProcessHandle.current().pid(),
                        "name",
                        "java.exe",
                        "startedAt",
                        ProcessHandle.current()
                            .info()
                            .startInstant()
                            .orElseThrow()
                            .toEpochMilli())));
  }
}
