package com.lanmanager.common.protocol.udp;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.io.*;
import java.net.*;
import java.util.*;

public final class UdpMessages {
  private UdpMessages() {}

  public static void send(
      DatagramSocket socket, InetAddress ip, int port, String type, JsonObject payload)
      throws IOException {
    byte[] bytes =
        JsonCodec.bytes(
            JsonCodec.object(
                "v",
                1,
                "type",
                type,
                "id",
                UUID.randomUUID().toString(),
                "ts",
                System.currentTimeMillis(),
                "from",
                "lan-manager",
                "payload",
                payload));
    if (bytes.length > 1024) throw new IOException("Gói UDP quá dài");
    socket.send(new DatagramPacket(bytes, bytes.length, ip, port));
  }

  public record Received(InetAddress ip, int port, String type, JsonObject payload) {}

  public static Received receive(DatagramSocket socket) throws IOException {
    DatagramPacket packet = new DatagramPacket(new byte[1025], 1025);
    socket.receive(packet);
    if (packet.getLength() > 1024) throw new IOException("Gói UDP quá dài");
    try {
      JsonObject body = JsonCodec.parse(Arrays.copyOf(packet.getData(), packet.getLength()));
      if (JsonCodec.requiredLong(body, "v", 1, 1) != 1) throw new IllegalArgumentException();
      return new Received(
          packet.getAddress(),
          packet.getPort(),
          JsonCodec.requiredText(body, "type", 50),
          body.getAsJsonObject("payload"));
    } catch (RuntimeException e) {
      throw new IOException("Gói UDP sai", e);
    }
  }
}
