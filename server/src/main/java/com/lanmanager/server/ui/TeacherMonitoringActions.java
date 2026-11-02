package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.SwingTasks;
import java.awt.*;
import javax.swing.*;

final class TeacherMonitoringActions {
  private final TeacherFrame view;

  TeacherMonitoringActions(TeacherFrame view) {
    this.view = view;
  }

  private record MonitorResult(String requestId, JsonObject payload) {}

  private MonitorResult awaitMonitor(
      String access, long session, String type, JsonObject payload, boolean control)
      throws Exception {
    var body = JsonCodec.object("studentSessionId", session, "type", type, "payload", payload);
    if (control) body.addProperty("confirmed", true);
    String requestId =
        view.callWithToken(access, control ? "monitor/control" : "monitor/request", body)
            .get("requestId")
            .getAsString();
    for (int i = 0; i < 40; i++) {
      var response =
          view.callWithToken(access, "monitor/result", JsonCodec.object("requestId", requestId));
      String state = response.get("status").getAsString();
      if (state.equals("COMPLETED")) {
        var result = response.getAsJsonObject("result");
        if (!result.get("ok").getAsBoolean())
          throw new IllegalStateException(
              result.getAsJsonObject("error").get("code").getAsString()
                  + ": "
                  + result.getAsJsonObject("error").get("message").getAsString());
        return new MonitorResult(requestId, result.getAsJsonObject("payload"));
      }
      if (state.equals("FAILED"))
        throw new IllegalStateException("Máy con không phản hồi hoặc phiên đã mất kết nối");
      Thread.sleep(500);
    }
    throw new IllegalStateException("Quá thời gian chờ");
  }

  void showScreen(JsonObject student) {
    long id = student.get("id").getAsLong();
    String access = view.token;
    SwingTasks.run(
        view,
        () -> awaitMonitor(access, id, "GET_SCREENSHOT", JsonCodec.object(), false),
        result -> {
          if (!java.util.Objects.equals(access, view.token)) return;
          view.lastScreenshot = result.requestId();
          view.updateContextActions();
          new ScreenDialog(view, result.payload(), "Màn hình phiên " + id).setVisible(true);
        });
  }

  void showProcesses(JsonObject student) {
    long id = student.get("id").getAsLong();
    String access = view.token;
    SwingTasks.run(
        view,
        () -> awaitMonitor(access, id, "GET_PROCESSES", JsonCodec.object(), false),
        result -> {
          if (!java.util.Objects.equals(access, view.token)) return;
          JDialog dialog = new JDialog(view, "Ứng dụng — Phiên " + id, false);
          var data =
              new DataTable(
                  new String[] {"pid", "name", "startedAt"},
                  new String[] {"Mã tiến trình", "Ứng dụng", "Khởi chạy"});
          var rows = result.payload().getAsJsonArray("processes");
          for (var item : rows)
            item.getAsJsonObject().addProperty("id", item.getAsJsonObject().get("pid").getAsLong());
          data.setRows(rows);
          dialog.add(data);
          JButton close = new JButton("Đóng ứng dụng được chọn");
          dialog.add(close, BorderLayout.SOUTH);
          close.addActionListener(
              e -> {
                try {
                  var row = data.selected();
                  if (JOptionPane.showConfirmDialog(
                          dialog,
                          "Đóng "
                              + row.get("name").getAsString()
                              + " trên phiên "
                              + id
                              + "? Có thể mất dữ liệu chưa lưu.",
                          "Xác nhận",
                          JOptionPane.YES_NO_OPTION)
                      != JOptionPane.YES_OPTION) return;
                  SwingTasks.run(
                      dialog,
                      () -> awaitMonitor(access, id, "TERMINATE_PROCESS", row, true),
                      r ->
                          JOptionPane.showMessageDialog(
                              dialog, r.payload().get("detail").getAsString()));
                } catch (Exception ex) {
                  JOptionPane.showMessageDialog(dialog, ex.getMessage());
                }
              });
          dialog.setSize(700, 500);
          dialog.setLocationRelativeTo(view);
          dialog.setVisible(true);
        });
  }

  void lockScreen(JsonObject student) {
    long id = student.get("id").getAsLong();
    String access = view.token;
    if (JOptionPane.showConfirmDialog(
            view, "Khóa màn hình phiên " + id + "?", "Xác nhận", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION)
      SwingTasks.run(
          view,
          () -> awaitMonitor(access, id, "LOCK_SCREEN", JsonCodec.object(), true),
          r -> JOptionPane.showMessageDialog(view, r.payload().get("detail").getAsString()));
  }

  void readHistory(JsonObject student) {
    long id = student.get("id").getAsLong();
    String access = view.token;
    Object browser =
        JOptionPane.showInputDialog(
            view,
            "Trình duyệt",
            "Lấy lịch sử trong phiên",
            JOptionPane.QUESTION_MESSAGE,
            null,
            new String[] {"chrome", "edge"},
            "chrome");
    if (browser == null) return;
    long since = student.get("signed_in_at").getAsLong();
    SwingTasks.run(
        view,
        () ->
            awaitMonitor(
                access,
                id,
                "GET_WEB_HISTORY",
                JsonCodec.object(
                    "browser", browser.toString(), "limit", 500, "sinceEpochMs", since),
                false),
        r -> {
          if (!java.util.Objects.equals(access, view.token)) return;
          JDialog dialog = new JDialog(view, "Lượt truy cập — Phiên " + id, false);
          var table =
              new DataTable(
                  new String[] {"url", "title", "visitTime"},
                  new String[] {"Địa chỉ web", "Tiêu đề", "Thời gian truy cập"});
          var entries = r.payload().getAsJsonArray("entries");
          for (var item : entries)
            item.getAsJsonObject()
                .addProperty("id", item.getAsJsonObject().get("visitId").getAsLong());
          table.setRows(entries);
          dialog.add(table);
          dialog.add(
              new JLabel(
                  r.payload().get("truncated").getAsBoolean()
                      ? "Có thêm dữ liệu chưa tải. Dữ liệu thu thập định kỳ được lưu trong thẻ Truy"
                          + " cập."
                      : "Dữ liệu trong phiên được chọn."),
              BorderLayout.SOUTH);
          dialog.setSize(1000, 500);
          dialog.setLocationRelativeTo(view);
          dialog.setVisible(true);
        });
  }
}
