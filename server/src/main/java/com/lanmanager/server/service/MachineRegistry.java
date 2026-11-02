package com.lanmanager.server.service;

import com.google.gson.JsonObject;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.util.*;
import java.util.function.LongSupplier;

/** Khóa ngắn bảo vệ toàn bộ chuyển trạng thái và thay phiên. */
public final class MachineRegistry {
  public record Machine(
      String id,
      String name,
      String ip,
      int udpPort,
      String sessionId,
      boolean online,
      long lastSeen,
      long seq,
      Double rttMs) {}

  public interface Listener {
    void changed(Machine machine);
  }

  private final Map<String, Machine> machines = new LinkedHashMap<>();
  private final LongSupplier clock;
  private final long offlineMs, expiryMs;
  private final Listener listener;

  public MachineRegistry(LongSupplier clock, long offlineMs, long expiryMs, Listener listener) {
    this.clock = clock;
    this.offlineMs = offlineMs;
    this.expiryMs = expiryMs;
    this.listener = listener;
  }

  public synchronized Machine register(JsonObject body, String ip) {
    String id = JsonCodec.requiredText(body, "machineId", 100),
        name = JsonCodec.requiredText(body, "machineName", 200);
    int port = (int) JsonCodec.requiredLong(body, "udpPort", 1, 65535);
    Machine old = machines.get(id);
    Machine machine =
        new Machine(
            id, name, ip, port, UUID.randomUUID().toString(), true, clock.getAsLong(), -1, null);
    machines.put(id, machine);
    if (old == null || !old.online()) listener.changed(machine);
    return machine;
  }

  public synchronized Machine authenticate(String session, String ip) {
    if (session == null) return null;
    return machines.values().stream()
        .filter(
            m ->
                m.sessionId() != null
                    && m.sessionId().equals(session)
                    && m.ip().equals(ip)
                    && clock.getAsLong() - m.lastSeen() < expiryMs)
        .findFirst()
        .orElse(null);
  }

  public synchronized boolean recordHeartbeat(String id, String session, String ip, long seq) {
    Machine old = authenticate(session, ip);
    if (old == null || !old.id().equals(id) || seq <= old.seq()) return false;
    Machine updated =
        new Machine(
            old.id(),
            old.name(),
            old.ip(),
            old.udpPort(),
            old.sessionId(),
            true,
            clock.getAsLong(),
            seq,
            old.rttMs());
    machines.put(id, updated);
    if (!old.online()) listener.changed(updated);
    return true;
  }

  public synchronized void unregister(String session) {
    for (Machine m : List.copyOf(machines.values()))
      if (Objects.equals(m.sessionId(), session)) offline(m, true);
  }

  public synchronized void checkConnectionTimeouts() {
    for (Machine m : List.copyOf(machines.values())) {
      long elapsed = clock.getAsLong() - m.lastSeen();
      if (elapsed >= offlineMs && (m.online() || (m.sessionId() != null && elapsed >= expiryMs)))
        offline(m, elapsed >= expiryMs);
    }
  }

  private void offline(Machine old, boolean revoke) {
    Machine m =
        new Machine(
            old.id(),
            old.name(),
            old.ip(),
            old.udpPort(),
            revoke ? null : old.sessionId(),
            false,
            old.lastSeen(),
            old.seq(),
            null);
    machines.put(m.id(), m);
    if (old.online()) listener.changed(m);
  }

  public synchronized void updateRoundTripTime(String id, String session, Double value) {
    Machine m = machines.get(id);
    if (m == null || !m.online() || !Objects.equals(m.sessionId(), session)) return;
    machines.put(
        id,
        new Machine(
            m.id(),
            m.name(),
            m.ip(),
            m.udpPort(),
            m.sessionId(),
            true,
            m.lastSeen(),
            m.seq(),
            value));
  }

  public synchronized Machine get(String id) {
    return machines.get(id);
  }

  public synchronized List<Machine> snapshot() {
    return List.copyOf(machines.values());
  }
}
