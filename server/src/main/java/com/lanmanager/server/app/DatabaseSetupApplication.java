package com.lanmanager.server.app;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.ui.SwingTasks;
import com.lanmanager.server.storage.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Arrays;
import javax.swing.*;

/** Chạy trong IDE để chuẩn bị PostgreSQL, kể cả khi pgAdmin đã lưu mật khẩu quản trị. */
public final class DatabaseSetupApplication {
  private DatabaseSetupApplication() {}

  public static void main(String[] args) {
    com.lanmanager.common.ui.AppTheme.install();
    SwingUtilities.invokeLater(DatabaseSetupApplication::openWindow);
  }

  private static void openWindow() {
    JFrame frame = new JFrame("Chuẩn bị PostgreSQL cho phòng máy");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
    form.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
    var service = new DatabaseSetupService();
    JTextField host = new JTextField("127.0.0.1"),
        port = new JTextField("5432"),
        database = new JTextField("lan_manager"),
        username = new JTextField("lan_manager_app"),
        administrator = new JTextField("postgres");
    JPasswordField password = new JPasswordField(service.randomPassword()),
        adminPassword = new JPasswordField();
    JLabel status =
        new JLabel("Nếu không nhớ mật khẩu postgres: xuất SQL, chạy bằng kết nối pgAdmin đang mở.");
    form.add(new JLabel("Địa chỉ PostgreSQL"));
    form.add(host);
    form.add(new JLabel("Cổng"));
    form.add(port);
    form.add(new JLabel("Database mới"));
    form.add(database);
    form.add(new JLabel("Tài khoản database mới"));
    form.add(username);
    form.add(new JLabel("Mật khẩu tài khoản mới (đã sinh ngẫu nhiên)"));
    form.add(password);
    form.add(new JLabel("Quản trị PostgreSQL (chỉ dùng khi tạo trực tiếp)"));
    form.add(administrator);
    form.add(new JLabel("Mật khẩu quản trị (có thể để trống khi xuất SQL)"));
    form.add(adminPassword);
    JButton direct = new JButton("Tạo database trực tiếp"),
        sql = new JButton("Xuất SQL cho pgAdmin"),
        check = new JButton("Kiểm tra và tạo bảng");
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
    actions.add(direct);
    actions.add(sql);
    actions.add(check);
    frame.add(form);
    frame.add(actions, BorderLayout.SOUTH);
    frame.add(status, BorderLayout.NORTH);
    frame.pack();
    frame.setMinimumSize(new Dimension(850, 350));
    frame.setLocationRelativeTo(null);
    java.util.function.Supplier<DatabaseSetupService.SetupPlan> plan =
        () -> {
          char[] chars = password.getPassword();
          try {
            return new DatabaseSetupService.SetupPlan(
                host.getText().strip(),
                Integer.parseInt(port.getText().strip()),
                database.getText().strip(),
                username.getText().strip(),
                new String(chars));
          } finally {
            Arrays.fill(chars, '\0');
          }
        };
    Path config =
        ApplicationConfig.findProjectConfig("server.properties")
            .resolveSibling("server-local.properties");
    if (Files.isRegularFile(config)) {
      try {
        var saved = new ApplicationConfig(config);
        String url = saved.text("database.url", "");
        if (url.startsWith("jdbc:postgresql://")) {
          var address = java.net.URI.create(url.substring(5));
          host.setText(address.getHost());
          port.setText("" + (address.getPort() < 0 ? 5432 : address.getPort()));
          database.setText(address.getPath().substring(1));
          username.setText(saved.text("database.user", "lan_manager_app"));
          String secret = saved.text("database.password", "");
          if (!secret.isEmpty()) password.setText(secret);
        }
      } catch (Exception ex) {
        status.setText("Không đọc được cấu hình đã lưu; kiểm tra các trường trước khi tiếp tục.");
      }
    }
    java.util.function.BooleanSupplier overwrite =
        () ->
            !Files.exists(config)
                || JOptionPane.showConfirmDialog(
                        frame,
                        "Thay cấu hình database trong server-local.properties? Các cấu hình mạng"
                            + " vẫn được giữ.",
                        "Xác nhận",
                        JOptionPane.YES_NO_OPTION)
                    == JOptionPane.YES_OPTION;
    direct.addActionListener(
        e -> {
          try {
            var selected = plan.get();
            String adminName = administrator.getText().strip();
            char[] chars = adminPassword.getPassword();
            String secret = new String(chars);
            Arrays.fill(chars, '\0');
            if (secret.isEmpty())
              throw new IllegalArgumentException(
                  "Nhập mật khẩu quản trị hoặc chọn xuất SQL để dùng kết nối pgAdmin đã mở");
            if (!overwrite.getAsBoolean()) return;
            SwingTasks.run(
                frame,
                () -> {
                  String adminUrl = selected.settings().url();
                  adminUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + "postgres";
                  service.createDatabase(
                      new DatabaseSettings(adminUrl, adminName, secret), selected);
                  service.saveConfiguration(config, selected);
                  service.initializeSchema(selected);
                  return true;
                },
                r -> {
                  adminPassword.setText("");
                  status.setText(
                      "Đã tạo database và bảng. Chạy CreateTeacherApplication.java tiếp theo.");
                });
          } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage());
          }
        });
    sql.addActionListener(
        e -> {
          try {
            var selected = plan.get();
            if (!overwrite.getAsBoolean()) return;
            Path output =
                ApplicationConfig.findProjectConfig("server.properties")
                    .toAbsolutePath()
                    .getParent()
                    .getParent()
                    .resolve("database")
                    .resolve("setup-local-" + System.currentTimeMillis());
            SwingTasks.run(
                frame,
                () -> {
                  var files = service.exportSql(output, selected);
                  service.saveConfiguration(config, selected);
                  return files;
                },
                files -> {
                  JTextArea instructions =
                      new JTextArea(
                          "Trong Query Tool của pgAdmin đã kết nối:\n1. Mở và chạy riêng file:\n"
                              + files.get(0)
                              + "\n2. Bật Auto-commit, mở và chạy riêng file:\n"
                              + files.get(1)
                              + "\n"
                              + "3. Quay lại đây bấm Kiểm tra và tạo bảng.\n\n"
                              + "Các file SQL chứa mật khẩu database mới, chỉ lưu trên máy bạn.");
                  instructions.setEditable(false);
                  instructions.setLineWrap(true);
                  instructions.setWrapStyleWord(true);
                  instructions.setColumns(70);
                  instructions.setRows(10);
                  JOptionPane.showMessageDialog(
                      frame,
                      new JScrollPane(instructions),
                      "SQL đã chuẩn bị",
                      JOptionPane.INFORMATION_MESSAGE);
                  status.setText(
                      "Đã lưu SQL và cấu hình local; chưa xác nhận database được tạo trong"
                          + " pgAdmin.");
                });
          } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage());
          }
        });
    check.addActionListener(
        e -> {
          try {
            var selected = plan.get();
            if (!overwrite.getAsBoolean()) return;
            SwingTasks.run(
                frame,
                () -> {
                  service.initializeSchema(selected);
                  service.saveConfiguration(config, selected);
                  return true;
                },
                r ->
                    status.setText(
                        "Kết nối thành công, bảng/view đã sẵn sàng. Chạy"
                            + " CreateTeacherApplication.java."));
          } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage());
          }
        });
    frame.setVisible(true);
  }
}
