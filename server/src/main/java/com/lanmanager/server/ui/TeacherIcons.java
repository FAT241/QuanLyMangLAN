package com.lanmanager.server.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.lanmanager.common.ui.AppTheme;
import java.awt.Color;
import javax.swing.Icon;

/** Icon SVG Lucide được đóng cùng tài nguyên; không tải mạng khi sử dụng. */
final class TeacherIcons {
  private TeacherIcons() {}

  static Icon icon(String name, int size) {
    return icon(name, size, AppTheme.MUTED);
  }

  static Icon icon(String name, int size, Color color) {
    var icon =
        new FlatSVGIcon(
            "icons/lucide/" + name + ".svg", size, size, TeacherIcons.class.getClassLoader());
    icon.setColorFilter(new FlatSVGIcon.ColorFilter(original -> color));
    return icon;
  }
}
