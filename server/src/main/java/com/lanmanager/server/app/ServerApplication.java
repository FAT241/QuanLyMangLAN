package com.lanmanager.server.app;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.ui.AppTheme;
import com.lanmanager.server.ServerBackend;
import com.lanmanager.server.ui.TeacherFrame;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

public final class ServerApplication {
  public static void main(String[] args) throws Exception {
    AppTheme.install();
    ServerBackend backend;
    try {
      backend =
          new ServerBackend(
              new ApplicationConfig(
                  args.length > 0 ? Path.of(args[0]) : ApplicationConfig.serverConfigPath()));
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(ServerApplication.class)
          .error("Không khởi động được máy chủ", e);
      SwingUtilities.invokeLater(
          () ->
              javax.swing.JOptionPane.showMessageDialog(
                  null,
                  "Không khởi động được máy chủ: "
                      + e.getMessage()
                      + "\n\n"
                      + "Nếu chưa có database, chạy DatabaseSetupApplication.java trước. Kiểm tra"
                      + " cấu hình PostgreSQL và các cổng mạng.",
                  "Không khởi động được",
                  javax.swing.JOptionPane.ERROR_MESSAGE));
      return;
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    backend.close();
                  } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(ServerApplication.class)
                        .error("Không đóng được máy chủ", e);
                  }
                },
                "teacher-shutdown"));
    SwingUtilities.invokeLater(() -> new TeacherFrame(backend).setVisible(true));
  }
}
