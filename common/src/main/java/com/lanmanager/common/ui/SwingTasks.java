package com.lanmanager.common.ui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;

/** Đưa I/O ra khỏi EDT, trả kết quả lên EDT. */
public final class SwingTasks {
  private SwingTasks() {}

  public static <T> void run(java.awt.Component owner, Callable<T> task, Consumer<T> completed) {
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return task.call();
      }

      protected void done() {
        try {
          completed.accept(get());
        } catch (Exception e) {
          Throwable cause = e.getCause() == null ? e : e.getCause();
          JOptionPane.showMessageDialog(
              owner, cause.getMessage(), "Không thực hiện được", JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
  }
}
