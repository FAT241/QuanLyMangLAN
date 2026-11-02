package com.lanmanager.common.protocol.http;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;

public final class ClassroomClient {
  /** Giao diện phân biệt phiên hết hạn với lỗi mạng để đưa về màn hình đăng nhập. */
  public static final class RequestException extends IOException {
    private final int statusCode;

    RequestException(int statusCode, String message) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  private ClassroomClient() {}

  public static JsonObject call(
      HttpTransport transport, String route, JsonObject body, String token, String agentSession)
      throws IOException {
    Map<String, String> headers = new HashMap<>();
    if (token != null) headers.put("X-User-Token", token);
    if (agentSession != null) headers.put("X-Lan-Session", agentSession);
    var response = transport.send("POST", "/api/v1/classroom/" + route, body, headers);
    JsonObject result = response.json();
    if (response.status() >= 400) {
      String message = "Yêu cầu bị từ chối (" + response.status() + ")";
      if (result.has("error") && result.get("error").isJsonObject())
        message = result.getAsJsonObject("error").get("message").getAsString();
      throw new RequestException(response.status(), message);
    }
    return result;
  }
}
