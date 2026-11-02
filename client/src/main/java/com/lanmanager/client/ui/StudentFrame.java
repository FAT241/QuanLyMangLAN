package com.lanmanager.client.ui;

import com.google.gson.*;
import com.lanmanager.client.ClientAgent;
import com.lanmanager.common.protocol.http.ClassroomClient;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;

/** Điều hướng đăng nhập, chọn buổi và buổi đang học; gọi mạng ở luồng nền. */
public final class StudentFrame extends JFrame {
  private record SignedIn(String token, JsonObject user) {}

  private record LobbyData(List<ClassChoice> classes, JsonObject status) {}

  private final ClientAgent agent;
  private final CardLayout navigation = new CardLayout();
  private final JPanel pages = new JPanel(navigation);
  private final LoginPanel login =
      new LoginPanel(
          "Sinh viên",
          "Tham gia buổi học, theo dõi thời gian làm bài và nhận hướng dẫn từ giáo viên.",
          "Dùng tài khoản do giáo viên cung cấp. Nếu quên mật khẩu, liên hệ giáo viên phụ trách.");
  private final ClassSelectionPanel selection;
  private final StudySessionPanel study;
  private final JPanel header = AppTheme.transparent(new BorderLayout(24, 0));
  private final JLabel identity = AppTheme.title(" ", 17);
  private final JLabel connection = AppTheme.muted("Đang kết nối máy chủ…");
  private final JLabel status = AppTheme.muted(" ");
  private final JButton logout = AppTheme.button("Đăng xuất", false);
  private final javax.swing.Timer timer;
  private String token;
  private ClassChoice currentClass;
  private long studentSession, screenVersion;
  private boolean busy, checking, closing;
  private int ticks;

