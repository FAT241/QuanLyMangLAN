package com.lanmanager.server.net;

import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.protocol.udp.UdpMessages;
import com.lanmanager.server.service.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;

public final class UdpService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(UdpService.class);

  private record Ping(String id, String session, long seq, long sent) {}

  private final Map<String, Ping> pending = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private final DatagramSocket socket;
  private final MachineRegistry registry;
  private final int httpPort;
  private final Thread listener;
  private volatile boolean running = true;

  public UdpService(int port, int httpPort, MachineRegistry registry) throws IOException {
    socket = new DatagramSocket(port);
    this.httpPort = httpPort;
    this.registry = registry;
    listener = new Thread(this::listen, "udp-listener");
    listener.start();
  }

  public int port() {
    return socket.getLocalPort();
  }

  private void listen() {
    while (running)
      try {
        var packet = UdpMessages.receive(socket);
        var p = packet.payload();
        String ip = packet.ip().getHostAddress();
        switch (packet.type()) {
          case "DISCOVER" ->
              UdpMessages.send(
                  socket,
                  packet.ip(),
                  packet.port(),
                  "DISCOVER_REPLY",
                  JsonCodec.object("httpPort", httpPort));
          case "HEARTBEAT" ->
              registry.recordHeartbeat(
                  JsonCodec.requiredText(p, "machineId", 100),
                  JsonCodec.requiredText(p, "sessionId", 100),
                  ip,
                  JsonCodec.requiredLong(p, "seq", 0, Long.MAX_VALUE));
          case "PONG" -> {
            String id = JsonCodec.requiredText(p, "machineId", 100),
                session = JsonCodec.requiredText(p, "sessionId", 100);
            long seq = JsonCodec.requiredLong(p, "seq", 0, Long.MAX_VALUE);
            Ping ping = pending.get(id);
            if (ping != null
                && ping.seq() == seq
                && ping.session().equals(session)
                && registry.authenticate(session, ip) != null
                && System.nanoTime() - ping.sent() < 2_000_000_000L) {
              registry.updateRoundTripTime(
                  id, session, (System.nanoTime() - ping.sent()) / 1_000_000.0);
              pending.remove(id, ping);
            }
          }
          default -> LOG.warn("Loại UDP không hỗ trợ: {}", packet.type());
        }
      } catch (IOException | RuntimeException e) {
        if (running) LOG.warn("Bỏ gói UDP: {}", e.getMessage());
      }
  }

  public void ping() {
    for (var m : registry.snapshot())
      if (m.online())
        try {
          long seq = sequence.incrementAndGet();
          pending.put(m.id(), new Ping(m.id(), m.sessionId(), seq, System.nanoTime()));
          UdpMessages.send(
              socket,
              InetAddress.getByName(m.ip()),
              m.udpPort(),
              "PING",
              JsonCodec.object("sessionId", m.sessionId(), "seq", seq));
        } catch (IOException e) {
          LOG.warn("Không gửi được PING: {}", e.getMessage());
        }
  }

  public void expire() {
    for (Ping p : pending.values())
      if (System.nanoTime() - p.sent() >= 2_000_000_000L && pending.remove(p.id(), p))
        registry.updateRoundTripTime(p.id(), p.session(), null);
  }

  public void close() {
    running = false;
    socket.close();
    try {
      listener.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
