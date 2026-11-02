package com.lanmanager.server.ui;

import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Khung ứng dụng với bốn trang chính, header chọn buổi và nội dung độc lập. */
final class TeacherWorkspacePanel extends JPanel {
  private static final Color SIDE = new Color(0x143A3D);
  private final CardLayout navigation = new CardLayout();
  private final JPanel pages = AppTheme.transparent(navigation);
  private final Map<TeacherPage, JComponent> views = new EnumMap<>(TeacherPage.class);
  private final JToggleButton[] links = new JToggleButton[4];
  private final TeacherPage[] lastPages = {
    TeacherPage.CLASSROOM, TeacherPage.HISTORY, TeacherPage.VIOLATIONS, TeacherPage.MANAGEMENT
  };
  private final JComboBox<TeacherPage> subsection = new JComboBox<>();
  private final JPanel subnavigation = AppTheme.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
  private final JLabel title = AppTheme.title("Lớp học", 27);
  private final JLabel hint = AppTheme.muted(" ");
  private final Consumer<TeacherPage> onNavigate;
  private TeacherPage selected;
  private boolean updating;

  TeacherWorkspacePanel(
      String teacher,
      JComboBox<?> classes,
      Map<TeacherPage, JComponent> content,
      JPanel actions,
      JPopupMenu classOptions,
      Runnable logout,
      Consumer<TeacherPage> onNavigate) {
    super(new BorderLayout());
    this.onNavigate = onNavigate;
    JPanel sidebar = new JPanel(new BorderLayout(0, 24));
    sidebar.setPreferredSize(new Dimension(198, 400));
    sidebar.setBackground(SIDE);
    sidebar.setBorder(BorderFactory.createEmptyBorder(28, 16, 22, 16));
    JPanel brand = AppTheme.transparent(new BorderLayout(12, 0));
    brand.add(new JLabel(TeacherIcons.icon("graduation-cap", 32, Color.WHITE)), BorderLayout.WEST);
    JPanel brandText = AppTheme.transparent(new GridLayout(0, 1, 0, 3));
    JLabel name = AppTheme.title("Phòng máy", 20);
    name.setForeground(Color.WHITE);
    JLabel role = new JLabel("Dành cho giáo viên");
    role.setForeground(new Color(0xBCD5D2));
    role.setFont(role.getFont().deriveFont(12f));
    brandText.add(name);
    brandText.add(role);
    brand.add(brandText);
    sidebar.add(brand, BorderLayout.NORTH);
    JPanel menu = AppTheme.transparent(new GridLayout(0, 1, 0, 10));
    String[] names = {"Lớp học", "Lịch sử", "Đánh dấu", "Quản lý"};
    String[] icons = {"monitor", "file-text", "flag", "settings"};
    ButtonGroup group = new ButtonGroup();
    for (int i = 0; i < links.length; i++) {
      int index = i;
      JToggleButton link = new JToggleButton(names[i]);
      link.setIcon(TeacherIcons.icon(icons[i], 20, new Color(0xDCEBE9)));
      link.setSelectedIcon(TeacherIcons.icon(icons[i], 20, AppTheme.PRIMARY));
      link.setHorizontalAlignment(SwingConstants.LEFT);
      link.setIconTextGap(12);
      link.setPreferredSize(new Dimension(160, 47));
      link.setMargin(new Insets(12, 14, 12, 14));
      link.putClientProperty(
          "FlatLaf.style",
          "arc: 10; borderWidth: 0; background: #143A3D; foreground: #DCEBE9; selectedBackground:"
              + " #E8F5F1; selectedForeground: #0F766E");
      link.addActionListener(e -> showPage(lastPages[index]));
      links[i] = link;
      group.add(link);
      menu.add(link);
    }
    JPanel menuArea = AppTheme.transparent(new BorderLayout());
    menuArea.add(menu, BorderLayout.NORTH);
    sidebar.add(menuArea);
    JPanel profile = AppTheme.transparent(new BorderLayout(0, 14));
    JLabel fullName =
        new JLabel(
            teacher, TeacherIcons.icon("users", 18, new Color(0xBCD5D2)), SwingConstants.LEFT);
    fullName.setIconTextGap(10);
    fullName.setForeground(Color.WHITE);
    fullName.setToolTipText(teacher);
    profile.add(fullName, BorderLayout.NORTH);
    JButton signOut =
        new JButton("Đăng xuất", TeacherIcons.icon("log-out", 18, new Color(0xBCD5D2)));
    signOut.setHorizontalAlignment(SwingConstants.LEFT);
    signOut.putClientProperty(
        "FlatLaf.style", "arc: 10; borderWidth: 0; background: #143A3D; foreground: #DCEBE9");
    signOut.addActionListener(e -> logout.run());
    profile.add(signOut, BorderLayout.SOUTH);
    sidebar.add(profile, BorderLayout.SOUTH);
    add(sidebar, BorderLayout.WEST);

    JPanel main = AppTheme.transparent(new BorderLayout());
    JPanel header = new JPanel(new BorderLayout(18, 0));
    header.setBackground(AppTheme.SURFACE);
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.BORDER),
            BorderFactory.createEmptyBorder(18, 24, 18, 24)));
    JPanel chooser = AppTheme.transparent(new BorderLayout(12, 0));
    JLabel classLabel =
        new JLabel("Buổi hiện tại", TeacherIcons.icon("calendar-days", 18), SwingConstants.LEFT);
    classLabel.setIconTextGap(8);
    classLabel.setLabelFor(classes);
    chooser.add(classLabel, BorderLayout.WEST);
    classes.setPreferredSize(new Dimension(350, 38));
    chooser.add(classes);
    header.add(chooser);
    JButton classMenu = AppTheme.button("Quản lý buổi", false);
    classMenu.setIcon(TeacherIcons.icon("chevron-down", 16));
    classMenu.setHorizontalTextPosition(SwingConstants.LEFT);
    classMenu.addActionListener(e -> classOptions.show(classMenu, 0, classMenu.getHeight()));
    header.add(classMenu, BorderLayout.EAST);
    main.add(header, BorderLayout.NORTH);

    JPanel body = AppTheme.transparent(new BorderLayout(0, 18));
    body.setBorder(BorderFactory.createEmptyBorder(24, 24, 20, 24));
    JPanel top = AppTheme.transparent(new BorderLayout(0, 16));
    JPanel heading = AppTheme.transparent(new BorderLayout(20, 0));
    JPanel copy = AppTheme.transparent(new BorderLayout(0, 8));
    copy.add(title, BorderLayout.NORTH);
    copy.add(hint);
    heading.add(copy);
    heading.add(actions, BorderLayout.EAST);
    top.add(heading, BorderLayout.NORTH);
    JLabel sublabel = AppTheme.muted("Nội dung");
    sublabel.setLabelFor(subsection);
    subnavigation.add(sublabel);
    subsection.setPreferredSize(new Dimension(250, 36));
    subnavigation.add(subsection);
    top.add(subnavigation);
    body.add(top, BorderLayout.NORTH);
    content.forEach(this::replacePage);
    body.add(pages);
    main.add(body);
    add(main);
    subsection.addActionListener(
        e -> {
          if (!updating && subsection.getSelectedItem() instanceof TeacherPage page) showPage(page);
        });
  }

  void showPage(TeacherPage page) {
    if (!views.containsKey(page))
      throw new IllegalArgumentException("Trang chưa được khởi tạo: " + page);
    selected = page;
    lastPages[page.section] = page;
    links[page.section].setSelected(true);
    title.setText(page.title);
    hint.setText(page.description);
    updating = true;
    subsection.removeAllItems();
    for (TeacherPage candidate : TeacherPage.values())
      if (candidate.section == page.section) subsection.addItem(candidate);
    subsection.setSelectedItem(page);
    subnavigation.setVisible(subsection.getItemCount() > 1);
    updating = false;
    navigation.show(pages, page.name());
    onNavigate.accept(page);
    revalidate();
    repaint();
  }

  void replacePage(TeacherPage page, JComponent view) {
    JComponent previous = views.put(page, view);
    if (previous != null) pages.remove(previous);
    pages.add(view, page.name());
    if (selected == page) navigation.show(pages, page.name());
    pages.revalidate();
    pages.repaint();
  }

  TeacherPage selectedPage() {
    return selected;
  }

  void setHint(String text) {
    hint.setText(text);
  }
}
