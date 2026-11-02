package com.lanmanager.common.protocol.http;

import com.google.gson.JsonObject;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.util.Map;

public record HttpMessage(String firstLine, Map<String, String> headers, byte[] body) {
  public int status() {
    return Integer.parseInt(firstLine.split(" ", 3)[1]);
  }

  public JsonObject json() {
    return JsonCodec.parse(body);
  }
}
