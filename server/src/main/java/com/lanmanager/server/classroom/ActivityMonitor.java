package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.service.*;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;

public final class ActivityMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(ActivityMonitor.class);
  private final ClassroomDatabase database;
  private final ActivityRecorder recorder;
  private final MachineRegistry machines;
  private final RequestService requests;
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final Map<Long, JsonObject> cursors = new ConcurrentHashMap<>();

  public ActivityMonitor(
      ClassroomDatabase database, MachineRegistry machines, RequestService requests) {
    this.database = database;
    recorder = new ActivityRecorder(database);
    this.machines = machines;
    this.requests = requests;
  }

  public void collect() {
    try {
      recorder.closeFinishedIntervals();
      Set<Long> active = new HashSet<>();
      for (var item : database.query("SELECT * FROM student_sessions WHERE ended_at IS NULL")) {
        var row = item.getAsJsonObject();
        long id = row.get("id").getAsLong();
        active.add(id);
        var machine = machines.get(row.get("machine_id").getAsString());
        if (machine == null
            || !machine.online()
            || !machine.sessionId().equals(row.get("agent_session").getAsString())
            || !inFlight.add(id)) continue;
        JsonObject cursor =
            cursors.computeIfAbsent(
                id, key -> JsonCodec.object("sinceEpochMs", row.get("signed_in_at").getAsLong()));
        try {
          requests
              .send(machine, "GET_ACTIVITY", cursor.deepCopy())
              .whenComplete(
                  (result, error) -> {
                    try {
                      if (error != null || !result.get("ok").getAsBoolean()) {
                        recorder.recordError(
                            id, "ACTIVITY_FAILED", "Không lấy được dữ liệu hoạt động", false);
                        return;
                      }
                      var payload = result.getAsJsonObject("payload");
                      recorder.record(id, payload);
                      recorder.recordError(id, "ACTIVITY_FAILED", "Đã kết nối lại", true);
                      for (var entry : payload.getAsJsonArray("browsers")) {
                        var browser = entry.getAsJsonObject();
                        if (browser.has("error")) continue;
                        var entries = browser.getAsJsonArray("entries");
                        if (entries.isEmpty()) continue;
                        var last = entries.get(entries.size() - 1).getAsJsonObject();
                        cursor.add(
                            browser.get("browser").getAsString(),
                            JsonCodec.object(
                                "sinceEpochMs",
                                last.get("visitTime").getAsLong(),
                                "afterVisitId",
                                last.get("visitId").getAsLong()));
                      }
                    } catch (Exception e) {
                      LOG.warn(
                          "Không ghi được hoạt động phiên {}: {}",
                          id,
                          e.getClass().getSimpleName());
                    } finally {
                      inFlight.remove(id);
                    }
                  });
        } catch (RuntimeException e) {
          inFlight.remove(id);
          LOG.warn("Không xếp được lệnh thu thập phiên {}", id);
        }
      }
      cursors.keySet().retainAll(active);
    } catch (Exception e) {
      LOG.error("Lỗi thu thập hoạt động", e);
    }
  }
}
