package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import javax.swing.*;

/** Hồ sơ đã chọn giữ đúng mã phiên; thao tác không phụ thuộc dòng đang chọn phía sau. */
final class StudentDetailDialog extends JDialog {
  StudentDetailDialog(TeacherFrame teacher, JsonObject source, TeacherMonitoringActions monitor) {
    super(teacher, "Hồ sơ sinh viên", false);
    JsonObject student = source.deepCopy();
    boolean live = student.get("ended_at").isJsonNull();
    JPanel root = new JPanel(new BorderLayout(0, 24));
    root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
    JPanel heading = AppTheme.transparent(new BorderLayout(0, 10));
    heading.add(AppTheme.title(student.get("full_name").getAsString(), 25), BorderLayout.NORTH);
    heading.add(
        AppTheme.muted(
            student.get("username").getAsString()
                + " · "
                + (live ? "Đang tham gia buổi" : "Phiên đã kết thúc")));
    root.add(heading, BorderLayout.NORTH);
    JPanel facts = AppTheme.card(new GridLayout(0, 2, 20, 18), 22);
    facts.add(AppTheme.muted("Giờ tham gia"));
    facts.add(new JLabel(time(student.get("signed_in_at"))));
    facts.add(AppTheme.muted("Giờ kết thúc"));
    facts.add(new JLabel(time(student.get("ended_at"))));
    facts.add(AppTheme.muted("Bắt đầu làm bài"));
    facts.add(new JLabel(time(student.get("exam_started_at"))));
    facts.add(AppTheme.muted("Nộp bài"));
    facts.add(new JLabel(time(student.get("exam_submitted_at"))));
    facts.add(AppTheme.muted("Tổng điểm trừ"));
    JsonElement points = student.get("penalty_total");
    facts.add(
        AppTheme.title(points == null || points.isJsonNull() ? "0" : points.getAsString(), 18));
    root.add(facts);
    JPanel actions = AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    JButton screen = AppTheme.button("Xem màn hình", true);
    screen.setIcon(TeacherIcons.icon("monitor", 18, Color.WHITE));
    screen.setEnabled(live);
    screen.addActionListener(e -> monitor.showScreen(student));
    actions.add(screen);
    JPopupMenu menu = new JPopupMenu();
    item(
        menu,
        "Xem ứng dụng / đóng ứng dụng",
        "activity",
        live,
        () -> monitor.showProcesses(student));
    item(menu, "Lấy lịch sử trình duyệt", "globe", live, () -> monitor.readHistory(student));
    item(menu, "Khóa màn hình", "lock", live, () -> monitor.lockScreen(student));
    menu.addSeparator();
    item(
        menu,
        "Đánh dấu sự việc",
        "flag",
        true,
        () -> teacher.violationActions.flagStudent(student));
    JButton more = AppTheme.button("Thao tác", false);
    more.setIcon(TeacherIcons.icon("chevron-down", 16));
    more.addActionListener(e -> menu.show(more, 0, more.getHeight()));
    actions.add(more);
    root.add(actions, BorderLayout.SOUTH);
    setContentPane(root);
    setSize(600, 450);
    setLocationRelativeTo(teacher);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
  }

  private void item(JPopupMenu menu, String name, String icon, boolean enabled, Runnable action) {
    JMenuItem item = new JMenuItem(name, TeacherIcons.icon(icon, 17));
    item.setEnabled(enabled);
    item.addActionListener(e -> action.run());
    menu.add(item);
  }

  private String time(JsonElement value) {
    if (value == null || value.isJsonNull()) return "—";
    return DateTimeFormatter.ofPattern("HH:mm:ss · dd/MM/yyyy")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
        .format(Instant.ofEpochMilli(value.getAsLong()));
  }
}
