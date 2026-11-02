package com.lanmanager.common.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class LoginPanelTest {
  @Test
  void emptyCredentialsShowInlineErrorWithoutCallingServer() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          LoginPanel login = new LoginPanel("Sinh viên", "Buổi học", "Liên hệ giáo viên.");
          AtomicInteger submitted = new AtomicInteger();
          login.onLogin((name, password) -> submitted.incrementAndGet());
          login.defaultButton().doClick();
          assertEquals(0, submitted.get());
          JTextArea error = (JTextArea) find(login, JTextArea.class, "Thông báo đăng nhập");
          assertTrue(error.getText().contains("Nhập tên đăng nhập và mật khẩu"));
        });
  }

  @Test
  void loginCannotBeSubmittedTwiceWhileRequestIsPending() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          LoginPanel login = new LoginPanel("Sinh viên", "Buổi học", "Liên hệ giáo viên.");
          JTextField name = (JTextField) find(login, JTextField.class, "Tên đăng nhập");
          JPasswordField password = (JPasswordField) find(login, JPasswordField.class, "Mật khẩu");
          AtomicInteger submitted = new AtomicInteger();
          login.onLogin(
              (username, chars) -> {
                assertEquals("sinhvien", username);
                assertArrayEquals("mat-khau-thu".toCharArray(), chars);
                submitted.incrementAndGet();
                java.util.Arrays.fill(chars, '\0');
                login.setBusy(true);
              });
          name.setText(" sinhvien ");
          password.setText("mat-khau-thu");
          password.postActionEvent();
          password.postActionEvent();
          login.defaultButton().doClick();
          assertEquals(1, submitted.get());
          assertEquals(0, password.getPassword().length);
        });
  }

  private Component find(Container root, Class<?> type, String accessibleName) {
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)
          && child.getAccessibleContext() != null
          && accessibleName.equals(child.getAccessibleContext().getAccessibleName())) return child;
      if (child instanceof Container container) {
        Component match = find(container, type, accessibleName);
        if (match != null) return match;
      }
    }
    return null;
  }
}
