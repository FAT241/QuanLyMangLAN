package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.sql.SQLException;

/** Thời gian làm bài độc lập với giờ vào/rời máy, giữ nguyên khi kết nối lại. */
public final class ExamService {
  private final ClassroomDatabase database;
  private final ClassroomService classroom;

  public ExamService(ClassroomDatabase database, ClassroomService classroom) {
    this.database = database;
    this.classroom = classroom;
  }

  public synchronized JsonObject start(User student, long studentSessionId) throws SQLException {
    synchronized (database) {
      long classId = requireExamSession(student, studentSessionId);
      database.execute(
          "INSERT INTO exam_attempts(class_id,account_id,started_at) VALUES(?,?,?) ON"
              + " CONFLICT(class_id,account_id) DO NOTHING",
          classId,
          student.id(),
          System.currentTimeMillis());
      var attempt = attempt(classId, student.id());
      if (!attempt.get("closed_at").isJsonNull())
        throw new IllegalStateException("Lượt làm bài đã kết thúc, không thể bắt đầu lại");
      return attempt;
    }
  }

  public synchronized JsonObject submit(User student, long studentSessionId) throws SQLException {
    synchronized (database) {
      long classId = requireExamSession(student, studentSessionId);
      long now = System.currentTimeMillis();
      if (attempt(classId, student.id()) == null)
        throw new IllegalStateException("Chưa bắt đầu làm bài");
      database.execute(
          "UPDATE exam_attempts SET submitted_at=?,closed_at=?,end_reason='SUBMITTED' WHERE"
              + " class_id=? AND account_id=? AND closed_at IS NULL",
          now,
          now,
          classId,
          student.id());
      return attempt(classId, student.id());
    }
  }

  private long requireExamSession(User student, long id) throws SQLException {
    classroom.requireStudentSession(student, id);
    var row =
        database
            .query(
                "SELECT c.id,c.type FROM class_sessions c JOIN student_sessions s ON"
                    + " s.class_id=c.id WHERE s.id=?",
                id)
            .get(0)
            .getAsJsonObject();
    if (!row.get("type").getAsString().equals("EXAM"))
      throw new IllegalArgumentException("Buổi này không phải kiểm tra");
    return row.get("id").getAsLong();
  }

  private JsonObject attempt(long classId, long accountId) throws SQLException {
    var rows =
        database.query(
            "SELECT * FROM exam_attempts WHERE class_id=? AND account_id=?", classId, accountId);
    return rows.isEmpty() ? null : rows.get(0).getAsJsonObject();
  }
}
