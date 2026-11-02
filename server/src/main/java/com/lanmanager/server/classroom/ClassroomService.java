package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.service.MachineRegistry;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.sql.SQLException;
import java.util.Set;

/** Nghiệp vụ buổi học, phiên sinh viên và ghi nhận sự việc. */
public final class ClassroomService {
  private final ClassroomDatabase database;

  public ClassroomService(ClassroomDatabase database) throws SQLException {
    this.database = database;
    database.execute(
        "UPDATE student_sessions SET ended_at=?,end_reason='SERVER_INTERRUPTED' WHERE ended_at IS"
            + " NULL",
        System.currentTimeMillis());
  }

  public synchronized long startClass(User teacher, String name, String type) throws SQLException {
    AccountService.requireTeacher(teacher);
    if (name.isBlank() || name.length() > 200 || !Set.of("CLASS", "EXAM").contains(type))
      throw new IllegalArgumentException("Tên hoặc loại buổi không hợp lệ");
    return database.insert(
        "INSERT INTO class_sessions(name,type,teacher_id,started_at) VALUES(?,?,?,?)",
        name,
        type,
        teacher.id(),
        System.currentTimeMillis());
  }

  public synchronized void endClass(User teacher, long id) throws SQLException {
    requireClassTeacher(teacher, id);
    long now = System.currentTimeMillis();
    database.transaction(
        () -> {
          database.execute(
              "UPDATE class_sessions SET ended_at=? WHERE id=? AND ended_at IS NULL", now, id);
          database.execute(
              "UPDATE student_sessions SET ended_at=?,end_reason='CLASS_ENDED' WHERE class_id=? AND"
                  + " ended_at IS NULL",
              now,
              id);
          database.execute(
              "UPDATE exam_attempts SET closed_at=?,end_reason='CLASS_ENDED' WHERE class_id=? AND"
                  + " closed_at IS NULL",
              now,
              id);
        });
  }

  /** Chỉ xóa buổi tạo nhầm; lịch sử tham gia và lượt thi phải được giữ lại. */
  public synchronized void deleteClass(User teacher, long id) throws SQLException {
    database.transaction(
        () -> {
          requireClassTeacher(teacher, id);
          var data =
              database.query(
                  "SELECT 1 FROM student_sessions WHERE class_id=? UNION ALL SELECT 1 FROM"
                      + " exam_attempts WHERE class_id=? UNION ALL SELECT 1 FROM rule_alerts a JOIN"
                      + " monitor_rules r ON r.id=a.rule_id WHERE r.class_id=? UNION ALL SELECT 1"
                      + " FROM rule_scan_offsets s JOIN monitor_rules r ON r.id=s.rule_id WHERE"
                      + " r.class_id=? LIMIT 1",
                  id,
                  id,
                  id,
                  id);
          if (!data.isEmpty())
            throw new IllegalStateException(
                "Buổi đã có dữ liệu tham gia hoặc làm bài nên không thể xóa. "
                    + "Hãy kết thúc buổi; lịch sử sẽ được giữ để tra cứu.");
          database.execute("DELETE FROM monitor_rules WHERE class_id=?", id);
          database.execute("DELETE FROM class_sessions WHERE id=?", id);
        });
  }

  public synchronized long joinClass(User student, long classId, MachineRegistry.Machine machine)
      throws SQLException {
    if (!student.role().equals("STUDENT"))
      throw new SecurityException("Chỉ tài khoản sinh viên được tham gia");
    if (machine == null || !machine.online())
      throw new SecurityException("Máy con chưa đăng ký hoặc mất kết nối");
    if (database
        .query("SELECT id FROM class_sessions WHERE id=? AND ended_at IS NULL", classId)
        .isEmpty()) throw new IllegalArgumentException("Buổi học không mở");
    if (!database
        .query(
            "SELECT id FROM student_sessions WHERE ended_at IS NULL AND (account_id=? OR"
                + " machine_id=?)",
            student.id(),
            machine.id())
        .isEmpty())
      throw new IllegalStateException("Sinh viên hoặc máy đã có phiên; kết thúc phiên cũ trước");
    long now = System.currentTimeMillis();
    return database.insert(
        "INSERT INTO"
            + " student_sessions(account_id,machine_id,agent_session,class_id,signed_in_at,last_seen_at)"
            + " VALUES(?,?,?,?,?,?)",
        student.id(),
        machine.id(),
        machine.sessionId(),
        classId,
        now,
        now);
  }

  public synchronized void leaveClass(User student, long id) throws SQLException {
    requireStudentSession(student, id);
    database.execute(
        "UPDATE student_sessions SET ended_at=?,end_reason='SIGNED_OUT' WHERE id=? AND ended_at IS"
            + " NULL",
        System.currentTimeMillis(),
        id);
  }

