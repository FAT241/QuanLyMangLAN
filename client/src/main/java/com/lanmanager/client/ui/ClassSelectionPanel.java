package com.lanmanager.client.ui;

import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Chỉ hiển thị buổi đang mở và thao tác tham gia. */
final class ClassSelectionPanel extends JPanel {
  private final DefaultListModel<ClassChoice> model = new DefaultListModel<>();
  private final JList<ClassChoice> list = new JList<>(model);
  private final JButton join = AppTheme.button("Tham gia buổi", true);
  private final JButton reload = AppTheme.button("Tải lại danh sách", false);
  private final JPanel results = AppTheme.transparent(new CardLayout());
  private final JLabel count = AppTheme.muted("Đang tải các buổi đang mở…");
  private boolean available;

  ClassSelectionPanel(Runnable refresh, Consumer<ClassChoice> enter) {
    super(new BorderLayout(0, 20));
    setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
    JPanel heading = AppTheme.transparent(new BorderLayout(16, 10));
    JPanel copy = AppTheme.transparent(new GridLayout(0, 1, 0, 8));
    copy.add(AppTheme.title("Chọn buổi để tham gia", 26));
    copy.add(count);
    heading.add(copy);
    JPanel reloadArea = AppTheme.transparent(new GridBagLayout());
    reloadArea.add(reload);
    heading.add(reloadArea, BorderLayout.EAST);
    add(heading, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setFixedCellHeight(82);
    list.setBackground(AppTheme.SURFACE);
    list.getAccessibleContext().setAccessibleName("Danh sách buổi học và kiểm tra đang mở");
    list.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> owner, Object value, int index, boolean selected, boolean focused) {
            JLabel label =
                (JLabel) super.getListCellRendererComponent(owner, value, index, selected, focused);
            ClassChoice choice = (ClassChoice) value;
            label.setText(
                "<html><b>"
                    + AppTheme.html(choice.name())
                    + "</b><br><span style='color:#526674'>"
                    + choice.kind()
                    + " · Đang mở</span></html>");
            label.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
            label.setBackground(selected ? AppTheme.PRIMARY_SOFT : AppTheme.SURFACE);
            label.setForeground(AppTheme.TEXT);
            return label;
          }
        });
    JScrollPane scroll = new JScrollPane(list);
    scroll.setBorder(BorderFactory.createLineBorder(AppTheme.BORDER));
    results.add(scroll, "list");
    JPanel empty = AppTheme.card(new GridBagLayout(), 28);
    JPanel emptyCopy = AppTheme.transparent(new GridLayout(0, 1, 0, 12));
    emptyCopy.add(AppTheme.title("Chưa có buổi đang mở", 22));
    emptyCopy.add(AppTheme.muted("Buổi học sẽ xuất hiện khi giáo viên bắt đầu buổi."));
    empty.add(emptyCopy);
    results.add(empty, "empty");
    add(results);

    JPanel footer = AppTheme.transparent(new BorderLayout(24, 0));
    JTextArea notice =
        AppTheme.paragraph(
            "Khi tham gia, giáo viên có thể theo dõi màn hình, ứng dụng và lượt truy cập trong"
                + " buổi.");
    notice.setRows(2);
    footer.add(notice);
    footer.add(join, BorderLayout.EAST);
    add(footer, BorderLayout.SOUTH);
    reload.addActionListener(e -> refresh.run());
    join.addActionListener(
        e -> {
          if (list.getSelectedValue() != null) enter.accept(list.getSelectedValue());
        });
    list.addListSelectionListener(e -> updateActions());
    join.setEnabled(false);
  }

  void setClasses(List<ClassChoice> choices) {
    ClassChoice previous = list.getSelectedValue();
    model.clear();
    choices.forEach(model::addElement);
    if (previous != null) {
      for (int i = 0; i < model.size(); i++) {
        if (model.get(i).id() == previous.id()) list.setSelectedIndex(i);
      }
    }
    if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
    count.setText(
        choices.isEmpty()
            ? "Đợi giáo viên mở buổi hoặc tải lại danh sách."
            : choices.size() + " buổi đang mở · Chọn một buổi bên dưới");
    ((CardLayout) results.getLayout()).show(results, choices.isEmpty() ? "empty" : "list");
    updateActions();
  }

  void setAvailable(boolean available) {
    this.available = available;
    list.setEnabled(available);
    reload.setEnabled(available);
    updateActions();
  }

  private void updateActions() {
    join.setEnabled(available && list.getSelectedValue() != null);
  }
}
