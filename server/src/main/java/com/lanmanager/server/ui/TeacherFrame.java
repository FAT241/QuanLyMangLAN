package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.protocol.http.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.*;
import com.lanmanager.server.ServerBackend;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

public final class TeacherFrame extends JFrame {
  private final TeacherMonitoringActions monitoringActions = new TeacherMonitoringActions(this);
  final TeacherViolationActions violationActions = new TeacherViolationActions(this);

  private final HttpTransport transport;
  private final ServerBackend backend;
  String token, lastScreenshot;
  private final JComboBox<TeacherClassChoice> classes = new JComboBox<>();
  private final JLabel status = new JLabel("Đăng nhập bằng tài khoản giáo viên để bắt đầu.");
  private final JPanel content = new JPanel(new BorderLayout(8, 8));
  final DataTable students =
      new DataTable(
          new String[] {
            "id",
            "full_name",
            "username",
            "machine_id",
            "signed_in_at",
            "last_seen_at",
            "ended_at",
            "exam_started_at",
            "exam_submitted_at",
            "exam_closed_at",
            "penalty_total",
            "end_reason"
          },
          new String[] {
            "Phiên",
            "Họ tên",
            "Tài khoản",
            "Máy",
            "Giờ vào",
            "Lần cuối thấy",
            "Giờ kết thúc",
            "Bắt đầu làm bài",
            "Nộp bài",
            "Kết thúc lượt thi",
            "Tổng điểm trừ",
            "Lý do"
          });
  final DataTable violations =
      new DataTable(
          new String[] {
            "id", "student_name", "details", "occurred_at", "status", "penalty_points", "note"
          },
          new String[] {
            "Mã", "Sinh viên", "Sự việc", "Thời điểm", "Trạng thái", "Điểm trừ", "Ghi chú"
          });
  private final DataTable errors =
      new DataTable(
          new String[] {
            "id",
            "student_name",
            "code",
            "message",
            "first_seen_at",
            "last_seen_at",
            "resolved_at",
            "occurrence_count"
          },
          new String[] {
            "Mã", "Sinh viên", "Loại lỗi", "Nội dung", "Bắt đầu", "Lần cuối", "Phục hồi", "Số lần"
          });
  final DataTable evidence =
      new DataTable(
          new String[] {"id", "violation_id", "captured_at", "saved_at", "sha256"},
          new String[] {"Mã", "Đánh dấu", "Giờ chụp", "Giờ lưu", "Mã kiểm tra ảnh"});
  private final DataTable controls =
      new DataTable(
          new String[] {
            "id", "student_name", "type", "requested_at", "completed_at", "status", "result"
          },
          new String[] {
            "Mã", "Sinh viên", "Thao tác", "Giờ gửi", "Giờ hoàn tất", "Trạng thái", "Kết quả"
          });
  private final DataTable audit =
      new DataTable(
          new String[] {
            "id", "teacher_id", "violation_id", "action", "at", "previous_value", "new_value"
          },
          new String[] {"Mã", "Giáo viên", "Đánh dấu", "Thao tác", "Thời điểm", "Trước", "Sau"});
  private TeacherWorkspacePanel workspace;
  private ClassroomOverviewPanel classroomPage;
  private long historyClass = -1;
  final DataTable alerts =
      new DataTable(
          new String[] {"id", "student_name", "kind", "pattern", "description", "created_at"},
          new String[] {"Mã", "Sinh viên", "Loại", "Quy tắc", "Sự việc cần xem xét", "Phát hiện"});
  private JsonObject currentReport;
  private boolean refreshing, loadingClasses, showingExamColumns, showEndedClasses;
  private long classLoadSequence;
  private String teacherName = "Giáo viên";
  private final JPanel contextActions =
      AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
  private final java.util.List<JComponent> targetedActions = new ArrayList<>();
  private TeacherPage contextPage;

