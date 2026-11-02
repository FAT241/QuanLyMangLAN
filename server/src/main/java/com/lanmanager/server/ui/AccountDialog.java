package com.lanmanager.server.ui;

import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.*;
import java.awt.*;
import java.util.Arrays;
import javax.swing.*;

final class AccountDialog extends JDialog {
  private final TeacherFrame teacher;
  private final DataTable data =
      new DataTable(
          new String[] {"id", "username", "full_name", "role", "display_enabled"},
          new String[] {"Mã", "Tài khoản", "Họ tên", "Vai trò", "Trạng thái"});

  AccountDialog(TeacherFrame teacher) {
    super(teacher, "Quản lý tài khoản", false);
    this.teacher = teacher;
    setSize(800, 500);
    setLocationRelativeTo(teacher);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    add(data);
    data.hideColumns("id");
    JPanel toolbar = AppTheme.transparent(new BorderLayout(12, 0));
    toolbar.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
    JButton create = AppTheme.button("Tạo tài khoản", true);
    create.setIcon(TeacherIcons.icon("plus", 17, Color.WHITE));
    create.addActionListener(e -> teacher.createAccount(this::reload));
    toolbar.add(create, BorderLayout.WEST);
    JPopupMenu menu = new JPopupMenu();
    JMenuItem enable = new JMenuItem("Mở khóa", TeacherIcons.icon("shield-check", 17));
    JMenuItem disable = new JMenuItem("Khóa tài khoản", TeacherIcons.icon("lock", 17));
    JMenuItem password = new JMenuItem("Đặt lại mật khẩu", TeacherIcons.icon("settings", 17));
    JMenuItem refresh = new JMenuItem("Tải lại", TeacherIcons.icon("refresh-cw", 17));
    menu.add(enable);
    menu.add(disable);
    menu.add(password);
    menu.addSeparator();
    menu.add(refresh);
    enable.addActionListener(e -> enabled(true));
    disable.addActionListener(e -> enabled(false));
    password.addActionListener(e -> resetPassword());
    refresh.addActionListener(e -> reload());
    JButton more = AppTheme.button("Tác vụ tài khoản", false);
    more.setIcon(TeacherIcons.icon("chevron-down", 16));
    more.addActionListener(
        e -> {
          boolean selected = data.table.getSelectedRow() >= 0;
          enable.setEnabled(selected);
          disable.setEnabled(selected);
          password.setEnabled(selected);
          menu.show(more, 0, more.getHeight());
        });
    toolbar.add(more, BorderLayout.EAST);
    add(toolbar, BorderLayout.NORTH);
    reload();
  }

  private void reload() {
    SwingTasks.run(
        this,
        () -> teacher.call("accounts/list", JsonCodec.object()),
        r -> {
          var rows = r.getAsJsonArray("accounts");
          for (var item : rows) {
            var row = item.getAsJsonObject();
            row.addProperty(
                "display_enabled",
                row.get("enabled").getAsInt() == 1 ? "Đang hoạt động" : "Đã khóa");
          }
          data.setRows(rows);
        });
  }

  private void enabled(boolean enabled) {
    try {
      var row = data.selected();
      long id = row.get("id").getAsLong();
      if (JOptionPane.showConfirmDialog(
              this,
              (enabled ? "Mở khóa " : "Khóa ")
                  + row.get("username").getAsString()
                  + "? Khóa sẽ kết thúc phiên đang dùng.",
              "Xác nhận",
              JOptionPane.YES_NO_OPTION)
          != JOptionPane.YES_OPTION) return;
      SwingTasks.run(
          this,
          () ->
              teacher.call(
                  "accounts/enabled",
                  JsonCodec.object("accountId", id, "enabled", enabled ? 1 : 0)),
          r -> reload());
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, e.getMessage());
    }
  }

  private void resetPassword() {
    try {
      long id = data.selected().get("id").getAsLong();
      JPasswordField input = new JPasswordField();
      if (JOptionPane.showConfirmDialog(
              this,
              new Object[] {
                "Mật khẩu mới (ít nhất 10 ký tự). Tài khoản phải đăng nhập lại.", input
              },
              "Đặt lại mật khẩu",
              JOptionPane.OK_CANCEL_OPTION)
          != JOptionPane.OK_OPTION) return;
      char[] chars = input.getPassword();
      String password = new String(chars);
      Arrays.fill(chars, '\0');
      SwingTasks.run(
          this,
          () ->
              teacher.call(
                  "accounts/password", JsonCodec.object("accountId", id, "password", password)),
          r -> JOptionPane.showMessageDialog(this, "Đã đặt lại mật khẩu."));
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, e.getMessage());
    }
  }
}
