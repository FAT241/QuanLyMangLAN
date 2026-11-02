package com.lanmanager.server.ui;

import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import javax.swing.*;

/** Các công việc quản lý được gom thành mục, không nằm trên thanh theo dõi lớp. */
final class ManagementPanel extends JPanel {
  ManagementPanel(Runnable accounts, Runnable rules, Runnable report, Runnable logs) {
    super(new BorderLayout());
    setOpaque(false);
    JPanel cards = AppTheme.transparent(new GridLayout(2, 2, 18, 18));
    cards.add(
        card(
            "Tài khoản",
            "users",
            "Tạo tài khoản, mở khóa và đặt lại mật khẩu.",
            "Quản lý tài khoản",
            accounts));
    cards.add(
        card(
            "Quy tắc cảnh báo",
            "shield-check",
            "Thiết lập tên miền và ứng dụng cần xem xét trong buổi.",
            "Mở quy tắc",
            rules));
    cards.add(
        card(
            "Báo cáo buổi học",
            "file-text",
            "Xuất thời gian tham gia, sự việc và tổng điểm trừ.",
            "Xuất báo cáo",
            report));
    cards.add(
        card(
            "Nhật ký hệ thống",
            "activity",
            "Tra cứu lỗi kỹ thuật, lệnh điều khiển và lịch sử chỉnh sửa.",
            "Xem nhật ký",
            logs));
    cards.setPreferredSize(new Dimension(800, 410));
    JPanel holder = AppTheme.transparent(new BorderLayout());
    holder.add(cards, BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(holder);
    scroll.setBorder(null);
    scroll.getViewport().setBackground(AppTheme.BACKGROUND);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    add(scroll);
  }

  private JPanel card(
      String title, String icon, String description, String action, Runnable callback) {
    JPanel card = AppTheme.card(new BorderLayout(0, 16), 24);
    JPanel heading = AppTheme.transparent(new BorderLayout(14, 0));
    heading.add(new JLabel(TeacherIcons.icon(icon, 25, AppTheme.PRIMARY)), BorderLayout.WEST);
    heading.add(AppTheme.title(title, 19));
    card.add(heading, BorderLayout.NORTH);
    JTextArea body = AppTheme.paragraph(description);
    body.setRows(2);
    card.add(body);
    JButton button = AppTheme.button(action, false);
    button.setIcon(TeacherIcons.icon("arrow-up-right", 16));
    button.addActionListener(e -> callback.run());
    JPanel footer = AppTheme.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
    footer.add(button);
    card.add(footer, BorderLayout.SOUTH);
    return card;
  }
}
