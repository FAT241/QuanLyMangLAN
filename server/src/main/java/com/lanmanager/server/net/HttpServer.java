package com.lanmanager.server.net;

import com.google.gson.*;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.service.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;

public final class HttpServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(HttpServer.class);
  private final ServerSocket socket;
  private final Thread acceptor;
  private final Set<Socket> active = ConcurrentHashMap.newKeySet();
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          4,
          16,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          r -> new Thread(r, "http-worker"));
  private final MachineRegistry registry;
  private final RequestService requests;
  private final String token;
  private final int heartbeat;
  private final ClassroomApi classroomApi;
  private volatile boolean running = true;

  public HttpServer(
      int port,
      MachineRegistry registry,
      RequestService requests,
      String token,
      int heartbeat,
      ClassroomApi classroomApi)
      throws IOException {
    this.registry = registry;
    this.requests = requests;
    this.token = token;
    this.heartbeat = heartbeat;
    this.classroomApi = classroomApi;
    socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(port));
    acceptor = new Thread(this::accept, "http-acceptor");
    acceptor.start();
  }

  public int port() {
    return socket.getLocalPort();
  }

  private void accept() {
    while (running)
      try {
        Socket client = socket.accept();
        active.add(client);
        try {
          workers.execute(() -> handle(client));
        } catch (RejectedExecutionException e) {
          reply(client, 503, JsonCodec.object("error", "Máy chủ bận"), false, Map.of());
          active.remove(client);
          client.close();
        }
      } catch (IOException e) {
        if (running) LOG.warn("Không nhận được kết nối", e);
      }
  }

  private void handle(Socket client) {
    try (client) {
      try {
        HttpMessage message = new HttpCodec(client, 5000).readMessage(true, false);
        String[] line = message.firstLine().split(" ");
        String method = line[0], path = line[1];
        boolean head = method.equals("HEAD");
        if (classroomApi.handles(path)) {
          var response =
              classroomApi.handle(method, path, message, client.getInetAddress().getHostAddress());
          reply(client, response.status(), response.body(), head, response.headers());
          return;
        }
        if (path.equals("/api/v1/health")) {
          if (!method.equals("GET") && !head) {
            reply(
                client,
                405,
                JsonCodec.object("error", "Sai phương thức"),
                false,
                Map.of("Allow", "GET, HEAD"));
            return;
          }
          if (message.body().length != 0) throw new IllegalArgumentException("GET không nhận thân");
          reply(client, 200, JsonCodec.object("status", "ok"), head, Map.of());
          return;
        }
        if (!Set.of(
                "/api/v1/register",
                "/api/v1/commands/poll",
                "/api/v1/results",
                "/api/v1/unregister")
            .contains(path)) {
          reply(client, 404, JsonCodec.object("error", "Không có đường dẫn"), false, Map.of());
          return;
        }
        if (!method.equals("POST")) {
          reply(
              client,
              405,
              JsonCodec.object("error", "Sai phương thức"),
              head,
              Map.of("Allow", "POST"));
          return;
        }
        if (!message
            .headers()
            .getOrDefault("content-type", "")
            .split(";", 2)[0]
            .equalsIgnoreCase("application/json")) {
          reply(client, 415, JsonCodec.object("error", "Cần JSON"), false, Map.of());
          return;
        }
        JsonObject body = message.json();
        String ip = client.getInetAddress().getHostAddress();
        if (path.equals("/api/v1/register")) {
          if (!MessageDigest.isEqual(
              token.getBytes(StandardCharsets.UTF_8),
              message.headers().getOrDefault("x-lan-token", "").getBytes(StandardCharsets.UTF_8))) {
            reply(client, 403, error("INVALID_TOKEN", "Mã truy cập sai"), false, Map.of());
            return;
          }
          var machine = registry.register(body, ip);
          reply(
              client,
              201,
              JsonCodec.object(
                  "sessionId",
                  machine.sessionId(),
                  "heartbeatIntervalSeconds",
                  heartbeat,
                  "pollIntervalSeconds",
                  1),
              false,
              Map.of());
          return;
        }
        var machine = registry.authenticate(message.headers().get("x-lan-session"), ip);
        if (machine == null) {
          reply(client, 403, error("SESSION_INVALID", "Phiên hết hiệu lực"), false, Map.of());
          return;
        }
        switch (path) {
          case "/api/v1/commands/poll" -> {
            JsonObject command = machine.online() ? requests.poll(machine.sessionId()) : null;
            reply(client, command == null ? 204 : 200, command, false, Map.of());
          }
          case "/api/v1/results" -> {
            boolean ok = requests.result(machine.sessionId(), body);
            reply(
                client,
                ok ? 200 : 409,
                ok
                    ? JsonCodec.object("accepted", true)
                    : error("RESULT_CONFLICT", "Kết quả quá hạn hoặc không khớp"),
                false,
                Map.of());
          }
          case "/api/v1/unregister" -> {
            registry.unregister(machine.sessionId());
            reply(client, 204, null, false, Map.of());
          }
          default -> throw new IllegalStateException("Đường dẫn chưa xử lý");
        }
      } catch (SecurityException e) {
        reply(client, 403, error("ACCESS_DENIED", e.getMessage()), false, Map.of());
      } catch (java.sql.SQLException e) {
        LOG.error("Lỗi cơ sở dữ liệu nghiệp vụ", e);
        reply(
            client,
            e.getSQLState() != null && e.getSQLState().startsWith("23") ? 409 : 500,
            error("DATA_CONFLICT", "Không lưu được dữ liệu; kiểm tra trùng tài khoản hoặc phiên"),
            false,
            Map.of());
      } catch (HttpCodec.ProtocolException e) {
        reply(client, e.status, error("HTTP_INVALID", e.getMessage()), false, Map.of());
      } catch (JsonParseException e) {
        reply(client, 400, error("INVALID_JSON", "JSON không hợp lệ"), false, Map.of());
      } catch (IllegalArgumentException | IllegalStateException e) {
        String message = e.getMessage();
        reply(
            client,
            400,
            error(
                "INVALID_INPUT",
                message != null && message.length() < 300 ? message : "Dữ liệu không hợp lệ"),
            false,
            Map.of());
      } catch (Exception e) {
        LOG.error("Không xử lý được yêu cầu", e);
        reply(client, 500, error("INTERNAL_ERROR", "Không xử lý được yêu cầu"), false, Map.of());
      }
    } catch (IOException e) {
      if (running) LOG.warn("Kết nối HTTP bị ngắt: {}", e.getMessage());
    } finally {
      active.remove(client);
    }
  }

  private static JsonObject error(String code, String message) {
    return JsonCodec.object("error", JsonCodec.object("code", code, "message", message));
  }

  private static void reply(
      Socket socket, int code, Object value, boolean head, Map<String, String> extra)
      throws IOException {
    byte[] body = code == 204 ? new byte[0] : JsonCodec.bytes(value);
    Map<String, String> headers = HttpCodec.responseHeaders(code, body);
    headers.putAll(extra);
    String reason =
        switch (code) {
          case 200 -> "OK";
          case 201 -> "Created";
          case 204 -> "No Content";
          case 400 -> "Bad Request";
          case 403 -> "Forbidden";
          case 404 -> "Not Found";
          case 405 -> "Method Not Allowed";
          case 409 -> "Conflict";
          case 411 -> "Length Required";
          case 413 -> "Content Too Large";
          case 415 -> "Unsupported Media Type";
          case 417 -> "Expectation Failed";
          case 501 -> "Not Implemented";
          case 503 -> "Service Unavailable";
          case 505 -> "HTTP Version Not Supported";
          default -> "Internal Server Error";
        };
    HttpCodec.write(
        socket.getOutputStream(), "HTTP/1.1 " + code + " " + reason, headers, body, head);
  }

  public void close() throws IOException {
    running = false;
    socket.close();
    for (Socket s : active) s.close();
    workers.shutdownNow();
    try {
      acceptor.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
