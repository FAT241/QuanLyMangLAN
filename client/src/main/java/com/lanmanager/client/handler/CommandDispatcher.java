package com.lanmanager.client.handler;

import com.google.gson.*;
import com.lanmanager.client.collector.*;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;

public final class CommandDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);
  private final ApplicationConfig config;

  public CommandDispatcher(ApplicationConfig config) {
    this.config = config;
  }

  public JsonObject handle(JsonObject command) {
    String id = JsonCodec.requiredText(command, "commandId", 100);
    try {
      String type = JsonCodec.requiredText(command, "type", 50);
      JsonObject payload = command.getAsJsonObject("payload");
      JsonObject result =
          switch (type) {
            case "TERMINATE_PROCESS" ->
                new ProcessControl(config.flag("dryRun", true)).terminate(payload);
            case "GET_ACTIVITY" ->
                new com.lanmanager.client.collector.ActivityCollector(config).collect(payload);
            case "GET_SCREENSHOT" -> new ScreenCaptureCollector().capture();
            case "GET_PROCESSES" -> new ProcessCollector().collect();
            case "GET_WEB_HISTORY" -> {
              if (!config.flag("allowWebHistory", true))
                throw new IllegalStateException("DISABLED");
              yield new BrowserHistoryReader().read(payload);
            }
            case "LOCK_SCREEN" -> lockScreen();
            default -> throw new IllegalArgumentException("UNSUPPORTED_COMMAND");
          };
      return JsonCodec.object("commandId", id, "ok", true, "payload", result);
    } catch (Exception e) {
      // Không ghi nội dung dữ liệu hoặc đường dẫn lịch sử trình duyệt.
      LOG.warn("Lệnh thất bại: {}", e.getClass().getSimpleName());
      String code = e.getMessage();
      if (code == null || !code.matches("[A-Z_]{2,40}")) code = "COMMAND_FAILED";
      return JsonCodec.object(
          "commandId",
          id,
          "ok",
          false,
          "error",
          JsonCodec.object("code", code, "message", errorMessage(code)));
    }
  }

  private String errorMessage(String code) {
    return switch (code) {
      case "DISABLED" -> "Máy sinh viên đang tắt tính năng đọc lịch sử web";
      case "BROWSER_NOT_FOUND" -> "Không tìm thấy lịch sử trong hồ sơ Default của trình duyệt";
      case "BROWSER_BUSY", "BROWSER_READ_FAILED" ->
          "Chưa đọc được lịch sử; thử lại hoặc đóng trình duyệt";
      case "SCREEN_UNAVAILABLE" -> "Máy con không có phiên màn hình tương tác để chụp";
      case "PROTECTED_PROCESS" ->
          "Không được đóng tiến trình hệ thống, Agent hoặc tiến trình của người dùng khác";
      case "PROCESS_CHANGED" -> "Tiến trình đã thay đổi; lấy lại danh sách ứng dụng trước khi đóng";
      case "PROCESS_GONE" -> "Ứng dụng đã kết thúc";
      case "PROCESS_REJECTED" -> "Hệ điều hành từ chối đóng ứng dụng";
      case "UNSUPPORTED_OS" -> "Hệ điều hành này chưa hỗ trợ lệnh khóa màn hình";
      default -> "Không thể thực hiện yêu cầu: " + code;
    };
  }

  private JsonObject lockScreen() throws Exception {
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))
      throw new IllegalStateException("UNSUPPORTED_OS");
    List<String> args = List.of("rundll32.exe", "user32.dll,LockWorkStation");
    if (config.flag("dryRun", true)) {
      LOG.info("Chế độ thử: {}", args);
      return JsonCodec.object("detail", "Chế độ thử, chưa thực hiện");
    }
    Process process =
        new ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("PROCESS_TIMEOUT");
    }
    if (process.exitValue() != 0) throw new IllegalStateException("OS_REJECTED");
    return JsonCodec.object("detail", "Hệ điều hành đã tiếp nhận yêu cầu");
  }
}
