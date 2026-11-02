package com.lanmanager.client;

import com.google.gson.*;
import com.lanmanager.client.handler.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.protocol.udp.UdpMessages;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;

public final class ClientAgent implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ClientAgent.class);

  private record Session(
      String id, InetAddress host, HttpTransport transport, int heartbeatSeconds) {}

  private final ApplicationConfig config;
  private final String machineId;
  private final DatagramSocket udp;
  private final Thread listener;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, r -> new Thread(r, "client-scheduler"));
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> new Thread(r, "command-worker"));
  private final AtomicLong sequence = new AtomicLong();
  private final Map<String, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>());
  private final CommandDispatcher dispatcher;
  private volatile Session session;
  private volatile boolean running = true;
  private long nextAttempt;
  private volatile long nextHeartbeat;
  private int backoff = 2;

  public ClientAgent(ApplicationConfig config) throws Exception {
    this.config = config;
    machineId = config.machineId();
    dispatcher = new CommandDispatcher(config);
    udp = new DatagramSocket(config.number("udp.client.port", 9879, 0, 65535));
    listener = new Thread(this::listen, "client-udp");
    listener.start();
    scheduler.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::heartbeat, 0, 200, TimeUnit.MILLISECONDS);
  }

  public String machineId() {
    return machineId;
  }

  public boolean registered() {
    return session != null;
  }

  public JsonObject classroomCall(String route, JsonObject body, String userToken)
      throws IOException {
    Session current = session;
    if (current == null) throw new IOException("Chưa kết nối máy chủ; đợi kết nối rồi thử lại");
    return ClassroomClient.call(current.transport(), route, body, userToken, current.id());
  }

  private void register() throws Exception {
    String host = config.text("server.host", "");
    int port = config.number("server.http.port", 9877, 1, 65535);
    if (host.isBlank()) {
      try (DatagramSocket discovery = new DatagramSocket()) {
        discovery.setBroadcast(true);
        discovery.setSoTimeout(2000);
        UdpMessages.send(
            discovery,
            InetAddress.getByName("255.255.255.255"),
            config.number("server.udp.port", 9876, 1, 65535),
            "DISCOVER",
            JsonCodec.object("machineName", InetAddress.getLocalHost().getHostName()));
        var reply = UdpMessages.receive(discovery);
        if (!reply.type().equals("DISCOVER_REPLY"))
          throw new IOException("Phản hồi tìm máy chủ sai");
        host = reply.ip().getHostAddress();
        port = (int) JsonCodec.requiredLong(reply.payload(), "httpPort", 1, 65535);
      }
    }
    HttpTransport transport = new HttpTransport(host, port);
    var result =
        transport.send(
            "POST",
            "/api/v1/register",
            JsonCodec.object(
                "machineId",
                machineId,
                "machineName",
                InetAddress.getLocalHost().getHostName(),
                "udpPort",
                udp.getLocalPort()),
            Map.of("X-Lan-Token", config.text("token", "lan-demo-token")));
    if (result.status() == 403) {
      LOG.error("Mã truy cập sai; sửa cấu hình và khởi động lại máy con");
      nextAttempt = Long.MAX_VALUE;
      return;
    }
    if (result.status() != 201) throw new IOException("Đăng ký thất bại: " + result.status());
    var body = result.json();
    session =
        new Session(
            JsonCodec.requiredText(body, "sessionId", 100),
            InetAddress.getByName(host),
            transport,
            (int) JsonCodec.requiredLong(body, "heartbeatIntervalSeconds", 1, 30));
    seen.clear();
    backoff = 2;
    sequence.set(0);
    nextHeartbeat = 0;
    LOG.info("Máy con đang được quản lý bởi máy chủ {}", host);
  }

  private void poll() {
    if (!running) return;
    Session current = session;
    try {
      if (current == null) {
        if (System.nanoTime() >= nextAttempt) register();
        return;
      }
      var response =
          current
              .transport()
              .send(
                  "POST",
                  "/api/v1/commands/poll",
                  JsonCodec.object(),
                  Map.of("X-Lan-Session", current.id()));
      if (response.status() == 204) return;
      if (response.status() != 200)
        throw new IOException("Không nhận được lệnh: " + response.status());
      JsonObject command = response.json();
      String id = JsonCodec.requiredText(command, "commandId", 100);
      synchronized (seen) {
        if (seen.containsKey(id)) return;
        seen.put(id, true);
        if (seen.size() > 1000) seen.remove(seen.keySet().iterator().next());
      }
      try {
        workers.execute(() -> execute(current, command));
      } catch (RejectedExecutionException e) {
        LOG.warn("Hàng xử lý máy con đầy");
      }
    } catch (Exception e) {
      reconnect(current, e);
    }
  }

  private void execute(Session current, JsonObject command) {
    JsonObject result = dispatcher.handle(command);
    if (session != current || !running) return;
    try {
      var response =
          current
              .transport()
              .send("POST", "/api/v1/results", result, Map.of("X-Lan-Session", current.id()));
      if (response.status() != 200) LOG.warn("Máy chủ không nhận kết quả: {}", response.status());
    } catch (IOException e) {
      LOG.warn("Không gửi được kết quả; không thực hiện lại lệnh");
    }
  }

  private void reconnect(Session current, Exception e) {
    if (session == current) {
      session = null;
      nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(backoff);
      backoff = Math.min(15, backoff * 2);
    }
    LOG.warn("Sẽ đăng ký lại: {}", e.getMessage());
  }

  private void heartbeat() {
    Session current = session;
    if (current == null || !running || System.nanoTime() < nextHeartbeat) return;
    try {
      UdpMessages.send(
          udp,
          current.host(),
          config.number("server.udp.port", 9876, 1, 65535),
          "HEARTBEAT",
          JsonCodec.object(
              "machineId",
              machineId,
              "sessionId",
              current.id(),
              "seq",
              sequence.incrementAndGet()));
      nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(current.heartbeatSeconds());
    } catch (IOException e) {
      LOG.warn("Không gửi được tín hiệu: {}", e.getMessage());
    }
  }

  private void listen() {
    while (running)
      try {
        var packet = UdpMessages.receive(udp);
        Session current = session;
        if (current != null
            && packet.ip().equals(current.host())
            && packet.type().equals("PING")
            && current.id().equals(JsonCodec.requiredText(packet.payload(), "sessionId", 100)))
          UdpMessages.send(
              udp,
              packet.ip(),
              packet.port(),
              "PONG",
              JsonCodec.object(
                  "machineId",
                  machineId,
                  "sessionId",
                  current.id(),
                  "seq",
                  JsonCodec.requiredLong(packet.payload(), "seq", 0, Long.MAX_VALUE)));
      } catch (Exception e) {
        if (running) LOG.warn("Bỏ gói UDP: {}", e.getMessage());
      }
  }

  public void close() {
    if (!running) return;
    running = false;
    scheduler.shutdownNow();
    workers.shutdownNow();
    Session current = session;
    session = null;
    if (current != null)
      try {
        current
            .transport()
            .send(
                "POST",
                "/api/v1/unregister",
                JsonCodec.object(),
                Map.of("X-Lan-Session", current.id()));
      } catch (IOException e) {
        LOG.warn("Không gửi được hủy đăng ký: {}", e.getMessage());
      }
    udp.close();
    try {
      listener.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