  public TeacherFrame(ServerBackend backend) {
    super("Quản lý phòng máy — Giáo viên");
    this.backend = backend;
    transport = new HttpTransport("127.0.0.1", backend.http.port());
    AppTheme.fitWindow(this, 960, 700, 900, 660);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    content.setBorder(BorderFactory.createEmptyBorder());
    status.setBorder(BorderFactory.createEmptyBorder(8, 24, 12, 24));
    status.setForeground(AppTheme.MUTED);
    for (DataTable data : new DataTable[] {students, violations, alerts, evidence})
      data.table.getSelectionModel().addListSelectionListener(e -> updateActionAvailability());
    students.table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2
                && SwingUtilities.isLeftMouseButton(event)
                && students.table.rowAtPoint(event.getPoint()) >= 0)
              guarded(TeacherFrame.this::openStudent);
          }
        });
    students.table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openStudent");
    students
        .table
        .getActionMap()
        .put(
            "openStudent",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                guarded(TeacherFrame.this::openStudent);
              }
            });
    add(content);
    add(status, BorderLayout.SOUTH);
    students.hideColumns("id", "machine_id", "last_seen_at", "exam_closed_at", "end_reason");
    students.hideColumns("exam_started_at", "exam_submitted_at");
    for (DataTable data : new DataTable[] {violations, alerts, errors, controls, audit})
      data.hideColumns("id", "teacher_id");
    evidence.hideColumns("id", "sha256");
    showLogin();
    javax.swing.Timer timer =
        new javax.swing.Timer(
            5000,
            e -> {
              if (token != null) refresh();
            });
    timer.start();
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            if (JOptionPane.showConfirmDialog(
                    TeacherFrame.this,
                    "Dừng máy chủ và đóng cửa sổ?",
                    "Xác nhận",
                    JOptionPane.YES_NO_OPTION)
                != JOptionPane.YES_OPTION) return;
            timer.stop();
            setEnabled(false);
            SwingTasks.run(
                TeacherFrame.this,
                () -> {
                  backend.close();
                  return true;
                },
                r -> dispose());
          }
        });
  }

  JsonObject call(String route, JsonObject body) throws Exception {
    return ClassroomClient.call(transport, route, body, token, null);
  }

  JsonObject callWithToken(String access, String route, JsonObject body) throws Exception {
    return ClassroomClient.call(transport, route, body, access, null);
  }

  private void showLogin() {
    AppTheme.fitWindow(this, 960, 700, 900, 660);
    LoginPanel login =
        new LoginPanel(
            "Giáo viên",
            "Theo dõi lớp học, xem lại hoạt động và ghi nhận sự việc trong từng buổi.",
            "Dùng tài khoản giáo viên của phòng máy để bắt đầu quản lý lớp.");
    login.setConnection("Máy chủ đang hoạt động", true);
    content.add(login);
    getRootPane().setDefaultButton(login.defaultButton());
    login.onLogin(
        (username, chars) -> {
          String secret = new String(chars);
          Arrays.fill(chars, '\0');
          login.setBusy(true);
          new SwingWorker<JsonObject, Void>() {
            @Override
            protected JsonObject doInBackground() throws Exception {
              String access =
                  ClassroomClient.call(
                          transport,
                          "login",
                          JsonCodec.object("username", username, "password", secret),
                          null,
                          null)
                      .get("accessToken")
                      .getAsString();
              try {
                JsonObject user =
                    ClassroomClient.call(transport, "me", JsonCodec.object(), access, null);
                if (!user.get("role").getAsString().equals("TEACHER"))
                  throw new IllegalArgumentException("Hãy dùng tài khoản giáo viên tại máy này.");
                user.addProperty("accessToken", access);
                return user;
              } catch (Exception error) {
                try {
                  ClassroomClient.call(transport, "logout", JsonCodec.object(), access, null);
                } catch (Exception ignored) {
                  /* Giữ lỗi đăng nhập ban đầu. */
                }
                throw error;
              }
            }

            @Override
            protected void done() {
              login.setBusy(false);
              if (!isDisplayable()) return;
              try {
                JsonObject user = get();
                teacherName = user.get("fullName").getAsString();
                showWorkspace(user.get("accessToken").getAsString());
              } catch (Exception error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                login.showError(cause.getMessage());
              }
            }
          }.execute();
        });
    SwingUtilities.invokeLater(login::focusUsername);
  }

  void showWorkspace(String access) {
    token = access;
    content.removeAll();
    for (DataTable data :
        new DataTable[] {students, violations, alerts, errors, evidence, controls, audit})
      data.setRows(new JsonArray());
    getRootPane().setDefaultButton(null);
    AppTheme.fitWindow(this, 1360, 860, 1100, 700);
    buildWorkspace();
    loadClasses();
    status.setText("Đã đăng nhập. Chọn buổi học/kiểm tra. Thời gian hiển thị theo giờ Việt Nam.");
  }

  private void buildWorkspace() {
    classroomPage = new ClassroomOverviewPanel(students, () -> guarded(this::startClass));
    Map<TeacherPage, JComponent> pages = new EnumMap<>(TeacherPage.class);
    pages.put(TeacherPage.CLASSROOM, classroomPage);
    pages.put(TeacherPage.HISTORY, new HistoryPanel(this, 0));
    pages.put(TeacherPage.VIOLATIONS, violations);
    pages.put(TeacherPage.ALERTS, alerts);
    pages.put(TeacherPage.EVIDENCE, evidence);
    pages.put(TeacherPage.ERRORS, errors);
    pages.put(TeacherPage.CONTROLS, controls);
    pages.put(TeacherPage.AUDIT, audit);
    pages.put(
        TeacherPage.MANAGEMENT,
        new ManagementPanel(
            () -> guarded(() -> new AccountDialog(this).setVisible(true)),
            () -> guarded(() -> new RuleDialog(this, classId()).setVisible(true)),
            () -> guarded(this::exportReport),
            () -> showPage(TeacherPage.ERRORS)));
    showEndedClasses = false;
    JPopupMenu classOptions = new JPopupMenu();
    menuItem(classOptions, "Tạo buổi mới", this::startClass);
    menuItem(classOptions, "Tải lại danh sách buổi", this::loadClasses);
    menuItem(classOptions, "Xuất báo cáo", this::exportReport);
    JCheckBoxMenuItem includeEnded = new JCheckBoxMenuItem("Hiện buổi đã kết thúc");
    includeEnded.addActionListener(
        e -> {
          showEndedClasses = includeEnded.isSelected();
          loadClasses();
        });
    classOptions.add(includeEnded);
    classOptions.addSeparator();
    menuItem(classOptions, "Kết thúc buổi", this::endClass);
    JMenuItem remove = menuItem(classOptions, "Xóa buổi tạo nhầm", this::deleteClass);
    remove.setForeground(AppTheme.DANGER);
    workspace =
        new TeacherWorkspacePanel(
            teacherName,
            classes,
            pages,
            contextActions,
            classOptions,
            () -> guarded(this::logout),
            page -> {
              if (page == TeacherPage.HISTORY) updateHistoryPage();
              updateContextActions();
            });
    content.add(workspace);
    classes.addActionListener(
        e -> {
          if (!loadingClasses) {
            classroomPage.setHasClass(classes.getSelectedItem() != null);
            updateHistoryPage();
            refresh();
          }
        });
    contextPage = null;
    historyClass = -1;
    workspace.showPage(TeacherPage.CLASSROOM);
    content.revalidate();
    content.repaint();
  }

  private void updateHistoryPage() {
    if (workspace == null || workspace.selectedPage() != TeacherPage.HISTORY) return;
    long id = classes.getSelectedItem() == null ? 0 : classId();
    if (id == historyClass) return;
    historyClass = id;
    workspace.replacePage(TeacherPage.HISTORY, new HistoryPanel(this, id));
  }

  void showPage(TeacherPage page) {
    if (workspace != null && token != null) workspace.showPage(page);
  }

  void updateContextActions() {
    if (token == null || workspace == null || workspace.selectedPage() == null) return;
    TeacherPage selected = workspace.selectedPage();
    if (contextPage == selected) {
      updateActionAvailability();
      return;
    }
    contextPage = selected;
    contextActions.removeAll();
    targetedActions.clear();
    if (selected == TeacherPage.CLASSROOM) {
      workspace.setHint("Nhấp đúp sinh viên hoặc chọn dòng để mở hồ sơ và thao tác.");
      targetButton("Xem hồ sơ", this::openStudent, students, false, true)
          .setIcon(TeacherIcons.icon("users", 18, Color.WHITE));
    } else if (selected == TeacherPage.ALERTS) {
      targetButton("Đánh dấu sự việc", violationActions::flag, alerts, false, true)
          .setIcon(TeacherIcons.icon("flag", 18, Color.WHITE));
    } else if (selected == TeacherPage.VIOLATIONS) {
      targetButton("Xem xét sự việc", violationActions::review, violations, false, true)
          .setIcon(TeacherIcons.icon("eye", 18, Color.WHITE));
    } else if (selected == TeacherPage.EVIDENCE) {
      targetButton("Mở ảnh bằng chứng", violationActions::openEvidence, evidence, false, true)
          .setIcon(TeacherIcons.icon("image", 18, Color.WHITE));
    }
    updateActionAvailability();
    contextActions.revalidate();
    contextActions.repaint();
  }

  private void openStudent() {
    new StudentDetailDialog(this, students.selected(), monitoringActions).setVisible(true);
  }

  private JButton button(JPanel panel, String title, Runnable action, boolean primary) {
    JButton button = AppTheme.button(title, primary);
    button.addActionListener(e -> guarded(action));
    panel.add(button);
    return button;
  }

  private JMenuItem menuItem(JPopupMenu menu, String title, Runnable action) {
    JMenuItem item = new JMenuItem(title);
    item.addActionListener(e -> guarded(action));
    menu.add(item);
    return item;
  }

  private JButton targetButton(
      String title, Runnable action, DataTable source, boolean live, boolean primary) {
    JButton button = button(contextActions, title, action, primary);
    targetAction(button, source, live);
    return button;
  }

  private void targetAction(JComponent action, DataTable source, boolean live) {
    action.putClientProperty("selectionSource", source);
    action.putClientProperty("requiresLiveStudent", live);
    targetedActions.add(action);
  }

  private void updateActionAvailability() {
    for (JComponent action : targetedActions) {
      DataTable source = (DataTable) action.getClientProperty("selectionSource");
      boolean enabled = source.table.getSelectedRow() >= 0;
      if (enabled && Boolean.TRUE.equals(action.getClientProperty("requiresLiveStudent"))) {
        JsonElement ended = source.selected().get("ended_at");
        enabled = ended == null || ended.isJsonNull();
      }
      if (Boolean.TRUE.equals(action.getClientProperty("requiresScreenshot")))
        enabled &= lastScreenshot != null;
      action.setEnabled(enabled);
      action.setVisible(enabled);
    }
  }

  private void guarded(Runnable action) {
    try {
      action.run();
    } catch (Exception error) {
      JOptionPane.showMessageDialog(
          this, error.getMessage(), "Không thực hiện được", JOptionPane.ERROR_MESSAGE);
    }
  }

  private long classId() {
    var choice = (TeacherClassChoice) classes.getSelectedItem();
    if (choice == null) throw new IllegalArgumentException("Chọn một buổi học/kiểm tra");
    return choice.id();
  }

  private void loadClasses() {
    loadClasses(null);
  }

  private void loadClasses(Long preferredId) {
    String access = token;
    if (access == null) return;
    long sequence = ++classLoadSequence;
    SwingTasks.run(
        this,
        () -> ClassroomClient.call(transport, "classes/list", JsonCodec.object(), access, null),
        r -> {
          if (!Objects.equals(access, token) || sequence != classLoadSequence) return;
          Long selected =
              preferredId != null
                  ? preferredId
                  : classes.getSelectedItem() == null
                      ? null
                      : ((TeacherClassChoice) classes.getSelectedItem()).id();
          loadingClasses = true;
          classes.removeAllItems();
          for (TeacherClassChoice choice :
              TeacherClassChoice.visibleClasses(r.getAsJsonArray("classes"), showEndedClasses)) {
            classes.addItem(choice);
            if (selected != null && selected == choice.id()) classes.setSelectedItem(choice);
          }
          loadingClasses = false;
          classroomPage.setHasClass(classes.getSelectedItem() != null);
          updateHistoryPage();
          if (classes.getItemCount() == 0) {
            currentReport = null;
            for (DataTable data :
                new DataTable[] {students, violations, alerts, errors, evidence, controls, audit})
              data.setRows(new JsonArray());
            classroomPage.updateReport(
                JsonCodec.object(
                    "students",
                    new JsonArray(),
                    "alerts",
                    new JsonArray(),
                    "violations",
                    new JsonArray()));
            status.setText(
                "Không có buổi đang mở. Tạo buổi mới hoặc bật Hiện buổi đã kết thúc trong Quản lý"
                    + " buổi.");
            workspace.setHint(
                "Tạo buổi mới hoặc mở lại danh sách buổi đã kết thúc để xem lịch sử.");
          }
          refresh();
        });
  }

  void refresh() {
    if (token == null || refreshing || loadingClasses || classes.getSelectedItem() == null) return;
    String access = token;
    long id = classId();
    boolean exam = ((TeacherClassChoice) classes.getSelectedItem()).exam();
    if (exam != showingExamColumns) {
      showingExamColumns = exam;
      students.showColumns(
          exam
              ? new String[] {
                "full_name",
                "username",
                "signed_in_at",
                "ended_at",
                "exam_started_at",
                "exam_submitted_at",
                "penalty_total"
              }
              : new String[] {
                "full_name", "username", "signed_in_at", "ended_at", "penalty_total"
              });
    }
    refreshing = true;
    new SwingWorker<JsonObject, Void>() {
      protected JsonObject doInBackground() throws Exception {
        return ClassroomClient.call(
            transport, "classes/report", JsonCodec.object("classId", id), access, null);
      }

      protected void done() {
        if (!Objects.equals(access, token)) return;
        refreshing = false;
        try {
          var report = get();
          if (classes.getSelectedItem() == null || classId() != id) {
            refresh();
            return;
          }
          currentReport = report;
          Map<Long, String> studentNames = new HashMap<>();
          for (JsonElement entry : report.getAsJsonArray("students")) {
            JsonObject student = entry.getAsJsonObject();
            studentNames.put(student.get("id").getAsLong(), student.get("full_name").getAsString());
          }
          for (String section : new String[] {"alerts", "violations", "errors", "controls"}) {
            for (JsonElement entry : report.getAsJsonArray(section)) {
              JsonObject row = entry.getAsJsonObject();
              JsonElement session = row.get("student_session_id");
              row.addProperty(
                  "student_name",
                  session == null || session.isJsonNull()
                      ? "—"
                      : studentNames.getOrDefault(
                          session.getAsLong(), "Phiên " + session.getAsLong()));
            }
          }
          students.setRows(report.getAsJsonArray("students"));
          JsonArray alertRows = report.getAsJsonArray("alerts");
          for (var item : alertRows) {
            var row = item.getAsJsonObject();
            row.addProperty(
                "description",
                com.lanmanager.common.ui.ActivityText.describe(row.get("details").getAsString()));
          }
          alerts.setRows(alertRows);
          classroomPage.updateReport(report);
          violations.setRows(report.getAsJsonArray("violations"));
          errors.setRows(report.getAsJsonArray("errors"));
          evidence.setRows(report.getAsJsonArray("evidence"));
          controls.setRows(report.getAsJsonArray("controls"));
          audit.setRows(report.getAsJsonArray("actions"));
          updateContextActions();
          status.setText(
              "Đã cập nhật: "
                  + report.getAsJsonArray("students").size()
                  + " phiên sinh viên. Dữ liệu tự cập nhật mỗi 5 giây.");
        } catch (Exception e) {
          status.setText(
              "Không tải được dữ liệu: "
                  + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
      }
    }.execute();
  }

  private void startClass() {
    JTextField name = new JTextField();
    JComboBox<String> type = new JComboBox<>(new String[] {"Buổi học", "Kiểm tra"});
    if (JOptionPane.showConfirmDialog(
            this,
            new Object[] {"Tên buổi", name, "Loại", type},
            "Tạo buổi",
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) return;
    String title = name.getText();
    String kind = type.getSelectedIndex() == 0 ? "CLASS" : "EXAM";
    SwingTasks.run(
        this,
        () -> call("classes/start", JsonCodec.object("name", title, "type", kind)),
        r -> loadClasses(r.get("id").getAsLong()));
  }

  private void endClass() {
    long id = classId();
    if (JOptionPane.showConfirmDialog(
            this,
            "Kết thúc buổi và đóng các phiên sinh viên?",
            "Xác nhận",
            JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION)
      SwingTasks.run(
          this, () -> call("classes/end", JsonCodec.object("classId", id)), r -> loadClasses());
  }

  private void deleteClass() {
    TeacherClassChoice choice = (TeacherClassChoice) classes.getSelectedItem();
    if (choice == null) throw new IllegalArgumentException("Chọn buổi cần xóa");
    if (JOptionPane.showConfirmDialog(
            this,
            "Xóa buổi “"
                + choice.label()
                + "”?\n\n"
                + "Chỉ xóa được buổi chưa có sinh viên tham gia.\n"
                + "Các quy tắc đã đặt cho buổi cũng bị xóa.",
            "Xác nhận xóa buổi",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        != JOptionPane.YES_OPTION) return;
    String access = token;
    SwingTasks.run(
        this,
        () ->
            callWithToken(
                access,
                "classes/delete",
                JsonCodec.object("classId", choice.id(), "confirmed", true)),
        result -> {
          if (Objects.equals(access, token)) loadClasses();
        });
  }

  void createAccount() {
    createAccount(() -> {});
  }

  void createAccount(Runnable completed) {
    JTextField username = new JTextField(), name = new JTextField();
    JPasswordField password = new JPasswordField();
    JComboBox<String> role = new JComboBox<>(new String[] {"Sinh viên", "Giáo viên"});
    if (JOptionPane.showConfirmDialog(
            this,
            new Object[] {
              "Tên đăng nhập",
              username,
              "Họ tên",
              name,
              "Mật khẩu (ít nhất 10 ký tự)",
              password,
              "Vai trò",
              role
            },
            "Tạo tài khoản",
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) return;
    char[] chars = password.getPassword();
    var body =
        JsonCodec.object(
            "username",
            username.getText(),
            "password",
            new String(chars),
            "fullName",
            name.getText(),
            "role",
            role.getSelectedIndex() == 0 ? "STUDENT" : "TEACHER");
    Arrays.fill(chars, '\0');
    SwingTasks.run(
        this,
        () -> call("accounts/create", body),
        r -> {
          JOptionPane.showMessageDialog(this, "Đã tạo tài khoản.");
          completed.run();
        });
  }

  private void exportReport() {
    if (currentReport == null) throw new IllegalArgumentException("Tải dữ liệu buổi trước");
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new java.io.File("bao-cao-phong-may.csv"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    if (Files.exists(path)
        && JOptionPane.showConfirmDialog(
                this, "Ghi đè tệp đã có?", "Xác nhận", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    long selectedClass = classId();
    SwingTasks.run(
        this,
        () -> {
          JsonArray students = new JsonArray();
          long before = Long.MAX_VALUE;
          while (true) {
            var response =
                call(
                    "students/page",
                    JsonCodec.object("classId", selectedClass, "beforeId", before));
            students.addAll(response.getAsJsonArray("rows"));
            if (!response.get("hasMore").getAsBoolean()) break;
            before = response.get("nextBeforeId").getAsLong();
          }
          new ClassroomReportExporter().export(path, JsonCodec.object("students", students));
          return true;
        },
        r -> JOptionPane.showMessageDialog(this, "Đã xuất báo cáo các phiên sinh viên."));
  }

  private void logout() {
    new SwingWorker<Boolean, Void>() {
      protected Boolean doInBackground() {
        try {
          call("logout", JsonCodec.object());
        } catch (Exception e) {
          org.slf4j.LoggerFactory.getLogger(TeacherFrame.class)
              .debug("Phiên giáo viên đã hết hiệu lực");
        }
        return true;
      }

      protected void done() {
        for (java.awt.Window child : getOwnedWindows()) child.dispose();
        token = null;
        refreshing = false;
        loadingClasses = false;
        currentReport = null;
        lastScreenshot = null;
        for (DataTable data :
            new DataTable[] {students, violations, alerts, errors, evidence, controls, audit})
          data.setRows(new JsonArray());
        content.removeAll();
        workspace = null;
        classroomPage = null;
        historyClass = -1;
        for (var listener : classes.getActionListeners()) classes.removeActionListener(listener);
        classes.removeAllItems();
        contextPage = null;
        targetedActions.clear();
        showLogin();
        content.revalidate();
        content.repaint();
        status.setText("Đã đăng xuất. Máy chủ vẫn phục vụ các phiên đang mở.");
      }
    }.execute();
  }
}
