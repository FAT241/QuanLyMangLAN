package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Tra cứu lịch sử phía máy chủ, từng trang; xuất CSV theo toàn bộ bộ lọc. */
final class HistoryPanel extends JPanel {
  private record StudentChoice(long id, String label) {
    public String toString() {
      return label;
    }
  }

  private final TeacherFrame teacher;
  private final long classId;
  private final String access;
  private final JComboBox<StudentChoice> student = new JComboBox<>();
  private final JComboBox<String> category =
      new JComboBox<>(new String[] {"Hoạt động", "Lỗi kỹ thuật", "Đánh dấu", "Cảnh báo"});
  private final JComboBox<String> kind =
      new JComboBox<>(new String[] {"Tất cả", "Truy cập web", "Ứng dụng"});
  private final JTextField keyword = new JTextField(15);
  private final JCheckBox period = new JCheckBox("Khoảng thời gian");
  private final JSpinner from = new JSpinner(new SpinnerDateModel()),
      to = new JSpinner(new SpinnerDateModel());
  private final JLabel status = new JLabel("Chọn bộ lọc và bấm Tìm");
  private final DataTable data =
      new DataTable(
          new String[] {
            "id",
            "full_name",
            "username",
            "machine_id",
            "student_session_id",
            "kind",
            "description",
            "first_at",
            "last_at",
            "ended_at",
            "points"
          },
          new String[] {
            "Mã",
            "Họ tên",
            "Tài khoản",
            "Máy",
            "Phiên",
            "Loại",
            "Nội dung",
            "Bắt đầu / truy cập",
            "Lần cuối",
            "Kết thúc",
            "Điểm trừ"
          });
  private final JButton search = AppTheme.button("Tìm kiếm", true),
      next = AppTheme.button("Sau", false),
      previous = AppTheme.button("Trước", false);
  private final java.util.List<Long> cursors = new ArrayList<>();
  private int page;
  private JsonObject filter;

