package com.lanmanager.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.common.ui.AppTheme;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class StudentScreenTest {
  @Test
  void ordinaryClassNeverOffersExamSubmission() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          var panel =
              new StudySessionPanel(
                  () -> fail("Không được bắt đầu thi"), () -> fail("Không được nộp bài"), () -> {});
          panel.showSession(new ClassChoice(1, "Thực hành mạng", false), 1000);
          panel.setAvailable(true);
          assertFalse(button(panel, "Bắt đầu làm bài").isVisible());
          assertTrue(button(panel, "Rời buổi").isEnabled());
        });
  }

  @Test
  void submittedExamHasNoActionToStartOrSubmitAgain() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          var panel =
              new StudySessionPanel(() -> fail("Lượt đã đóng"), () -> fail("Đã nộp rồi"), () -> {});
          panel.showSession(new ClassChoice(1, "Kiểm tra mạng", true), 1000);
          panel.setAvailable(true);
          panel.showAttempt(
              JsonCodec.object("started_at", 2000, "submitted_at", 3000, "closed_at", 3000));
          JButton submit = button(panel, "Ghi nhận nộp bài");
          assertFalse(submit.isVisible());
          assertFalse(submit.isEnabled());
        });
  }

  @Test
  void reloadingClassListKeepsChoiceAndOfflineStatePreventsJoining() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          AppTheme.install();
          AtomicReference<ClassChoice> entered = new AtomicReference<>();
          var panel = new ClassSelectionPanel(() -> {}, entered::set);
          ClassChoice first = new ClassChoice(1, "Buổi một", false);
          ClassChoice second = new ClassChoice(2, "Buổi hai", true);
          panel.setClasses(List.of(first, second));
          JList<?> classes = list(panel);
          classes.setSelectedIndex(1);
          panel.setClasses(List.of(second, first));
          panel.setAvailable(false);
          button(panel, "Tham gia buổi").doClick();
          assertNull(entered.get());
          panel.setAvailable(true);
          button(panel, "Tham gia buổi").doClick();
          assertEquals(second, entered.get());
        });
  }

  private JButton button(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton button && text.equals(button.getText())) return button;
      if (child instanceof Container container) {
        JButton match = button(container, text);
        if (match != null) return match;
      }
    }
    return null;
  }

  private JList<?> list(Container root) {
    for (Component child : root.getComponents()) {
      if (child instanceof JList<?> list) return list;
      if (child instanceof Container container) {
        JList<?> match = list(container);
        if (match != null) return match;
      }
    }
    return null;
  }
}
