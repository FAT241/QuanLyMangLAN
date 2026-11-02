package com.lanmanager.server.app;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.ui.SwingTasks;
import com.lanmanager.server.account.AccountService;
import com.lanmanager.server.storage.ClassroomDatabase;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.*;

/** Chạy một lần trong IDE để tạo giáo viên đầu tiên, trước khi mở máy chủ. */
public final class CreateTeacherApplication {
  public static void main(String[] args) throws Exception {
    com.lanmanager.common.ui.AppTheme.install();
    var config =
        new ApplicationConfig(
            (args.length > 0 ? Path.of(args[0]) : ApplicationConfig.serverConfigPath()));
    SwingUtilities.invokeLater(
        () -> {
          JFrame frame = new JFrame("Tạo giáo viên đầu tiên");
          frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
          JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 10, 10));
          form.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
          JTextField username = new JTextField(), fullName = new JTextField();
          JPasswordField password = new JPasswordField();
          JButton create = new JButton("Tạo giáo viên");
          form.add(new JLabel("Tên đăng nhập"));
          form.add(username);
          form.add(new JLabel("Họ tên"));
          form.add(fullName);
          form.add(new JLabel("Mật khẩu (ít nhất 10 ký tự)"));
          form.add(password);
          form.add(new JLabel("Dừng máy chủ trước khi tạo lần đầu."));
          form.add(create);
          frame.add(form);
          frame.pack();
          frame.setLocationRelativeTo(null);
          create.addActionListener(
              e -> {
                String name = username.getText(), display = fullName.getText();
                char[] chars = password.getPassword();
                String secret = new String(chars);
                Arrays.fill(chars, '\0');
                password.setText("");
                SwingTasks.run(
                    frame,
                    () -> {
                      try (var database =
                          new ClassroomDatabase(
                              com.lanmanager.server.storage.DatabaseSettings.from(config))) {
                        // Khóa giao dịch để hai cửa sổ khởi tạo không tạo hai người quản trị đầu
                        // tiên.
                        database.transaction(
                            () -> {
                              if (!database
                                  .query("SELECT id FROM accounts WHERE role='TEACHER'")
                                  .isEmpty())
                                throw new IllegalStateException(
                                    "Đã có giáo viên. Đăng nhập máy chủ để quản lý tài khoản.");
                              new AccountService(database)
                                  .createAccount(name, secret, display, "TEACHER");
                            });
                      }
                      return true;
                    },
                    r -> {
                      JOptionPane.showMessageDialog(
                          frame, "Đã tạo giáo viên. Chạy ServerApplication.java để đăng nhập.");
                      frame.dispose();
                    });
              });
          frame.setVisible(true);
        });
  }
}
