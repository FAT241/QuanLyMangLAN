package com.lanmanager.server.ui;

import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.*;
import java.awt.*;
import javax.swing.*;

final class RuleDialog extends JDialog {
  private final TeacherFrame teacher;
  private final long classId;
  private final DataTable data =
      new DataTable(
          new String[] {"id", "kind", "pattern", "display_enabled"},
          new String[] {"Mã", "Loại", "Tên miền / ứng dụng", "Trạng thái"});

  RuleDialog(TeacherFrame teacher, long classId) {
    super(teacher, "Quy tắc cảnh báo — Buổi #" + classId, false);
    this.teacher = teacher;
    this.classId = classId;
    setSize(800, 450);
    setLocationRelativeTo(teacher);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    add(data);
    data.hideColumns("id");
    JPanel toolbar = AppTheme.transparent(new BorderLayout(12, 0));
    toolbar.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
    JButton add = AppTheme.button("Thêm quy tắc", true);
    add.setIcon(TeacherIcons.icon("plus", 17, Color.WHITE));
    add.addActionListener(e -> addRule());
    toolbar.add(add, BorderLayout.WEST);
    JPopupMenu menu = new JPopupMenu();
    JMenuItem on = new JMenuItem("Bật quy tắc"),
        off = new JMenuItem("Tắt quy tắc"),
        refresh = new JMenuItem("Tải lại");
    menu.add(on);
    menu.add(off);
    menu.addSeparator();
    menu.add(refresh);
    on.addActionListener(e -> enabled(true));
    off.addActionListener(e -> enabled(false));
    refresh.addActionListener(e -> reload());
    JButton more = AppTheme.button("Tác vụ quy tắc", false);
    more.setIcon(TeacherIcons.icon("chevron-down", 16));
    more.addActionListener(
        e -> {
          boolean selected = data.table.getSelectedRow() >= 0;
          on.setEnabled(selected);
          off.setEnabled(selected);
          menu.show(more, 0, more.getHeight());
        });
    toolbar.add(more, BorderLayout.EAST);
    add(toolbar, BorderLayout.NORTH);
    reload();
  }

  private void reload() {
    SwingTasks.run(
        this,
        () -> teacher.call("classes/report", JsonCodec.object("classId", classId)),
        r -> {
          var rows = r.getAsJsonArray("rules");
          for (var item : rows) {
            var row = item.getAsJsonObject();
            row.addProperty(
                "display_enabled", row.get("enabled").getAsInt() == 1 ? "Đang bật" : "Đã tắt");
          }
          data.setRows(rows);
        });
  }

  private void addRule() {
    JComboBox<String> kind = new JComboBox<>(new String[] {"Tên miền web", "Tên tệp ứng dụng"});
    JTextField pattern = new JTextField();
    if (JOptionPane.showConfirmDialog(
            this,
            new Object[] {"Loại", kind, "Ví dụ: youtube.com hoặc steam.exe", pattern},
            "Thêm cảnh báo",
            JOptionPane.OK_CANCEL_OPTION)
        != JOptionPane.OK_OPTION) return;
    String type = kind.getSelectedIndex() == 0 ? "WEB" : "PROCESS", text = pattern.getText();
    SwingTasks.run(
        this,
        () ->
            teacher.call(
                "rules/add", JsonCodec.object("classId", classId, "kind", type, "pattern", text)),
        r -> reload());
  }

  private void enabled(boolean enabled) {
    try {
      long id = data.selected().get("id").getAsLong();
      SwingTasks.run(
          this,
          () ->
              teacher.call(
                  "rules/enabled", JsonCodec.object("ruleId", id, "enabled", enabled ? 1 : 0)),
          r -> reload());
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, e.getMessage());
    }
  }
}
