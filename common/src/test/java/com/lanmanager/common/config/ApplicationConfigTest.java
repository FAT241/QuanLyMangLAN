package com.lanmanager.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationConfigTest {
  @TempDir Path temporary;

  @Test
  void findsApplicationConfigsWhenMavenRunsInCommonModule() {
    Path server =
        ApplicationConfig.findProjectConfig("server.properties").toAbsolutePath().normalize();
    Path client =
        ApplicationConfig.findProjectConfig("client.properties").toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(server));
    assertTrue(Files.isRegularFile(client));
    assertTrue(server.endsWith(Path.of("server", "config", "server.properties")));
    assertTrue(client.endsWith(Path.of("client", "config", "client.properties")));
  }

  @Test
  void relativeDataPathsBelongToTheirApplication() throws Exception {
    Path config = temporary.resolve("server/config/server.properties");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "evidence.path=data/evidence\n");
    assertEquals(
        temporary.resolve("server/data/evidence"),
        new ApplicationConfig(config).resolvePath("evidence.path", "unused"));
    Path external = temporary.resolve("external");
    Files.writeString(config, "evidence.path=" + external.toString().replace('\\', '/') + "\n");
    assertEquals(external, new ApplicationConfig(config).resolvePath("evidence.path", "unused"));
  }
}
