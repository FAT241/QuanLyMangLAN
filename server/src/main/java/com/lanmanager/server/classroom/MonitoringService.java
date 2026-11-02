package com.lanmanager.server.classroom;

import com.google.gson.JsonObject;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.service.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/** Giữ kết quả ngắn hạn để HTTP không phải chờ chụp ảnh hoặc đọc trình duyệt. */
public final class MonitoringService {
  private record Pending(
      long teacherId,
      long studentSessionId,
      long expiresAt,
      String type,
      CompletableFuture<JsonObject> result) {}

  private final Map<String, Pending> pending = new HashMap<>();
  private final ClassroomService classroom;
  private final MachineRegistry machines;
  private final RequestService requests;
  private final com.lanmanager.server.storage.ClassroomDatabase database;
  private final EvidenceService evidence;

  public MonitoringService(
      ClassroomService classroom,
      MachineRegistry machines,
      RequestService requests,
      com.lanmanager.server.storage.ClassroomDatabase database,
      java.nio.file.Path evidenceDirectory) {
    this.classroom = classroom;
    this.machines = machines;
    this.requests = requests;
    this.database = database;
    evidence = new EvidenceService(database, classroom, evidenceDirectory);
  }

  public synchronized String request(
      User teacher, long studentSessionId, String type, JsonObject payload) throws SQLException {
    cleanup();
    if (!Set.of(
            "GET_SCREENSHOT",
            "GET_PROCESSES",
            "GET_WEB_HISTORY",
            "LOCK_SCREEN",
            "TERMINATE_PROCESS")
        .contains(type)) throw new IllegalArgumentException("Loại theo dõi không hợp lệ");
    var target = classroom.requireMonitoredMachine(teacher, studentSessionId);
    var machine = machines.get(target.machineId());
    if (machine == null || !machine.online() || !target.agentSession().equals(machine.sessionId()))
      throw new SecurityException("Máy đã đổi phiên; sinh viên cần tham gia lại");
    if (pending.size() >= 100)
      throw new IllegalStateException("Quá nhiều yêu cầu; lấy kết quả trước khi gửi thêm");
    String id = UUID.randomUUID().toString();
    boolean control = Set.of("LOCK_SCREEN", "TERMINATE_PROCESS").contains(type);
    long actionId =
        control
            ? database.insert(
                "INSERT INTO"
                    + " control_actions(teacher_id,student_session_id,type,details,requested_at,status)"
                    + " VALUES(?,?,?,?,?,'PENDING')",
                teacher.id(),
                studentSessionId,
                type,
                payload.toString(),
                System.currentTimeMillis())
            : 0;
    CompletableFuture<JsonObject> future;
    try {
      future = requests.send(machine, type, payload);
    } catch (RuntimeException e) {
      if (control)
        database.execute(
            "UPDATE control_actions SET status='FAILED',completed_at=?,result=? WHERE id=?",
            System.currentTimeMillis(),
            "Không xếp được lệnh",
            actionId);
      throw e;
    }
    pending.put(
        id,
        new Pending(
            teacher.id(), studentSessionId, System.currentTimeMillis() + 120000, type, future));
    if (control) {
      pending
          .get(id)
          .result()
          .whenComplete(
              (response, error) -> {
                try {
                  database.execute(
                      "UPDATE control_actions SET completed_at=?,status=?,result=? WHERE id=?",
                      System.currentTimeMillis(),
                      error == null && response.get("ok").getAsBoolean() ? "COMPLETED" : "FAILED",
                      error == null ? response.toString() : "Quá hạn hoặc mất kết nối",
                      actionId);
                } catch (SQLException e) {
                  org.slf4j.LoggerFactory.getLogger(MonitoringService.class)
                      .error("Không lưu kết quả điều khiển", e);
                }
              });
    }
    return id;
  }

  public synchronized JsonObject result(User teacher, String id) throws SQLException {
    cleanup();
    Pending item = pending.get(id);
    if (item == null
        || item.teacherId() != teacher.id()
        || item.expiresAt() < System.currentTimeMillis())
      throw new SecurityException("Không có kết quả hoặc kết quả đã hết hạn");
    // Kết quả đã chụp đúng phiên vẫn được xem/gắn bằng chứng sau khi buổi kết thúc.
    classroom.requireSessionTeacher(teacher, item.studentSessionId());
    if (!item.result().isDone()) return JsonCodec.object("status", "PENDING");
    try {
      return JsonCodec.object("status", "COMPLETED", "result", item.result().join());
    } catch (CompletionException | CancellationException e) {
      return JsonCodec.object(
          "status", "FAILED", "error", "Không có phản hồi hợp lệ trong thời gian chờ");
    }
  }

  public synchronized void cleanup() {
    pending.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
  }

  public synchronized long saveEvidence(User teacher, long violationId, String requestId)
      throws Exception {
    Pending item = pending.get(requestId);
    if (item == null || !item.type().equals("GET_SCREENSHOT"))
      throw new IllegalArgumentException("Chọn yêu cầu ảnh màn hình");
    var response = result(teacher, requestId);
    if (!response.get("status").getAsString().equals("COMPLETED")
        || !response.getAsJsonObject("result").get("ok").getAsBoolean())
      throw new IllegalStateException("Chưa có ảnh thành công");
    return evidence.save(
        teacher,
        violationId,
        item.studentSessionId(),
        response.getAsJsonObject("result").getAsJsonObject("payload"));
  }

  public JsonObject readEvidence(User teacher, long evidenceId) throws Exception {
    return evidence.read(teacher, evidenceId);
  }
}
