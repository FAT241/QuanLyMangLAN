package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.SwingTasks;
import java.awt.*;
import javax.swing.*;

final class TeacherViolationActions {
  private final TeacherFrame view;

  TeacherViolationActions(TeacherFrame view) {
    this.view = view;
  }

  void flag() {
    var alert = view.alerts.selected();
    flagSession(alert.get("student_session_id").getAsLong(), alert.get("created_at").getAsLong());
  }

  void flagStudent(JsonObject student) {
    long at =
        student.get("ended_at").isJsonNull()
            ? System.currentTimeMillis()
            : student.get("ended_at").getAsLong();
    flagSession(student.get("id").getAsLong(), at);
  }

  private void flagSession(long session, long at) {
    String details =
        JOptionPane.showInputDialog(view, "Mô tả sự việc cần ghi nhận (chưa tự trừ điểm):");
    if (details == null) return;
    final long occurred = at;
    SwingTasks.run(
        view,
        () ->
            view.call(
                "violations/flag",
                JsonCodec.object(
                    "studentSessionId", session, "details", details, "occurredAt", occurred)),
        r -> {
          view.refresh();
          view.showPage(TeacherPage.VIOLATIONS);
          JOptionPane.showMessageDialog(
              view, "Đã đánh dấu #" + r.get("id") + ". Chọn dòng để xem xét hoặc gắn ảnh.");
        });
  }

  void review() {
    review(view.violations.selected().get("id").getAsLong());
  }

  void review(long id) {
    JComboBox<String> decision =
        new JComboBox<>(new String[] {"Xác nhận", "Bỏ qua / Hủy điểm trừ"});
    JSpinner points = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
    JTextField note = new JTextField();
    JButton attach = new JButton("Gắn ảnh màn hình vừa chụp", TeacherIcons.icon("image", 17));
    attach.setEnabled(view.lastScreenshot != null);
    attach.setToolTipText("Xem màn hình sinh viên trước khi lưu ảnh bằng chứng.");
    attach.addActionListener(e -> saveEvidence(id));
    if (JOptionPane.showConfirmDialog(
            view,
            new Object[] {"Xử lý", decision, "Điểm trừ", points, "Lý do", note, attach},
            "Xem xét đánh dấu #" + id,
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) return;
    String state = decision.getSelectedIndex() == 0 ? "CONFIRMED" : "DISMISSED";
    int deduction = decision.getSelectedIndex() == 0 ? (int) points.getValue() : 0;
    String reason = note.getText();
    SwingTasks.run(
        view,
        () ->
            view.call(
                "violations/review",
                JsonCodec.object(
                    "violationId", id, "status", state, "points", deduction, "note", reason)),
        r -> view.refresh());
  }

  void saveEvidence() {
    saveEvidence(view.violations.selected().get("id").getAsLong());
  }

  private void saveEvidence(long id) {
    if (view.lastScreenshot == null)
      throw new IllegalArgumentException("Chụp màn hình trước khi lưu bằng chứng");
    String request = view.lastScreenshot;
    SwingTasks.run(
        view,
        () -> view.call("evidence/save", JsonCodec.object("violationId", id, "requestId", request)),
        r -> {
          view.refresh();
          JOptionPane.showMessageDialog(view, "Đã lưu ảnh bằng chứng #" + r.get("id"));
        });
  }

  void openEvidence() {
    long id = view.evidence.selected().get("id").getAsLong();
    SwingTasks.run(
        view,
        () -> view.call("evidence/read", JsonCodec.object("evidenceId", id)),
        r -> new ScreenDialog(view, r, "Bằng chứng #" + id).setVisible(true));
  }
}
