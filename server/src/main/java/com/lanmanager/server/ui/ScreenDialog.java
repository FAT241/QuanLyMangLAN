package com.lanmanager.server.ui;

import com.google.gson.JsonObject;
import java.awt.*;
import java.util.Base64;
import javax.swing.*;

final class ScreenDialog extends JDialog {
  ScreenDialog(JFrame owner, JsonObject image, String title) {
    super(owner, title, false);
    byte[] bytes = Base64.getDecoder().decode(image.get("base64").getAsString());
    add(new JScrollPane(new JLabel(new ImageIcon(bytes))));
    add(
        new JLabel(
            "Ảnh chụp lúc "
                + java.time.Instant.ofEpochMilli(image.get("capturedAt").getAsLong())
                    .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                + ". Bấm Xem màn hình để lấy ảnh mới."),
        BorderLayout.SOUTH);
    setSize(1000, 650);
    setLocationRelativeTo(owner);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
  }
}
