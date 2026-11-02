package com.lanmanager.client.collector;

import com.google.gson.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.json.JsonCodec;

public final class ActivityCollector {
  private final ApplicationConfig config;

  public ActivityCollector(ApplicationConfig config) {
    this.config = config;
  }

  public JsonObject collect(JsonObject request) {
    JsonObject result = new ProcessCollector().collect();
    JsonArray browsers = new JsonArray();
    for (String browser : new String[] {"chrome", "edge"}) {
      try {
        if (!config.flag("allowWebHistory", true)) throw new IllegalStateException("DISABLED");
        JsonObject cursor =
            request.has(browser)
                ? request.getAsJsonObject(browser)
                : JsonCodec.object(
                    "sinceEpochMs",
                    JsonCodec.requiredLong(request, "sinceEpochMs", 0, Long.MAX_VALUE));
        JsonObject query = cursor.deepCopy();
        query.addProperty("browser", browser);
        query.addProperty("limit", 200);
        browsers.add(new BrowserHistoryReader().read(query));
      } catch (Exception e) {
        String code = e.getMessage();
        if (code == null || !code.matches("[A-Z_]{2,60}")) code = "BROWSER_READ_FAILED";
        browsers.add(JsonCodec.object("browser", browser, "error", code));
      }
    }
    result.add("browsers", browsers);
    return result;
  }
}
