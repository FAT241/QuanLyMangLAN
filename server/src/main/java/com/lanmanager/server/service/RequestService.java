package com.lanmanager.server.service;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.util.*;
import java.util.concurrent.*;

public final class RequestService {
  public static final Set<String> TYPES =
      Set.of(
          "GET_ACTIVITY",
          "TERMINATE_PROCESS",
          "GET_WEB_HISTORY",
          "GET_SCREENSHOT",
          "GET_PROCESSES",
          "LOCK_SCREEN");

  private static final class Pending {
    final String session;
    final JsonObject command;
    final long deadline;
    final CompletableFuture<JsonObject> future = new CompletableFuture<>();
    boolean delivered;
    JsonObject result;

    Pending(String session, JsonObject command, long deadline) {
      this.session = session;
      this.command = command;
      this.deadline = deadline;
    }
  }

  private final Map<String, Pending> pending = new LinkedHashMap<>();

  public synchronized CompletableFuture<JsonObject> send(
      MachineRegistry.Machine machine, String type, JsonObject payload) {
    if (machine == null || !machine.online()) throw new IllegalStateException("Máy mất kết nối");
    if (!TYPES.contains(type)) throw new IllegalArgumentException("Lệnh chưa hỗ trợ");
    if (pending.values().stream().filter(p -> p.session.equals(machine.sessionId())).count() >= 100)
      throw new IllegalStateException("Hàng đợi đầy");
    String id = UUID.randomUUID().toString();
    Pending p =
        new Pending(
            machine.sessionId(),
            JsonCodec.object("commandId", id, "type", type, "payload", payload),
            System.nanoTime()
                + TimeUnit.SECONDS.toNanos(
                    Set.of("GET_WEB_HISTORY", "GET_ACTIVITY").contains(type) ? 15 : 5));
    pending.put(id, p);
    return p.future;
  }

  public synchronized JsonObject poll(String session) {
    for (Pending p : pending.values())
      if (p.session.equals(session)
          && !p.delivered
          && !p.future.isDone()
          && System.nanoTime() < p.deadline) {
        p.delivered = true;
        return p.command.deepCopy();
      }
    return null;
  }

  public synchronized boolean result(String session, JsonObject body) {
    Pending p = pending.get(JsonCodec.requiredText(body, "commandId", 100));
    if (p == null || !p.session.equals(session) || !p.delivered || System.nanoTime() >= p.deadline)
      return false;
    if (!body.has("ok")
        || !body.get("ok").isJsonPrimitive()
        || !body.getAsJsonPrimitive("ok").isBoolean())
      throw new IllegalArgumentException("Thiếu ok");
    if (body.get("ok").getAsBoolean() ? !body.has("payload") : !body.has("error"))
      throw new IllegalArgumentException("Thiếu kết quả");
    if (p.result != null) return p.result.equals(body);
    p.result = body.deepCopy();
    p.future.complete(p.result);
    return true;
  }

  public synchronized void expirePendingRequests(MachineRegistry registry) {
    Iterator<Pending> it = pending.values().iterator();
    while (it.hasNext()) {
      Pending p = it.next();
      boolean valid =
          registry.snapshot().stream().anyMatch(m -> m.online() && p.session.equals(m.sessionId()));
      if (!valid || System.nanoTime() >= p.deadline) {
        p.future.completeExceptionally(new TimeoutException(valid ? "TIMEOUT" : "SESSION_CLOSED"));
        it.remove();
      }
    }
  }

  public synchronized void close() {
    pending
        .values()
        .forEach(p -> p.future.completeExceptionally(new CancellationException("Máy chủ đã đóng")));
    pending.clear();
  }
}
