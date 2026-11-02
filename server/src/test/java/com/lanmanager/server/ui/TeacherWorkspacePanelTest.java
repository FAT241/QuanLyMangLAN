package com.lanmanager.server.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class TeacherWorkspacePanelTest {
  @Test
  void navigationKeepsDataAndReportsThePageBeingViewed() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          AtomicReference<TeacherPage> selected = new AtomicReference<>();
          Map<TeacherPage, JComponent> pages = pages();
          JTextField filter = new JTextField("youtube");
          pages.get(TeacherPage.HISTORY).add(filter);
          var workspace = workspace(pages, selected);
          workspace.showPage(TeacherPage.HISTORY);
          workspace.showPage(TeacherPage.VIOLATIONS);
          workspace.showPage(TeacherPage.HISTORY);
          assertEquals(TeacherPage.HISTORY, selected.get());
          assertTrue(pages.get(TeacherPage.HISTORY).isVisible());
          assertFalse(pages.get(TeacherPage.VIOLATIONS).isVisible());
          assertEquals("youtube", filter.getText());
        });
  }

  @Test
  void returningToASectionRestoresItsLastSubpage() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          var workspace = workspace(pages(), new AtomicReference<>());
          workspace.showPage(TeacherPage.ALERTS);
          workspace.showPage(TeacherPage.CLASSROOM);
          button(workspace, "Đánh dấu").doClick();
          assertEquals(TeacherPage.ALERTS, workspace.selectedPage());
          long navigationLinks =
              buttons(workspace).stream()
                  .filter(
                      button ->
                          Set.of("Lớp học", "Lịch sử", "Đánh dấu", "Quản lý")
                              .contains(button.getText()))
                  .count();
          assertEquals(4, navigationLinks);
        });
  }

  @Test
  void changingClassReplacesHistoryWithoutChangingTheSelectedPage() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          Map<TeacherPage, JComponent> pages = pages();
          var workspace = workspace(pages, new AtomicReference<>());
          workspace.showPage(TeacherPage.HISTORY);
          JPanel replacement = new JPanel();
          workspace.replacePage(TeacherPage.HISTORY, replacement);
          assertNull(pages.get(TeacherPage.HISTORY).getParent());
          assertTrue(replacement.isVisible());
          assertEquals(TeacherPage.HISTORY, workspace.selectedPage());
        });
  }

  @Test
  void compactWindowLeavesSpaceForStudentRowsAndIconsLoadLocally() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          for (String icon :
              new String[] {
                "monitor",
                "users",
                "file-text",
                "flag",
                "settings",
                "graduation-cap",
                "calendar-days"
              }) assertTrue(((FlatSVGIcon) TeacherIcons.icon(icon, 20)).hasFound(), icon);
          DataTable table =
              new DataTable(new String[] {"id", "full_name"}, new String[] {"Mã", "Họ tên"});
          Map<TeacherPage, JComponent> pages = pages();
          ClassroomOverviewPanel classroom = new ClassroomOverviewPanel(table, () -> {});
          classroom.setHasClass(true);
          pages.put(TeacherPage.CLASSROOM, classroom);
          var workspace = workspace(pages, new AtomicReference<>());
          workspace.showPage(TeacherPage.CLASSROOM);
          workspace.setSize(1084, 620);
          for (int i = 0; i < 3; i++) layout(workspace);
          JViewport viewport = (JViewport) table.table.getParent();
          assertTrue(
              viewport.getHeight() >= table.table.getRowHeight() * 3,
              "Danh sách phải hiển thị được ít nhất ba dòng ở cửa sổ nhỏ");
        });
  }

  private Map<TeacherPage, JComponent> pages() {
    Map<TeacherPage, JComponent> result = new EnumMap<>(TeacherPage.class);
    for (TeacherPage page : TeacherPage.values()) result.put(page, new JPanel());
    return result;
  }

  private TeacherWorkspacePanel workspace(
      Map<TeacherPage, JComponent> pages, AtomicReference<TeacherPage> selected) {
    return new TeacherWorkspacePanel(
        "Giáo viên thử",
        new JComboBox<>(new String[] {"Buổi thử"}),
        pages,
        new JPanel(),
        new JPopupMenu(),
        () -> {},
        selected::set);
  }

  private java.util.List<JToggleButton> buttons(Container root) {
    java.util.List<JToggleButton> found = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (child instanceof JToggleButton button) found.add(button);
      if (child instanceof Container container) found.addAll(buttons(container));
    }
    return found;
  }

  private JToggleButton button(Container root, String title) {
    return buttons(root).stream()
        .filter(button -> title.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }

  private void layout(Container root) {
    root.doLayout();
    for (Component child : root.getComponents())
      if (child instanceof Container container) layout(container);
  }
}
