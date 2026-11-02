package com.lanmanager.server.account;

import com.google.gson.*;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.security.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;

public final class AccountService {
  public record User(long id, String username, String fullName, String role) {}

  private record Login(User user, long expiresAt) {}

  private final ClassroomDatabase database;
  private final Map<String, Login> logins = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public AccountService(ClassroomDatabase database) {
    this.database = database;
  }

  public synchronized long createAccount(
      String username, String password, String fullName, String role) throws SQLException {
    if (!username.matches("[a-zA-Z0-9_.-]{3,50}")
        || password.length() < 10
        || password.length() > 200
        || fullName.isBlank()
        || fullName.length() > 200
        || !Set.of("TEACHER", "STUDENT").contains(role))
      throw new IllegalArgumentException(
          "Thông tin tài khoản không hợp lệ; mật khẩu cần ít nhất 10 ký tự");
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return database.insert(
        "INSERT INTO accounts(username,full_name,role,password_hash,salt) VALUES(?,?,?,?,?)",
        username,
        fullName,
        role,
        encode(hash(password, salt)),
        encode(salt));
  }

  public String login(String username, String password) throws SQLException {
    if (password.length() > 200) throw new SecurityException("Đăng nhập không hợp lệ");
    JsonArray rows =
        database.query("SELECT * FROM accounts WHERE username=? AND enabled=1", username);
    byte[] salt = new byte[16], expected = new byte[32];
    JsonObject row = rows.isEmpty() ? null : rows.get(0).getAsJsonObject();
    if (row != null) {
      salt = Base64.getDecoder().decode(row.get("salt").getAsString());
      expected = Base64.getDecoder().decode(row.get("password_hash").getAsString());
    }
    boolean matches = MessageDigest.isEqual(expected, hash(password, salt));
    if (row == null || !matches) throw new SecurityException("Tên đăng nhập hoặc mật khẩu sai");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = encode(bytes);
    logins.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
    if (logins.size() >= 500) throw new IllegalStateException("Quá nhiều phiên đăng nhập");
    logins.put(
        token,
        new Login(
            new User(
                row.get("id").getAsLong(),
                username,
                row.get("full_name").getAsString(),
                row.get("role").getAsString()),
            System.currentTimeMillis() + 8 * 3600_000L));
    return token;
  }

  public User authenticate(String token) throws SQLException {
    Login login = token == null ? null : logins.get(token);
    if (login == null
        || login.expiresAt() < System.currentTimeMillis()
        || database
            .query("SELECT id FROM accounts WHERE id=? AND enabled=1", login.user().id())
            .isEmpty()) throw new SecurityException("Phiên đăng nhập không hợp lệ");
    return login.user();
  }

  public void logout(String token) {
    logins.remove(token);
  }

  public JsonArray listAccounts(User teacher) throws SQLException {
    requireTeacher(teacher);
    return database.query(
        "SELECT id,username,full_name,role,enabled FROM accounts ORDER BY username");
  }

  public synchronized void setEnabled(User teacher, long accountId, boolean enabled)
      throws SQLException {
    requireTeacher(teacher);
    if (accountId == teacher.id() && !enabled)
      throw new IllegalArgumentException("Không thể tự khóa tài khoản đang dùng");
    database.transaction(
        () -> {
          if (database.execute(
                  "UPDATE accounts SET enabled=? WHERE id=?", enabled ? 1 : 0, accountId)
              != 1) throw new IllegalArgumentException("Không có tài khoản");
          if (!enabled)
            database.execute(
                "UPDATE student_sessions SET ended_at=?,end_reason='ACCOUNT_DISABLED' WHERE"
                    + " account_id=? AND ended_at IS NULL",
                System.currentTimeMillis(),
                accountId);
          database.execute(
              "INSERT INTO account_actions(teacher_id,account_id,action,at) VALUES(?,?,?,?)",
              teacher.id(),
              accountId,
              enabled ? "ENABLE" : "DISABLE",
              System.currentTimeMillis());
        });
    if (!enabled) logins.entrySet().removeIf(e -> e.getValue().user().id() == accountId);
  }

  public synchronized void resetPassword(User teacher, long accountId, String password)
      throws SQLException {
    requireTeacher(teacher);
    if (password.length() < 10 || password.length() > 200)
      throw new IllegalArgumentException("Mật khẩu cần 10–200 ký tự");
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    String passwordHash = encode(hash(password, salt));
    database.transaction(
        () -> {
          if (database.execute(
                  "UPDATE accounts SET password_hash=?,salt=? WHERE id=?",
                  passwordHash,
                  encode(salt),
                  accountId)
              != 1) throw new IllegalArgumentException("Không có tài khoản");
          database.execute(
              "UPDATE student_sessions SET ended_at=?,end_reason='PASSWORD_RESET' WHERE"
                  + " account_id=? AND ended_at IS NULL",
              System.currentTimeMillis(),
              accountId);
          database.execute(
              "INSERT INTO account_actions(teacher_id,account_id,action,at)"
                  + " VALUES(?,?,'RESET_PASSWORD',?)",
              teacher.id(),
              accountId,
              System.currentTimeMillis());
        });
    logins.entrySet().removeIf(e -> e.getValue().user().id() == accountId);
  }

  public static void requireTeacher(User user) {
    if (!user.role().equals("TEACHER")) throw new SecurityException("Chỉ giáo viên được thực hiện");
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static byte[] hash(String password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không tạo được mật khẩu", e);
    } finally {
      spec.clearPassword();
    }
  }
}
