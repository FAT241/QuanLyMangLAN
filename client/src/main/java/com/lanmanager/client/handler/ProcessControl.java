package com.lanmanager.client.handler;

import com.google.gson.JsonObject;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.file.Path;
import java.util.*;

/** Chỉ đóng tiến trình cùng người dùng, đúng tên và thời điểm khởi động đã quan sát. */
public final class ProcessControl {
  private final boolean dryRun;

  public ProcessControl(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public JsonObject terminate(JsonObject payload) {
    long pid = JsonCodec.requiredLong(payload, "pid", 1, Long.MAX_VALUE);
    long startedAt = JsonCodec.requiredLong(payload, "startedAt", 1, Long.MAX_VALUE);
    String name = JsonCodec.requiredText(payload, "name", 200);
    ProcessHandle process =
        ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("PROCESS_GONE"));
    var info = process.info();
    String actualName = info.command().map(p -> Path.of(p).getFileName().toString()).orElse("");
    String ownUser = ProcessHandle.current().info().user().orElse("");
    boolean ancestor = false;
    Optional<ProcessHandle> parent = ProcessHandle.current().parent();
    while (parent.isPresent()) {
      if (parent.get().pid() == pid) {
        ancestor = true;
        break;
      }
      parent = parent.get().parent();
    }
    Set<String> protectedNames =
        Set.of(
            "system",
            "registry",
            "smss.exe",
            "csrss.exe",
            "wininit.exe",
            "services.exe",
            "lsass.exe",
            "svchost.exe",
            "winlogon.exe",
            "dwm.exe",
            "explorer.exe",
            "java.exe",
            "javaw.exe");
    if (pid == ProcessHandle.current().pid()
        || ancestor
        || ownUser.isBlank()
        || !info.user().orElse("").equals(ownUser)
        || protectedNames.contains(actualName.toLowerCase(Locale.ROOT)))
      throw new SecurityException("PROTECTED_PROCESS");
    if (!name.equalsIgnoreCase(actualName)
        || info.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L) != startedAt)
      throw new IllegalArgumentException("PROCESS_CHANGED");
    if (dryRun) return JsonCodec.object("detail", "Chế độ thử, chưa đóng ứng dụng");
    if (!process.destroy()) throw new IllegalStateException("PROCESS_REJECTED");
    return JsonCodec.object(
        "detail", "Đã gửi yêu cầu đóng ứng dụng; kiểm tra lại danh sách tiến trình");
  }
}
