package com.lanmanager.server.classroom;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService.User;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

public final class EvidenceService {
  private final ClassroomDatabase database;
  private final ClassroomService classroom;
  private final Path directory;

  public EvidenceService(ClassroomDatabase database, ClassroomService classroom, Path directory) {
    this.database = database;
    this.classroom = classroom;
    this.directory = directory.toAbsolutePath().normalize();
  }

  public long save(User teacher, long violationId, long studentSessionId, JsonObject screenshot)
      throws Exception {
    long target = classroom.requireViolationSession(teacher, violationId);
    if (target != studentSessionId)
      throw new SecurityException("Ảnh không thuộc phiên của đánh dấu");
    long capturedAt =
        JsonCodec.requiredLong(screenshot, "capturedAt", 0, System.currentTimeMillis() + 300000);
    var session =
        database
            .query("SELECT signed_in_at,ended_at FROM student_sessions WHERE id=?", target)
            .get(0)
            .getAsJsonObject();
    if (capturedAt < session.get("signed_in_at").getAsLong()
        || (!session.get("ended_at").isJsonNull()
            && capturedAt > session.get("ended_at").getAsLong()))
      throw new IllegalArgumentException("Ảnh không nằm trong phiên");
    byte[] bytes = Base64.getDecoder().decode(JsonCodec.requiredText(screenshot, "base64", 900000));
    if (bytes.length > 650000) throw new IllegalArgumentException("Ảnh quá lớn");
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IllegalArgumentException("Không phải ảnh hợp lệ");
      var reader = readers.next();
      try {
        reader.setInput(input);
        if (!reader.getFormatName().equalsIgnoreCase("JPEG")
            || reader.getWidth(0) > 1280
            || reader.getHeight(0) > 720)
          throw new IllegalArgumentException("Kích thước/định dạng ảnh không hợp lệ");
      } finally {
        reader.dispose();
      }
    }
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Files.createDirectories(directory);
    String filename = UUID.randomUUID() + ".jpg";
    Path output = directory.resolve(filename);
    Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
    final long[] id = new long[1];
    try {
      database.transaction(
          () -> {
            id[0] =
                database.insert(
                    "INSERT INTO evidence(violation_id,path,sha256,captured_at,saved_at,teacher_id)"
                        + " VALUES(?,?,?,?,?,?)",
                    violationId,
                    filename,
                    hash,
                    capturedAt,
                    System.currentTimeMillis(),
                    teacher.id());
            database.execute(
                "INSERT INTO teacher_actions(teacher_id,violation_id,action,new_value,at)"
                    + " VALUES(?,?,'SAVE_EVIDENCE',?,?)",
                teacher.id(),
                violationId,
                hash,
                System.currentTimeMillis());
          });
      return id[0];
    } catch (Exception e) {
      Files.deleteIfExists(output);
      throw e;
    }
  }

  public JsonObject read(User teacher, long evidenceId) throws Exception {
    var rows = database.query("SELECT * FROM evidence WHERE id=?", evidenceId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Không có ảnh bằng chứng");
    var row = rows.get(0).getAsJsonObject();
    classroom.requireViolationSession(teacher, row.get("violation_id").getAsLong());
    if (!row.get("purged_at").isJsonNull())
      throw new IllegalStateException("Ảnh đã hết thời hạn lưu; bản ghi sự việc vẫn được giữ");
    Path path = directory.resolve(row.get("path").getAsString()).normalize();
    if (!path.startsWith(directory)) throw new SecurityException("Đường dẫn không hợp lệ");
    byte[] bytes = Files.readAllBytes(path);
    if (!HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        .equals(row.get("sha256").getAsString()))
      throw new IllegalStateException("Ảnh đã bị thay đổi");
    return JsonCodec.object(
        "mimeType",
        "image/jpeg",
        "base64",
        Base64.getEncoder().encodeToString(bytes),
        "capturedAt",
        row.get("captured_at"));
  }

  public void purgeExpired(int days) throws Exception {
    if (days <= 0) return;
    long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(days);
    for (var item :
        database.query(
            "SELECT id,path FROM evidence WHERE purged_at IS NULL AND saved_at<? ORDER BY id LIMIT"
                + " 100",
            cutoff)) {
      var row = item.getAsJsonObject();
      String filename = row.get("path").getAsString();
      if (!filename.matches("[a-f0-9-]{36}\\.jpg"))
        throw new SecurityException("Tên tệp bằng chứng không hợp lệ");
      Path target = directory.resolve(filename).normalize();
      if (!target.startsWith(directory)) throw new SecurityException("Đường dẫn không hợp lệ");
      Files.deleteIfExists(target);
      database.execute(
          "UPDATE evidence SET purged_at=? WHERE id=?",
          System.currentTimeMillis(),
          row.get("id").getAsLong());
    }
  }
}
