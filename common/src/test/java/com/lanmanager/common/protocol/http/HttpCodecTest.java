package com.lanmanager.common.protocol.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpCodecTest {
  private HttpMessage parse(String wire) throws Exception {
    try (ServerSocket server = new ServerSocket(0);
        Socket sender = new Socket("127.0.0.1", server.getLocalPort());
        Socket receiver = server.accept()) {
      sender.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));
      sender.shutdownOutput();
      return new HttpCodec(receiver, 1000).readMessage(true, false);
    }
  }

  @Test
  void readsUtf8ByBytes() throws Exception {
    String body = "{\"text\":\"Tiếng Việt\"}";
    var message =
        parse(
            "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n"
                + body);
    assertEquals("Tiếng Việt", message.json().get("text").getAsString());
  }

  @Test
  void readsChunks() throws Exception {
    assertEquals(
        "{}",
        new String(
            parse(
                    "POST / HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "1\r\n"
                        + "{\r\n"
                        + "1\r\n"
                        + "}\r\n"
                        + "0\r\n\r\n")
                .body()));
  }

  @Test
  void rejectsAmbiguousLength() {
    assertThrows(
        HttpCodec.ProtocolException.class,
        () ->
            parse(
                "POST / HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Content-Length: 2\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n"
                    + "{}"));
    assertThrows(
        HttpCodec.ProtocolException.class,
        () ->
            parse(
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}"));
  }

  @Test
  void rejectsMissingHostAndOversizeAndEarlyEof() {
    assertThrows(HttpCodec.ProtocolException.class, () -> parse("GET / HTTP/1.1\r\n\r\n"));
    assertThrows(
        HttpCodec.ProtocolException.class,
        () -> parse("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1048577\r\n\r\n"));
    assertThrows(
        java.io.EOFException.class,
        () -> parse("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 9\r\n\r\n{}"));
  }
}