  public synchronized void checkStudentConnections(MachineRegistry machines) throws SQLException {
    for (var item : database.query("SELECT * FROM student_sessions WHERE ended_at IS NULL")) {
      var row = item.getAsJsonObject();
      var machine = machines.get(row.get("machine_id").getAsString());
      long id = row.get("id").getAsLong();
      if (machine == null
          || !machine.online()
          || !row.get("agent_session").getAsString().equals(machine.sessionId())) {
        database.execute(
            "UPDATE student_sessions SET ended_at=?,end_reason='CONNECTION_LOST' WHERE id=?",
            System.currentTimeMillis(),
            id);
      } else
        database.execute(
            "UPDATE student_sessions SET last_seen_at=? WHERE id=?", machine.lastSeen(), id);
    }
  }

  public synchronized long recordActivity(User student, long sessionId, JsonObject body)
      throws SQLException {
    requireStudentSession(student, sessionId);
    String kind = JsonCodec.requiredText(body, "kind", 20),
        key = JsonCodec.requiredText(body, "sourceKey", 200),
        details = JsonCodec.requiredText(body, "details", 8000);
    if (!Set.of("WEB", "PROCESS").contains(kind))
      throw new IllegalArgumentException("Loại hoạt động sai");
    long sourceAt =
        JsonCodec.requiredLong(body, "sourceAt", 0, System.currentTimeMillis() + 300000);
    var session =
        database
            .query("SELECT signed_in_at FROM student_sessions WHERE id=?", sessionId)
            .get(0)
            .getAsJsonObject();
    if (sourceAt < session.get("signed_in_at").getAsLong())
      throw new IllegalArgumentException("Hoạt động nằm ngoài phiên");
    database.execute(
        "INSERT INTO"
            + " activity_records(student_session_id,kind,source_key,details,source_at,observed_at)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT(student_session_id,kind,source_key) DO NOTHING",
        sessionId,
        kind,
        key,
        details,
        sourceAt,
        System.currentTimeMillis());
    return database
        .query(
            "SELECT id FROM activity_records WHERE student_session_id=? AND kind=? AND"
                + " source_key=?",
            sessionId,
            kind,
            key)
        .get(0)
        .getAsJsonObject()
        .get("id")
        .getAsLong();
  }

  public synchronized long flagViolation(
      User teacher, long sessionId, String details, long occurredAt) throws SQLException {
    requireSessionTeacher(teacher, sessionId);
    long now = System.currentTimeMillis();
    var session =
        database
            .query("SELECT signed_in_at,ended_at FROM student_sessions WHERE id=?", sessionId)
            .get(0)
            .getAsJsonObject();
    long end = session.get("ended_at").isJsonNull() ? now : session.get("ended_at").getAsLong();
    if (details.isBlank()
        || details.length() > 8000
        || occurredAt < session.get("signed_in_at").getAsLong()
        || occurredAt > end)
      throw new IllegalArgumentException("Sự việc phải nằm trong phiên sinh viên");
    final long[] id = new long[1];
    database.transaction(
        () -> {
          id[0] =
              database.insert(
                  "INSERT INTO"
                      + " violations(student_session_id,details,occurred_at,teacher_id,updated_at)"
                      + " VALUES(?,?,?,?,?)",
                  sessionId,
                  details,
                  occurredAt,
                  teacher.id(),
                  now);
          database.execute(
              "INSERT INTO teacher_actions(teacher_id,violation_id,action,new_value,at)"
                  + " VALUES(?,?,'FLAG',?,?)",
              teacher.id(),
              id[0],
              details,
              now);
        });
    return id[0];
  }

