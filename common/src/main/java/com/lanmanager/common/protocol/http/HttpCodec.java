package com.lanmanager.common.protocol.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Đọc theo byte để không làm mất ranh giới giữa tiêu đề và thân. */
public final class HttpCodec {
  public static final int MAX_BODY = 1_048_576;

  public static final class ProtocolException extends IOException {
    public final int status;

    public ProtocolException(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final Socket socket;
  private final InputStream input;
  private final long deadline;

  public HttpCodec(Socket socket, int timeoutMs) throws IOException {
    this.socket = socket;
    input = new BufferedInputStream(socket.getInputStream());
    deadline = System.nanoTime() + timeoutMs * 1_000_000L;
  }

  private int read() throws IOException {
    long remaining = (deadline - System.nanoTime()) / 1_000_000;
    if (remaining <= 0) throw new SocketTimeoutException("Quá thời gian đọc HTTP");
    socket.setSoTimeout((int) Math.max(1, remaining));
    return input.read();
  }

  private String line(int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      int b = read();
      if (b < 0) throw new EOFException("HTTP chưa đầy đủ");
      if (b == '\r') {
        if (read() != '\n') throw bad("Thiếu CRLF");
        return out.toString(StandardCharsets.ISO_8859_1);
      }
      if (b == '\n' || out.size() >= limit) throw bad("Dòng HTTP không hợp lệ");
      out.write(b);
    }
  }

  private byte[] exact(int length) throws IOException {
    if (length < 0 || length > MAX_BODY) throw new ProtocolException(413, "Thân quá dài");
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      int b = read();
      if (b < 0) throw new EOFException("Thiếu thân HTTP");
      bytes[i] = (byte) b;
    }
    return bytes;
  }

  public HttpMessage readMessage(boolean request, boolean headResponse) throws IOException {
    String first = line(4096);
    String[] parts = first.split(" ", 3);
    if (parts.length != 3) throw bad("Dòng đầu sai");
    if (!(request ? parts[2] : parts[0]).equals("HTTP/1.1"))
      throw new ProtocolException(505, "Chỉ hỗ trợ HTTP/1.1");
    Map<String, String> headers = new HashMap<>();
    int total = 0;
    for (int count = 0; ; count++) {
      String line = line(16384);
      total += line.length() + 2;
      if (total > 16384 || count > 64) throw bad("Tiêu đề quá dài");
      if (line.isEmpty()) break;
      int colon = line.indexOf(':');
      if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
        throw bad("Tên trường sai");
      String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (value.chars().anyMatch(c -> c < 32 && c != 9)) throw bad("Giá trị trường sai");
      if (headers.putIfAbsent(name, value) != null) throw bad("Trường trùng");
    }
    if (request && (!headers.containsKey("host") || headers.get("host").isBlank()))
      throw bad("Thiếu Host");
    if (headers.containsKey("expect")) throw new ProtocolException(417, "Không hỗ trợ Expect");
    String length = headers.get("content-length"), transfer = headers.get("transfer-encoding");
    if (length != null && transfer != null) throw bad("Độ dài mâu thuẫn");
    int status = request ? 0 : Integer.parseInt(parts[1]);
    if (!request && (headResponse || status == 204 || status == 304 || status < 200))
      return new HttpMessage(first, headers, new byte[0]);
    byte[] body;
    if (transfer != null) {
      if (!transfer.equalsIgnoreCase("chunked"))
        throw new ProtocolException(501, "Mã hóa chưa hỗ trợ");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      while (true) {
        String sizeLine = line(1024);
        String size = sizeLine.split(";", 2)[0];
        if (!size.matches("[0-9a-fA-F]{1,8}")) throw bad("Kích thước khối sai");
        long n = Long.parseLong(size, 16);
        if (n + out.size() > MAX_BODY) throw new ProtocolException(413, "Thân quá dài");
        if (n == 0) {
          int trailerBytes = 0;
          for (int i = 0; ; i++) {
            String trailer = line(4096);
            trailerBytes += trailer.length();
            if (i > 64 || trailerBytes > 16384) throw bad("Tiêu đề cuối quá dài");
            if (trailer.isEmpty()) break;
            if (!trailer.matches("[A-Za-z0-9-]+:.*")) throw bad("Tiêu đề cuối sai");
          }
          break;
        }
        out.write(exact((int) n));
        if (!line(0).isEmpty()) throw bad("Thiếu kết thúc khối");
      }
      body = out.toByteArray();
    } else if (length != null) {
      if (!length.matches("[0-9]{1,10}")) throw bad("Content-Length sai");
      long n = Long.parseLong(length);
      if (n > MAX_BODY) throw new ProtocolException(413, "Thân quá dài");
      body = exact((int) n);
    } else if (request) {
      if (parts[0].equals("POST")) throw new ProtocolException(411, "Thiếu độ dài");
      body = new byte[0];
    } else {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      int b;
      while ((b = read()) >= 0) {
        if (out.size() >= MAX_BODY) throw new ProtocolException(413, "Thân quá dài");
        out.write(b);
      }
      body = out.toByteArray();
    }
    return new HttpMessage(first, Map.copyOf(headers), body);
  }

  private static ProtocolException bad(String reason) {
    return new ProtocolException(400, reason);
  }

  public static void write(
      OutputStream out, String first, Map<String, String> headers, byte[] body, boolean omitBody)
      throws IOException {
    StringBuilder text = new StringBuilder(first).append("\r\n");
    for (var entry : headers.entrySet())
      text.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
    text.append("\r\n");
    out.write(text.toString().getBytes(StandardCharsets.ISO_8859_1));
    if (!omitBody) out.write(body);
    out.flush();
  }

  public static Map<String, String> responseHeaders(int status, byte[] body) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Connection", "close");
    headers.put("Cache-Control", "no-store");
    headers.put(
        "Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
    if (status != 204) {
      headers.put("Content-Type", "application/json");
      headers.put("Content-Length", "" + body.length);
    }
    return headers;
  }
}
