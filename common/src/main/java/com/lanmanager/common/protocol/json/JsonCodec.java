package com.lanmanager.common.protocol.json;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Chuyển đổi JSON, dùng chung cho thân HTTP và gói UDP. */
public final class JsonCodec {
  public static final Gson GSON = new GsonBuilder().serializeNulls().create();

  private JsonCodec() {}

  public static byte[] bytes(Object value) {
    return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
  }

  public static JsonObject parse(byte[] value) {
    return JsonParser.parseString(new String(value, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  public static JsonObject object(Object... pairs) {
    JsonObject result = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2)
      result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
    return result;
  }

  public static String requiredText(JsonObject value, String key, int max) {
    JsonElement field = value.get(key);
    if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Thiếu chuỗi: " + key);
    String text = field.getAsString();
    if (text.isBlank() || text.length() > max)
      throw new IllegalArgumentException("Độ dài không hợp lệ: " + key);
    return text;
  }

  public static long requiredLong(JsonObject value, String key, long min, long max) {
    JsonElement field = value.get(key);
    if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Thiếu số: " + key);
    long number = new java.math.BigDecimal(field.getAsString()).longValueExact();
    if (number < min || number > max)
      throw new IllegalArgumentException("Số ngoài giới hạn: " + key);
    return number;
  }
}