  public synchronized void reviewViolation(
      User teacher, long violationId, String status, int points, String note) throws SQLException {
    JsonArray rows = database.query("SELECT * FROM violations WHERE id=?", violationId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Không có đánh dấu này");
    JsonObject before = rows.get(0).getAsJsonObject();
    requireSessionTeacher(teacher, before.get("student_session_id").getAsLong());
    if (!Set.of("CONFIRMED", "DISMISSED").contains(status)
        || points < 0
        || points > 100
        || (status.equals("DISMISSED") && points != 0)
        || note.isBlank()
        || note.length() > 2000)
      throw new IllegalArgumentException("Điểm trừ/ghi chú không hợp lệ");
    long now = System.currentTimeMillis();
    database.transaction(
        () -> {
          database.execute(
              "UPDATE violations SET status=?,penalty_points=?,note=?,updated_at=? WHERE id=?",
              status,
              points,
              note,
              now,
              violationId);
          database.execute(
              "INSERT INTO"
                  + " teacher_actions(teacher_id,violation_id,action,previous_value,new_value,at)"
                  + " VALUES(?,?,'REVIEW',?,?,?)",
              teacher.id(),
              violationId,
              before.toString(),
              JsonCodec.object("status", status, "points", points, "note", note).toString(),
              now);
        });
  }

  public synchronized void recordError(
      User student, long sessionId, String code, String message, boolean resolved)
      throws SQLException {
    requireStudentSession(student, sessionId);
    long now = System.currentTimeMillis();
    if (!code.matches("[A-Z_]{2,60}") || message.isBlank() || message.length() > 2000)
      throw new IllegalArgumentException("Thông tin lỗi không hợp lệ");
    if (resolved)
      database.execute(
          "UPDATE system_errors SET resolved_at=? WHERE student_session_id=? AND code=? AND"
              + " resolved_at IS NULL",
          now,
          sessionId,
          code);
    else {
      int count =
          database.execute(
              "UPDATE system_errors SET"
                  + " message=?,last_seen_at=?,occurrence_count=occurrence_count+1 WHERE"
                  + " student_session_id=? AND code=? AND resolved_at IS NULL",
              message,
              now,
              sessionId,
              code);
      if (count == 0)
        database.execute(
            "INSERT INTO system_errors(student_session_id,code,message,first_seen_at,last_seen_at)"
                + " VALUES(?,?,?,?,?)",
            sessionId,
            code,
            message,
            now,
            now);
    }
  }

  public synchronized JsonObject report(User teacher, long classId) throws SQLException {
    return new ClassroomReportService(database, this).report(teacher, classId);
  }

  public synchronized JsonArray listClasses(User user) throws SQLException {
    return user.role().equals("TEACHER")
        ? database.query(
            "SELECT * FROM class_sessions WHERE teacher_id=? ORDER BY id DESC", user.id())
        : database.query(
            "SELECT id,name,type,started_at,ended_at FROM class_sessions WHERE ended_at IS NULL"
                + " ORDER BY id DESC");
  }

  public record MonitoredMachine(String machineId, String agentSession) {}

  public synchronized MonitoredMachine requireMonitoredMachine(User teacher, long sessionId)
      throws SQLException {
    requireSessionTeacher(teacher, sessionId);
    var rows =
        database.query(
            "SELECT machine_id,agent_session FROM student_sessions WHERE id=? AND ended_at IS NULL",
            sessionId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Phiên đã kết thúc");
    var row = rows.get(0).getAsJsonObject();
    return new MonitoredMachine(
        row.get("machine_id").getAsString(), row.get("agent_session").getAsString());
  }

  public synchronized void signOutStudent(User user) throws SQLException {
    if (user.role().equals("STUDENT"))
      database.execute(
          "UPDATE student_sessions SET ended_at=?,end_reason='SIGNED_OUT' WHERE account_id=? AND"
              + " ended_at IS NULL",
          System.currentTimeMillis(),
          user.id());
  }

  public synchronized long requireViolationSession(User teacher, long violationId)
      throws SQLException {
    var rows = database.query("SELECT student_session_id FROM violations WHERE id=?", violationId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Không có đánh dấu");
    long sessionId = rows.get(0).getAsJsonObject().get("student_session_id").getAsLong();
    requireSessionTeacher(teacher, sessionId);
    return sessionId;
  }

  public synchronized JsonObject studentStatus(User student) throws SQLException {
    var rows =
        database.query(
            "SELECT id,class_id,machine_id,signed_in_at FROM student_sessions WHERE account_id=?"
                + " AND ended_at IS NULL",
            student.id());
    return JsonCodec.object(
        "sessions",
        rows,
        "attempts",
        database.query(
            "SELECT * FROM exam_attempts WHERE account_id=? ORDER BY id DESC LIMIT 50",
            student.id()));
  }

  void requireClassTeacher(User teacher, long classId) throws SQLException {
    AccountService.requireTeacher(teacher);
    if (database
        .query("SELECT id FROM class_sessions WHERE id=? AND teacher_id=?", classId, teacher.id())
        .isEmpty()) throw new SecurityException("Không có quyền với buổi này");
  }

  void requireSessionTeacher(User teacher, long sessionId) throws SQLException {
    var rows = database.query("SELECT class_id FROM student_sessions WHERE id=?", sessionId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Không có phiên sinh viên");
    requireClassTeacher(teacher, rows.get(0).getAsJsonObject().get("class_id").getAsLong());
  }

  void requireStudentSession(User student, long sessionId) throws SQLException {
    if (!student.role().equals("STUDENT")
        || database
            .query(
                "SELECT s.id FROM student_sessions s JOIN class_sessions c ON c.id=s.class_id WHERE"
                    + " s.id=? AND s.account_id=? AND s.ended_at IS NULL AND c.ended_at IS NULL",
                sessionId,
                student.id())
            .isEmpty()) throw new SecurityException("Phiên sinh viên không hợp lệ");
  }
}