  public StudentFrame(ClientAgent agent) {
    super("Phòng máy — Sinh viên");
    this.agent = agent;
    selection = new ClassSelectionPanel(this::loadClasses, this::joinClass);
    study =
        new StudySessionPanel(() -> examAction(false), () -> examAction(true), this::leaveClass);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    AppTheme.fitWindow(this, 960, 700, 900, 660);
    JPanel root = new JPanel(new BorderLayout());
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.BORDER),
            BorderFactory.createEmptyBorder(18, 32, 18, 32)));
    JPanel brand = AppTheme.transparent(new GridLayout(0, 1, 0, 4));
    brand.add(AppTheme.muted("PHÒNG MÁY · SINH VIÊN"));
    brand.add(identity);
    header.add(brand);
    header.add(logout, BorderLayout.EAST);
    root.add(header, BorderLayout.NORTH);
    pages.add(login, "login");
    pages.add(selection, "classes");
    pages.add(study, "study");
    root.add(pages);
    JPanel footer = AppTheme.transparent(new BorderLayout(24, 0));
    footer.setBorder(BorderFactory.createEmptyBorder(10, 24, 12, 24));
    footer.add(status);
    footer.add(connection, BorderLayout.EAST);
    root.add(footer, BorderLayout.SOUTH);
    setContentPane(root);
    login.onLogin(this::signIn);
    logout.addActionListener(e -> logout());
    header.setVisible(false);
    getRootPane().setDefaultButton(login.defaultButton());
    updateAvailability();
    timer =
        new javax.swing.Timer(
            3000,
            e -> {
              updateAvailability();
              if (token != null && agent.registered() && !busy && !checking) {
                if (studentSession == 0 && ++ticks % 5 == 0) loadClasses();
                else refreshSessionStatus();
              }
            });
    timer.start();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent e) {
            login.focusUsername();
          }

          @Override
          public void windowClosing(WindowEvent e) {
            closeWindow();
          }

          @Override
          public void windowClosed(WindowEvent e) {
            timer.stop();
          }
        });
  }

  private void signIn(String name, char[] chars) {
    String secret = new String(chars);
    Arrays.fill(chars, '\0');
    if (!agent.registered()) {
      login.showError("Chưa kết nối được máy chủ. Kiểm tra máy giáo viên đã mở chương trình.");
      return;
    }
    perform(
        () -> {
          String access =
              agent
                  .classroomCall(
                      "login", JsonCodec.object("username", name, "password", secret), null)
                  .get("accessToken")
                  .getAsString();
          try {
            JsonObject user = agent.classroomCall("me", JsonCodec.object(), access);
            if (!user.get("role").getAsString().equals("STUDENT"))
              throw new IllegalArgumentException("Hãy dùng tài khoản sinh viên tại máy này.");
            return new SignedIn(access, user);
          } catch (Exception error) {
            try {
              agent.classroomCall("logout", JsonCodec.object(), access);
            } catch (Exception ignored) {
              /* Giữ nguyên lỗi đăng nhập để hiển thị. */
            }
            throw error;
          }
        },
        result -> {
          token = result.token();
          screenVersion++;
          identity.setText(result.user().get("fullName").getAsString());
          header.setVisible(true);
          getRootPane().setDefaultButton(null);
          navigation.show(pages, "classes");
          status.setText("Đã đăng nhập. Chọn một buổi để tham gia.");
          loadClasses();
        });
  }

  private void loadClasses() {
    if (token == null || busy) return;
    String access = token;
    perform(
        () -> {
          JsonObject response = agent.classroomCall("classes/list", JsonCodec.object(), access);
          List<ClassChoice> choices = ClassChoice.openClasses(response.getAsJsonArray("classes"));
          return new LobbyData(
              choices, agent.classroomCall("students/status", JsonCodec.object(), access));
        },
        result -> {
          selection.setClasses(result.classes());
          for (JsonElement item : result.status().getAsJsonArray("sessions")) {
            JsonObject row = item.getAsJsonObject();
            if (!row.get("machine_id").getAsString().equals(agent.machineId())) continue;
            for (ClassChoice choice : result.classes()) {
              if (choice.id() == row.get("class_id").getAsLong()) {
                showStudy(choice, row.get("id").getAsLong(), row.get("signed_in_at").getAsLong());
                updateAttempt(result.status());
                return;
              }
            }
          }
          updateAvailability();
        });
  }

  private void joinClass(ClassChoice choice) {
    if (busy || token == null || studentSession != 0) return;
    if (JOptionPane.showConfirmDialog(
            this,
            "Tham gia “"
                + choice.name()
                + "”?\n\nTrong buổi này, giáo viên có thể xem màn hình,"
                + " ứng dụng và lượt truy cập của bạn.",
            "Tham gia " + choice.kind().toLowerCase(Locale.ROOT),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE)
        != JOptionPane.OK_OPTION) return;
    String access = token;
    perform(
        () ->
            agent.classroomCall("students/join", JsonCodec.object("classId", choice.id()), access),
        result -> {
          showStudy(choice, result.get("id").getAsLong(), System.currentTimeMillis());
          refreshSessionStatus();
        });
  }

  private void showStudy(ClassChoice choice, long session, long joinedAt) {
    currentClass = choice;
    studentSession = session;
    study.showSession(choice, joinedAt);
    navigation.show(pages, "study");
    status.setText("Đang tham gia buổi. Hoạt động trong buổi được ghi nhận.");
    updateAvailability();
  }

  private void leaveClass() {
    if (busy || studentSession == 0) return;
    if (JOptionPane.showConfirmDialog(
            this,
            "Rời buổi hiện tại? Giờ rời buổi sẽ được ghi lại.",
            "Rời buổi",
            JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) return;
    long session = studentSession;
    String access = token;
    perform(
        () ->
            agent.classroomCall(
                "students/leave", JsonCodec.object("studentSessionId", session), access),
        result -> showClassSelection("Đã rời buổi. Bạn có thể chọn một buổi khác."));
  }

  private void showClassSelection(String message) {
    studentSession = 0;
    currentClass = null;
    navigation.show(pages, "classes");
    status.setText(message);
    loadClasses();
  }

  private void examAction(boolean submit) {
    if (busy || studentSession == 0 || currentClass == null || !currentClass.exam()) return;
    if (submit
        && JOptionPane.showConfirmDialog(
                this,
                "Ghi nhận đã nộp bài? Sau xác nhận, bạn không thể bắt đầu lại lượt làm bài.",
                "Xác nhận nộp bài",
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    long session = studentSession;
    String access = token;
    perform(
        () ->
            agent.classroomCall(
                submit ? "exams/submit" : "exams/start",
                JsonCodec.object("studentSessionId", session),
                access),
        result -> {
          study.showAttempt(result);
          status.setText(submit ? "Đã ghi nhận giờ nộp bài." : "Đã ghi nhận giờ bắt đầu làm bài.");
        });
  }

  private void refreshSessionStatus() {
    if (checking || busy || token == null) return;
    checking = true;
    String access = token;
    long version = screenVersion;
    new SwingWorker<JsonObject, Void>() {
      @Override
      protected JsonObject doInBackground() throws Exception {
        return agent.classroomCall("students/status", JsonCodec.object(), access);
      }

      @Override
      protected void done() {
        checking = false;
        if (closing || version != screenVersion || !Objects.equals(access, token) || busy) return;
        try {
          JsonObject result = get();
          if (studentSession == 0) return;
          for (JsonElement item : result.getAsJsonArray("sessions")) {
            JsonObject row = item.getAsJsonObject();
            if (row.get("id").getAsLong() == studentSession
                && row.get("machine_id").getAsString().equals(agent.machineId())) {
              updateAttempt(result);
              return;
            }
          }
          showClassSelection("Buổi đã kết thúc hoặc phiên trên máy này đã đóng.");
        } catch (Exception error) {
          Throwable cause = error.getCause() == null ? error : error.getCause();
          if (expired(cause)) showLogin("Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.");
          else status.setText("Chưa cập nhật được trạng thái. Hệ thống sẽ thử lại.");
        }
      }
    }.execute();
  }

  private void updateAttempt(JsonObject result) {
    if (currentClass == null || !currentClass.exam()) return;
    for (JsonElement item : result.getAsJsonArray("attempts")) {
      JsonObject attempt = item.getAsJsonObject();
      if (attempt.get("class_id").getAsLong() == currentClass.id()) {
        study.showAttempt(attempt);
        return;
      }
    }
    study.showAttempt(null);
  }

  private void logout() {
    if (busy || token == null) return;
    if (studentSession != 0
        && JOptionPane.showConfirmDialog(
                this,
                "Đăng xuất sẽ kết thúc phiên tham gia hiện tại. Tiếp tục?",
                "Đăng xuất",
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    String access = token;
    perform(
        () -> agent.classroomCall("logout", JsonCodec.object(), access), result -> showLogin(null));
  }

  private void showLogin(String message) {
    screenVersion++;
    token = null;
    studentSession = 0;
    currentClass = null;
    selection.setClasses(List.of());
    login.clearPassword();
    header.setVisible(false);
    navigation.show(pages, "login");
    status.setText(" ");
    getRootPane().setDefaultButton(login.defaultButton());
    updateAvailability();
    if (message != null) login.showError(message);
    login.focusUsername();
  }

  private boolean expired(Throwable error) {
    return error instanceof ClassroomClient.RequestException request
        && (request.statusCode() == 401 || request.statusCode() == 403);
  }

  private <T> void perform(Callable<T> task, Consumer<T> completed) {
    if (busy || closing) return;
    busy = true;
    long version = ++screenVersion;
    updateAvailability();
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.call();
      }

      @Override
      protected void done() {
        if (closing || version != screenVersion) return;
        busy = false;
        updateAvailability();
        try {
          completed.accept(get());
        } catch (Exception error) {
          Throwable cause = error.getCause() == null ? error : error.getCause();
          if (token == null) login.showError(cause.getMessage());
          else if (expired(cause))
            showLogin("Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.");
          else {
            status.setText("Chưa thực hiện được thao tác. Bạn có thể thử lại.");
            JOptionPane.showMessageDialog(
                StudentFrame.this,
                cause.getMessage(),
                "Không thực hiện được",
                JOptionPane.ERROR_MESSAGE);
          }
        }
      }
    }.execute();
  }

  private void updateAvailability() {
    boolean connected = agent.registered();
    boolean available = connected && !busy && !closing;
    connection.setText(connected ? "Đã kết nối máy chủ" : "Đang kết nối máy chủ…");
    connection.setForeground(connected ? AppTheme.PRIMARY : AppTheme.MUTED);
    login.setConnection(connection.getText(), connected);
    login.setBusy(busy);
    selection.setAvailable(available && token != null);
    study.setAvailable(available && studentSession != 0);
    logout.setEnabled(!busy && !closing);
    setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
  }

  private void closeWindow() {
    if (closing) return;
    if (studentSession != 0
        && JOptionPane.showConfirmDialog(
                this,
                "Đóng chương trình và rời buổi hiện tại?",
                "Đóng chương trình",
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    closing = true;
    screenVersion++;
    timer.stop();
    setEnabled(false);
    String access = token;
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() {
        try {
          if (access != null && agent.registered())
            agent.classroomCall("logout", JsonCodec.object(), access);
        } catch (Exception error) {
          org.slf4j.LoggerFactory.getLogger(StudentFrame.class)
              .debug("Không gửi được đăng xuất khi đóng.");
        } finally {
          agent.close();
        }
        return null;
      }

      @Override
      protected void done() {
        dispose();
      }
    }.execute();
  }
}