  HistoryPanel(TeacherFrame teacher, long classId) {
    super(new BorderLayout(0, 16));
    this.teacher = teacher;
    access = teacher == null ? null : teacher.token;
    this.classId = classId;
    setOpaque(false);
    from.setEditor(new JSpinner.DateEditor(from, "dd/MM/yyyy HH:mm"));
    to.setEditor(new JSpinner.DateEditor(to, "dd/MM/yyyy HH:mm"));
    from.setValue(new Date(System.currentTimeMillis() - 86400000L));
    data.showColumns("full_name", "kind", "description", "first_at", "last_at", "ended_at");
    data.setSearchVisible(false);
    student.addItem(new StudentChoice(0, "Tất cả sinh viên"));
    JPanel filters = AppTheme.card(new BorderLayout(16, 12), 16);
    keyword.putClientProperty(
        "JTextField.placeholderText", "Tìm địa chỉ web, ứng dụng hoặc nội dung…");
    keyword.getAccessibleContext().setAccessibleName("Nội dung cần tra cứu");
    JPanel query = AppTheme.transparent(new BorderLayout(12, 0));
    query.add(keyword);
    JPanel queryActions = AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    search.setIcon(TeacherIcons.icon("eye", 17, Color.WHITE));
    queryActions.add(search);
    JToggleButton advanced =
        new JToggleButton("Bộ lọc thêm", TeacherIcons.icon("sliders-horizontal", 17));
    queryActions.add(advanced);
    query.add(queryActions, BorderLayout.EAST);
    filters.add(query, BorderLayout.NORTH);
    JPanel extra = AppTheme.transparent(new GridLayout(2, 3, 12, 12));
    extra.add(field("Sinh viên / lượt tham gia", student));
    extra.add(field("Nhóm lịch sử", category));
    extra.add(field("Loại hoạt động", kind));
    extra.add(period);
    extra.add(field("Từ thời điểm", from));
    extra.add(field("Đến thời điểm", to));
    extra.setVisible(false);
    from.setEnabled(false);
    to.setEnabled(false);
    period.addActionListener(
        e -> {
          from.setEnabled(period.isSelected());
          to.setEnabled(period.isSelected());
        });
    advanced.addActionListener(
        e -> {
          extra.setVisible(advanced.isSelected());
          revalidate();
        });
    filters.add(extra);
    add(filters, BorderLayout.NORTH);
    add(data);
    JPanel bottom = AppTheme.transparent(new BorderLayout(16, 0));
    bottom.add(status);
    JPanel actions = AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    JPopupMenu options = new JPopupMenu();
    JMenuItem download = new JMenuItem("Xuất toàn bộ kết quả");
    download.addActionListener(e -> exportAll());
    options.add(download);
    JMenuItem flag = new JMenuItem("Đánh dấu sự việc được chọn");
    flag.addActionListener(e -> markSelected());
    options.add(flag);
    JButton more = AppTheme.button("Thao tác", false);
    more.setIcon(TeacherIcons.icon("chevron-down", 15));
    more.addActionListener(e -> options.show(more, 0, more.getHeight()));
    actions.add(more);
    actions.add(previous);
    actions.add(next);
    bottom.add(actions, BorderLayout.EAST);
    add(bottom, BorderLayout.SOUTH);
    search.addActionListener(
        e -> {
          try {
            data.showColumns(
                category.getSelectedIndex() == 2
                    ? new String[] {
                      "full_name", "kind", "description", "first_at", "ended_at", "points"
                    }
                    : new String[] {
                      "full_name", "kind", "description", "first_at", "last_at", "ended_at"
                    });
            filter = readFilter();
            page = 0;
            cursors.clear();
            cursors.add(Long.MAX_VALUE);
            loadPage();
          } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage());
          }
        });
    previous.addActionListener(
        e -> {
          if (page > 0) {
            page--;
            loadPage();
          }
        });
    next.addActionListener(
        e -> {
          if (page + 1 < cursors.size()) {
            page++;
            loadPage();
          }
        });
    previous.setEnabled(false);
    next.setEnabled(false);
    search.setEnabled(classId > 0);
    more.setEnabled(classId > 0);
    if (classId == 0) {
      status.setText("Chọn buổi học ở đầu trang để tra cứu lịch sử.");
      return;
    }
    runTask(
        () -> {
          JsonArray choices = new JsonArray();
          long before = Long.MAX_VALUE;
          while (true) {
            var response =
                request("students/page", JsonCodec.object("classId", classId, "beforeId", before));
            choices.addAll(response.getAsJsonArray("rows"));
            if (!response.get("hasMore").getAsBoolean()) break;
            before = response.get("nextBeforeId").getAsLong();
          }
          return choices;
        },
        rows -> {
          for (var item : rows) {
            var row = item.getAsJsonObject();
            student.addItem(
                new StudentChoice(
                    row.get("id").getAsLong(),
                    row.get("full_name").getAsString()
                        + " / "
                        + row.get("username").getAsString()
                        + " — phiên "
                        + row.get("id")));
          }
          search.doClick();
        });
  }

  private boolean currentLogin() {
    return teacher != null && Objects.equals(access, teacher.token);
  }

  private JsonObject request(String route, JsonObject body) throws Exception {
    return teacher.callWithToken(access, route, body);
  }

  private <T> void runTask(
      java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> completed) {
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.call();
      }

      @Override
      protected void done() {
        if (!currentLogin()) return;
        try {
          completed.accept(get());
        } catch (Exception error) {
          Throwable cause = error.getCause() == null ? error : error.getCause();
          status.setText(cause.getMessage());
        }
      }
    }.execute();
  }

  private JPanel field(String text, JComponent input) {
    JPanel panel = AppTheme.transparent(new BorderLayout(0, 6));
    JLabel label = AppTheme.muted(text);
    label.setLabelFor(input);
    panel.add(label, BorderLayout.NORTH);
    panel.add(input);
    return panel;
  }

  private JsonObject readFilter() {
    var selected = (StudentChoice) student.getSelectedItem();
    long fromAt = period.isSelected() ? ((Date) from.getValue()).getTime() : 0,
        toAt = period.isSelected() ? ((Date) to.getValue()).getTime() : Long.MAX_VALUE;
    if (fromAt > toAt) throw new IllegalArgumentException("Giờ bắt đầu phải trước giờ kết thúc");
    String group =
        new String[] {"ACTIVITY", "ERROR", "VIOLATION", "ALERT"}[category.getSelectedIndex()];
    String type =
        category.getSelectedIndex() == 0
            ? new String[] {"ALL", "WEB", "PROCESS"}[kind.getSelectedIndex()]
            : "ALL";
    return JsonCodec.object(
        "classId",
        classId,
        "studentSessionId",
        selected == null ? 0 : selected.id(),
        "category",
        group,
        "kind",
        type,
        "keyword",
        keyword.getText(),
        "fromAt",
        fromAt,
        "toAt",
        toAt);
  }

  private void busy(boolean value) {
    search.setEnabled(!value);
    next.setEnabled(!value && page + 1 < cursors.size());
    previous.setEnabled(!value && page > 0);
  }

  private void loadPage() {
    if (filter == null) return;
    busy(true);
    JsonObject query = filter.deepCopy();
    query.addProperty("beforeId", cursors.get(page));
    new SwingWorker<JsonObject, Void>() {
      protected JsonObject doInBackground() throws Exception {
        return request("history/page", query);
      }

      protected void done() {
        if (!currentLogin()) return;
        try {
          var response = get();
          filter.addProperty("snapshotId", response.get("snapshotId").getAsLong());
          data.setRows(displayRows(response.getAsJsonArray("rows")));
          if (response.get("hasMore").getAsBoolean() && cursors.size() == page + 1)
            cursors.add(response.get("nextBeforeId").getAsLong());
          status.setText(
              "Trang "
                  + (page + 1)
                  + " | "
                  + data.model.getRowCount()
                  + " mục. Thời gian theo giờ Việt Nam.");
        } catch (Exception e) {
          JOptionPane.showMessageDialog(
              HistoryPanel.this, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } finally {
          busy(false);
        }
      }
    }.execute();
  }

  private JsonArray displayRows(JsonArray rows) {
    for (var item : rows) {
      var row = item.getAsJsonObject();
      row.addProperty(
          "description",
          com.lanmanager.common.ui.ActivityText.describe(row.get("description").getAsString()));
    }
    return rows;
  }

  private void markSelected() {
    try {
      var row = data.selected();
      if (filter != null && filter.get("category").getAsString().equals("VIOLATION")) {
        teacher.violationActions.review(row.get("id").getAsLong());
        return;
      }
      long session = row.get("student_session_id").getAsLong(),
          at = row.get("first_at").getAsLong();
      String note =
          JOptionPane.showInputDialog(this, "Mô tả sự việc", row.get("description").getAsString());
      if (note == null) return;
      runTask(
          () ->
              request(
                  "violations/flag",
                  JsonCodec.object("studentSessionId", session, "details", note, "occurredAt", at)),
          r -> {
            teacher.refresh();
            JOptionPane.showMessageDialog(this, "Đã đánh dấu #" + r.get("id") + ", chưa trừ điểm.");
          });
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, ex.getMessage());
    }
  }

  private void exportAll() {
    JsonObject query;
    try {
      query = readFilter();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, e.getMessage());
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new java.io.File("lich-su-phong-may.csv"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path target = chooser.getSelectedFile().toPath();
    if (Files.exists(target)
        && JOptionPane.showConfirmDialog(
                this, "Ghi đè tệp đã có?", "Xác nhận", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    runTask(
        () -> {
          Path temp =
              Files.createTempFile(target.toAbsolutePath().getParent(), "history-export-", ".csv");
          try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
              writer.write('\ufeff');
              writer.write(
                  "Mã,Họ tên,Tài khoản,Máy,Phiên,Loại,Nội dung,Thời điểm đầu UTC ms,Lần cuối UTC"
                      + " ms,Kết thúc UTC ms,Điểm trừ\r\n");
              long before = Long.MAX_VALUE;
              while (true) {
                query.addProperty("beforeId", before);
                var response = request("history/page", query);
                query.addProperty("snapshotId", response.get("snapshotId").getAsLong());
                for (var item : response.getAsJsonArray("rows")) {
                  var row = item.getAsJsonObject();
                  String[] fields = {
                    "id",
                    "full_name",
                    "username",
                    "machine_id",
                    "student_session_id",
                    "kind",
                    "description",
                    "first_at",
                    "last_at",
                    "ended_at",
                    "points"
                  };
                  for (int i = 0; i < fields.length; i++) {
                    if (i > 0) writer.write(',');
                    var value = row.get(fields[i]);
                    writer.write(
                        csv(value == null || value.isJsonNull() ? "" : value.getAsString()));
                  }
                  writer.write("\r\n");
                }
                if (!response.get("hasMore").getAsBoolean()) break;
                before = response.get("nextBeforeId").getAsLong();
              }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
          } finally {
            Files.deleteIfExists(temp);
          }
          return true;
        },
        r -> JOptionPane.showMessageDialog(this, "Đã xuất toàn bộ kết quả theo bộ lọc."));
  }

  private String csv(String value) {
    String clean = value.stripLeading();
    if (!clean.isEmpty() && "=+-@".indexOf(clean.charAt(0)) >= 0
        || value.startsWith("\t")
        || value.startsWith("\r")
        || value.startsWith("\n")) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
