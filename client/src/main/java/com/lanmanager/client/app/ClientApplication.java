package com.lanmanager.client.app;

import com.lanmanager.client.ClientAgent;
import com.lanmanager.client.ui.StudentFrame;
import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.common.ui.AppTheme;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

/** Chạy file này trong IDE để mở chương trình sinh viên. */
public final class ClientApplication {
  public static void main(String[] args) throws Exception {
    AppTheme.install();
    var agent =
        new ClientAgent(
            new ApplicationConfig(
                (args.length > 0 ? Path.of(args[0]) : ApplicationConfig.prepareClientConfig())));
    Runtime.getRuntime().addShutdownHook(new Thread(agent::close, "student-shutdown"));
    SwingUtilities.invokeLater(() -> new StudentFrame(agent).setVisible(true));
  }
}
