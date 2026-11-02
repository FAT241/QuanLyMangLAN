package com.lanmanager.client;

import com.lanmanager.common.config.ApplicationConfig;
import java.nio.file.Path;

public final class ClientAgentApplication {
  public static void main(String[] args) throws Exception {
    ClientAgent agent =
        new ClientAgent(
            new ApplicationConfig(
                args.length > 0 ? Path.of(args[0]) : ApplicationConfig.prepareClientConfig()));
    Runtime.getRuntime().addShutdownHook(new Thread(agent::close, "client-shutdown"));
    new java.util.concurrent.CountDownLatch(1).await();
  }
}
