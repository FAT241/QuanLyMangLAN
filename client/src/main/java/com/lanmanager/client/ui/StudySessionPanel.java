package com.lanmanager.client.ui;

import com.google.gson.JsonObject;
import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import javax.swing.*;

/** Màn hình trong buổi học; thao tác làm bài chỉ xuất hiện với buổi kiểm tra. */
final class StudySessionPanel extends JPanel {
  private final JLabel name = AppTheme.title(" ", 26);
  private final JLabel kind = AppTheme.muted(" ");
  private final JLabel joined = AppTheme.muted("—");
  private final JLabel examStatus = AppTheme.title("Chưa bắt đầu làm bài", 20);
  private final JLabel examTime = AppTheme.muted("Bấm bắt đầu khi giáo viên yêu cầu.");
  private final JPanel examCard = AppTheme.card(new BorderLayout(20, 16), 24);
  private final JButton examAction = AppTheme.button("Bắt đầu làm bài", true);
  private final JButton leave = AppTheme.button("Rời buổi", false);
  private final Runnable start, submit;
  private boolean exam, started, closed, available;

  StudySessionPanel(Runnable start, Runnable submit, Runnable leaveAction) {
    super(new BorderLayout(0, 24));
    this.start = start;
    this.submit = submit;
    setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
    JPanel heading = AppTheme.transparent(new BorderLayout(0, 10));
    heading.add(kind, BorderLayout.NORTH);
    heading.add(name);
    heading.add(joined, BorderLayout.SOUTH);
    add(heading, BorderLayout.NORTH);
    JPanel body = AppTheme.transparent(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.NORTH;
    c.insets = new Insets(0, 0, 20, 0);
    JPanel monitor = AppTheme.card(new BorderLayout(0, 12), 24);
    monitor.setBackground(AppTheme.PRIMARY_SOFT);
    monitor.add(AppTheme.title("Đang được theo dõi trong buổi", 18), BorderLayout.NORTH);
    JTextArea explanation =
        AppTheme.paragraph(
            "Giáo viên có thể xem màn hình, ứng dụng và lượt truy cập của bạn trong thời gian tham"
                + " gia.");
    explanation.setRows(2);
    monitor.add(explanation);
    body.add(monitor, c);
    c.gridy++;
    JPanel examCopy = AppTheme.transparent(new GridLayout(0, 1, 0, 10));
    examCopy.add(AppTheme.muted("LƯỢT KIỂM TRA"));
    examCopy.add(examStatus);
    examCopy.add(examTime);
    examCard.add(examCopy);
    JPanel examButtons = AppTheme.transparent(new GridBagLayout());
    examButtons.add(examAction);
    examCard.add(examButtons, BorderLayout.EAST);
    body.add(examCard, c);
    c.gridy++;
    c.weighty = 1;
    body.add(Box.createVerticalGlue(), c);
    JScrollPane scroll = new JScrollPane(body);
    scroll.setBorder(null);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getViewport().setBackground(AppTheme.BACKGROUND);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    add(scroll);
    JPanel footer = AppTheme.transparent(new BorderLayout(24, 0));
    footer.add(AppTheme.muted("Thời gian tham gia được ghi lại theo buổi."));
    footer.add(leave, BorderLayout.EAST);
    add(footer, BorderLayout.SOUTH);
    examAction.addActionListener(
        e -> {
          if (started) this.submit.run();
          else this.start.run();
        });
    leave.addActionListener(e -> leaveAction.run());
  }

  void showSession(ClassChoice choice, long joinedAt) {
    exam = choice.exam();
    started = false;
    closed = false;
    name.setText(choice.name());
    name.setToolTipText(choice.name());
    kind.setText(choice.kind().toUpperCase(java.util.Locale.ROOT) + " · ĐANG THAM GIA");
    joined.setText("Tham gia lúc " + time(joinedAt));
    examCard.setVisible(exam);
    showAttempt(null);
  }

  void showAttempt(JsonObject attempt) {
    started = attempt != null && !attempt.get("started_at").isJsonNull();
    closed = attempt != null && !attempt.get("closed_at").isJsonNull();
    boolean submitted = attempt != null && !attempt.get("submitted_at").isJsonNull();
    examStatus.setText(
        submitted
            ? "Đã ghi nhận nộp bài"
            : closed
                ? "Lượt kiểm tra đã kết thúc"
                : started ? "Đang làm bài" : "Chưa bắt đầu làm bài");
    examTime.setText(
        submitted
            ? "Nộp lúc " + time(attempt.get("submitted_at").getAsLong())
            : started
                ? "Bắt đầu lúc " + time(attempt.get("started_at").getAsLong())
                : "Bấm bắt đầu khi giáo viên yêu cầu.");
    examAction.setText(started ? "Ghi nhận nộp bài" : "Bắt đầu làm bài");
    updateActions();
  }

  void setAvailable(boolean available) {
    this.available = available;
    updateActions();
  }

  private void updateActions() {
    examAction.setVisible(exam && !closed);
    examAction.setEnabled(available && exam && !closed);
    leave.setEnabled(available);
  }

  private String time(long at) {
    return DateTimeFormatter.ofPattern("HH:mm:ss · dd/MM/yyyy")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
        .format(Instant.ofEpochMilli(at));
  }
}
