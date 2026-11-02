package com.lanmanager.common.ui;

import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.charset.StandardCharsets;

/** Nội dung dễ đọc, giữ nguyên ghi chú thủ công không phải JSON. */
public final class ActivityText {
  private ActivityText() {}

  public static String describe(String source) {
    int start = source.indexOf('{');
    if (start < 0) return source;
    try {
      var body = JsonCodec.parse(source.substring(start).getBytes(StandardCharsets.UTF_8));
      String value;
      if (body.has("url"))
        value =
            body.get("url").getAsString()
                + " | "
                + (body.has("title") && !body.get("title").isJsonNull()
                    ? body.get("title").getAsString()
                    : "");
      else if (body.has("name")) value = body.get("name").getAsString();
      else return source;
      return source.substring(0, start) + value;
    } catch (RuntimeException e) {
      return source;
    }
  }
}
