package com.lanmanager.common.ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;

/** Màu sắc, khoảng cách và kiểu chữ dùng thống nhất giữa hai ứng dụng. */
public final class AppTheme {
  public static final Color BACKGROUND = new Color(0xF3F6F8);
  public static final Color SURFACE = Color.WHITE;
  public static final Color TEXT = new Color(0x172F3B);
  public static final Color MUTED = new Color(0x526674);
  public static final Color PRIMARY = new Color(0x0F766E);
  public static final Color PRIMARY_SOFT = new Color(0xE8F5F1);
  public static final Color BORDER = new Color(0xD5DFE5);
  public static final Color DANGER = new Color(0xB42318);

  private AppTheme() {}

  /** Giữ cửa sổ trong vùng màn hình làm việc, kể cả máy có độ phân giải nhỏ. */
  public static void fitWindow(
      JFrame frame, int width, int height, int minimumWidth, int minimumHeight) {
    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    frame.setMinimumSize(
        new Dimension(
            Math.min(minimumWidth, screen.width), Math.min(minimumHeight, screen.height)));
    frame.setSize(Math.min(width, screen.width), Math.min(height, screen.height));
    frame.setLocationRelativeTo(null);
  }

  public static void install() {
    FlatLightLaf.setup();
    UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 14));
    UIManager.put("Panel.background", BACKGROUND);
    UIManager.put("Label.foreground", TEXT);
    UIManager.put("Component.arc", 10);
    UIManager.put("Button.arc", 10);
    UIManager.put("TextComponent.arc", 10);
    UIManager.put("Component.focusColor", PRIMARY);
    UIManager.put("Component.focusedBorderColor", PRIMARY);
    UIManager.put("Component.focusWidth", 2);
    UIManager.put("Component.minimumHeight", 36);
    UIManager.put("Button.default.background", PRIMARY);
    UIManager.put("Button.default.foreground", Color.WHITE);
    UIManager.put("Button.default.focusedBackground", PRIMARY.darker());
    UIManager.put("Table.rowHeight", 36);
    UIManager.put("Table.showVerticalLines", false);
    UIManager.put("Table.selectionBackground", PRIMARY_SOFT);
    UIManager.put("Table.selectionForeground", TEXT);
    UIManager.put("Table.alternateRowColor", new Color(0xF8FAFB));
    UIManager.put("TableHeader.height", 40);
    UIManager.put("TableHeader.background", new Color(0xEDF2F5));
    UIManager.put("TabbedPane.selectedBackground", PRIMARY_SOFT);
    UIManager.put("TabbedPane.underlineColor", PRIMARY);
    UIManager.put("TabbedPane.tabHeight", 42);
    UIManager.put("ScrollBar.width", 10);
  }

  public static JLabel title(String text, int size) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD, size));
    label.setForeground(TEXT);
    return label;
  }

  public static JLabel muted(String text) {
    JLabel label = new JLabel(text);
    label.setForeground(MUTED);
    return label;
  }

  public static JTextArea paragraph(String text) {
    JTextArea area = new JTextArea(text);
    area.setEditable(false);
    area.setFocusable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setOpaque(false);
    area.setForeground(MUTED);
    area.setFont(UIManager.getFont("Label.font"));
    area.setBorder(null);
    return area;
  }

  public static JButton button(String text, boolean primary) {
    JButton button = new JButton(text);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setMargin(new Insets(9, 16, 9, 16));
    if (primary) {
      button.setBackground(PRIMARY);
      button.setForeground(Color.WHITE);
      button.setFont(button.getFont().deriveFont(Font.BOLD));
    }
    return button;
  }

  public static JPanel transparent(LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  public static JPanel card(LayoutManager layout, int padding) {
    JPanel panel = new JPanel(layout);
    panel.setBackground(SURFACE);
    panel.setBorder(cardBorder(padding));
    return panel;
  }

  public static Border cardBorder(int padding) {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(BORDER),
        BorderFactory.createEmptyBorder(padding, padding, padding, padding));
  }

  public static String html(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
