package com.lanmanager.common.protocol.http;

import com.lanmanager.common.protocol.json.JsonCodec;
import java.io.*;
import java.net.*;
import java.util.*;

public final class HttpTransport {
  private final String host;
  private final int port;

  public HttpTransport(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public HttpMessage send(String method, String path, Object value, Map<String, String> extra)
      throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 5000);
      byte[] body = value == null ? new byte[0] : JsonCodec.bytes(value);
      Map<String, String> headers = new LinkedHashMap<>(extra);
      headers.put("Host", host + ":" + port);
      headers.put("Connection", "close");
      if (value != null) {
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", "" + body.length);
      }
      HttpCodec.write(
          socket.getOutputStream(), method + " " + path + " HTTP/1.1", headers, body, false);
      return new HttpCodec(socket, 5000).readMessage(false, method.equals("HEAD"));
    }
  }
}
