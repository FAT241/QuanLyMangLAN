package com.lanmanager.client.collector;

import com.google.gson.JsonObject;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Ảnh chỉ được chụp khi có yêu cầu, giảm kích thước trước khi truyền. */
public final class ScreenCaptureCollector {
  public JsonObject capture() throws Exception {
    if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("SCREEN_UNAVAILABLE");
    Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    BufferedImage original = new Robot().createScreenCapture(screen);
    double scale =
        Math.min(1.0, Math.min(1280.0 / original.getWidth(), 720.0 / original.getHeight()));
    BufferedImage image =
        new BufferedImage(
            Math.max(1, (int) (original.getWidth() * scale)),
            Math.max(1, (int) (original.getHeight() * scale)),
            BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.drawImage(original, 0, 0, image.getWidth(), image.getHeight(), null);
    } finally {
      graphics.dispose();
      original.flush();
    }
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", bytes);
      if (bytes.size() > 650000) throw new IllegalStateException("SCREEN_TOO_LARGE");
      return JsonCodec.object(
          "capturedAt",
          System.currentTimeMillis(),
          "mimeType",
          "image/jpeg",
          "width",
          image.getWidth(),
          "height",
          image.getHeight(),
          "base64",
          Base64.getEncoder().encodeToString(bytes.toByteArray()));
    } finally {
      image.flush();
    }
  }
}
