package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Trang lớp học: tổng quan ngắn, số liệu trong buổi và danh sách sinh viên. */
final class ClassroomOverviewPanel extends JPanel {
  private final JLabel online = AppTheme.title("0", 26);
  private final JLabel alerts = AppTheme.title("0", 26);
  private final JLabel flagged = AppTheme.title("0", 26);
  private final CardLayout navigation = new CardLayout();
  private final JPanel body = AppTheme.transparent(navigation);
  private final JPanel welcome;

  ClassroomOverviewPanel(JComponent students, Runnable createClass) {
    super(new BorderLayout(0, 16));
    setOpaque(false);
    JPanel top = AppTheme.transparent(new BorderLayout(0, 16));
    welcome = AppTheme.card(new BorderLayout(24, 0), 0);
    welcome.setPreferredSize(new Dimension(600, 132));
    JPanel copy = AppTheme.transparent(new BorderLayout(0, 10));
    copy.setBorder(BorderFactory.createEmptyBorder(24, 24, 20, 16));
    copy.add(AppTheme.title("Theo dõi từng sinh viên", 21), BorderLayout.NORTH);
    JTextArea guide =
        AppTheme.paragraph(
            "Mở hồ sơ sinh viên để xem màn hình, ứng dụng và lịch sử sử dụng trong buổi.");
    guide.setRows(2);
    copy.add(guide);
    welcome.add(copy);
    welcome.add(new ClassroomPhoto(), BorderLayout.EAST);
    top.add(welcome, BorderLayout.NORTH);
    JPanel statistics = AppTheme.transparent(new GridLayout(1, 3, 12, 0));
    statistics.add(stat("Đang tham gia", "users", online));
    statistics.add(stat("Cảnh báo ghi nhận", "circle-alert", alerts));
    statistics.add(stat("Sự việc đã đánh dấu", "flag", flagged));
    top.add(statistics);
    add(top, BorderLayout.NORTH);
    body.add(students, "students");
    JPanel empty = AppTheme.card(new GridBagLayout(), 24);
    JPanel message = AppTheme.transparent(new GridLayout(0, 1, 0, 12));
    message.add(AppTheme.title("Chưa có buổi đang mở", 22));
    message.add(AppTheme.muted("Tạo một buổi để sinh viên bắt đầu tham gia."));
    JButton create = AppTheme.button("Tạo buổi mới", true);
    create.setIcon(TeacherIcons.icon("plus", 18, Color.WHITE));
    create.addActionListener(e -> createClass.run());
    message.add(create);
    empty.add(message);
    body.add(empty, "empty");
    add(body);
    setHasClass(false);
  }

  private JPanel stat(String title, String icon, JLabel number) {
    JPanel card = AppTheme.card(new BorderLayout(16, 0), 16);
    card.add(new JLabel(TeacherIcons.icon(icon, 26, AppTheme.PRIMARY)), BorderLayout.WEST);
    JPanel text = AppTheme.transparent(new BorderLayout(0, 4));
    text.add(AppTheme.muted(title), BorderLayout.NORTH);
    text.add(number);
    card.add(text);
    return card;
  }

  void setHasClass(boolean exists) {
    navigation.show(body, exists ? "students" : "empty");
  }

  @Override
  public void doLayout() {
    welcome.setVisible(getHeight() >= 500);
    super.doLayout();
  }

  void updateReport(JsonObject report) {
    int active = 0;
    for (JsonElement row : report.getAsJsonArray("students"))
      if (row.getAsJsonObject().get("ended_at").isJsonNull()) active++;
    online.setText(Integer.toString(active));
    alerts.setText(Integer.toString(report.getAsJsonArray("alerts").size()));
    flagged.setText(Integer.toString(report.getAsJsonArray("violations").size()));
  }

  private static final class ClassroomPhoto extends JPanel {
    private final BufferedImage image;

    ClassroomPhoto() {
      setPreferredSize(new Dimension(260, 132));
      setToolTipText("Ảnh minh họa: GOLDENHARRIER · Wikimedia Commons · CC BY-SA 4.0");
      getAccessibleContext().setAccessibleName("Ảnh minh họa một phòng máy tính");
      try (var stream = ClassroomPhoto.class.getResourceAsStream("/images/computer-lab.jpg")) {
        if (stream == null) throw new IOException("Thiếu ảnh phòng máy");
        image = ImageIO.read(stream);
      } catch (IOException error) {
        throw new IllegalStateException("Không đọc được ảnh minh họa", error);
      }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      double scale =
          Math.max(
              (double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
      int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
      g.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
      g.dispose();
    }
  }
}
