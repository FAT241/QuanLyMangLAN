package com.lanmanager.common.ui;

import java.awt.*;
import java.util.Arrays;
import java.util.function.BiConsumer;
import javax.swing.*;

/** Màn hình đăng nhập riêng; không chứa thao tác sau khi đăng nhập. */
public final class LoginPanel extends JPanel {
  private final JTextField username = new JTextField(24);
  private final JPasswordField password = new JPasswordField(24);
  private final JButton submit = AppTheme.button("Đăng nhập", true);
  private final JCheckBox showPassword = new JCheckBox("Hiện mật khẩu");
  private final JTextArea feedback = AppTheme.paragraph(" ");
  private final JLabel connection = AppTheme.muted(" ");
  private final char echo;
  private BiConsumer<String, char[]> onLogin = (name, secret) -> Arrays.fill(secret, '\0');

  public LoginPanel(String role, String introduction, String help) {
    super(new GridBagLayout());
    setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
    echo = password.getEchoChar();
    JPanel card = AppTheme.card(new GridLayout(1, 2, 0, 0), 0);
    card.setPreferredSize(new Dimension(840, 552));
    JPanel brand = new JPanel(new BorderLayout(0, 24));
    brand.setBackground(new Color(0x124D4A));
    brand.setBorder(BorderFactory.createEmptyBorder(40, 32, 36, 32));
    JLabel eyebrow = new JLabel("QUẢN LÝ PHÒNG MÁY");
    eyebrow.setForeground(new Color(0xBEDED8));
    eyebrow.setFont(eyebrow.getFont().deriveFont(Font.BOLD, 12));
    brand.add(eyebrow, BorderLayout.NORTH);
    JPanel brandCopy = AppTheme.transparent(new BorderLayout(0, 20));
    JLabel heading = AppTheme.title("<html>Vào lớp.<br>Tập trung học.</html>", 32);
    heading.setForeground(Color.WHITE);
    brandCopy.add(heading, BorderLayout.NORTH);
    JTextArea summary = AppTheme.paragraph(introduction);
    summary.setForeground(new Color(0xD9EAE7));
    brandCopy.add(summary);
    brand.add(brandCopy);
    JLabel roleLabel = new JLabel("DÀNH CHO " + role.toUpperCase(java.util.Locale.ROOT));
    roleLabel.setForeground(new Color(0xBEDED8));
    brand.add(roleLabel, BorderLayout.SOUTH);
    card.add(brand);

    JPanel form = new JPanel(new GridBagLayout());
    form.setBackground(AppTheme.SURFACE);
    form.setBorder(BorderFactory.createEmptyBorder(32, 32, 28, 32));
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.NORTHWEST;
    addRow(form, c, AppTheme.title("Đăng nhập", 26), 0, 8);
    addRow(form, c, AppTheme.muted("Tài khoản " + role.toLowerCase(java.util.Locale.ROOT)), 1, 24);
    addField(form, c, "Tên đăng nhập", username, 2);
    addField(form, c, "Mật khẩu", password, 4);
    showPassword.setOpaque(false);
    showPassword.addActionListener(
        e -> password.setEchoChar(showPassword.isSelected() ? '\0' : echo));
    addRow(form, c, showPassword, 6, 8);
    feedback.setForeground(AppTheme.DANGER);
    feedback.setRows(2);
    feedback.getAccessibleContext().setAccessibleName("Thông báo đăng nhập");
    addRow(form, c, feedback, 7, 12);
    submit.setPreferredSize(new Dimension(220, 44));
    addRow(form, c, submit, 8, 16);
    JTextArea hint = AppTheme.paragraph(help);
    hint.setRows(2);
    addRow(form, c, hint, 9, 12);
    connection.setFont(connection.getFont().deriveFont(12f));
    addRow(form, c, connection, 10, 0);
    card.add(form);
    add(card);
    submit.addActionListener(e -> submit());
    password.addActionListener(e -> submit());
    username.addActionListener(e -> password.requestFocusInWindow());
  }

  private void addField(JPanel form, GridBagConstraints c, String text, JComponent field, int row) {
    JLabel label = new JLabel(text);
    label.setLabelFor(field);
    field.getAccessibleContext().setAccessibleName(text);
    field.setPreferredSize(new Dimension(260, 40));
    addRow(form, c, label, row, 6);
    addRow(form, c, field, row + 1, 14);
  }

  private void addRow(
      JPanel form, GridBagConstraints c, JComponent component, int row, int bottom) {
    c.gridy = row;
    c.insets = new Insets(0, 0, bottom, 0);
    form.add(component, c);
  }

  private void submit() {
    if (!submit.isEnabled()) return;
    String name = username.getText().strip();
    char[] secret = password.getPassword();
    if (name.isEmpty() || secret.length == 0) {
      Arrays.fill(secret, '\0');
      showError("Nhập tên đăng nhập và mật khẩu để tiếp tục.");
      (name.isEmpty() ? username : password).requestFocusInWindow();
      return;
    }
    password.setText("");
    onLogin.accept(name, secret);
  }

  public void onLogin(BiConsumer<String, char[]> handler) {
    onLogin = handler;
  }

  public void setBusy(boolean busy) {
    submit.setEnabled(!busy);
    username.setEnabled(!busy);
    password.setEnabled(!busy);
    showPassword.setEnabled(!busy);
    submit.setText(busy ? "Đang đăng nhập…" : "Đăng nhập");
    if (busy) feedback.setText(" ");
  }

  public void showError(String message) {
    feedback.setText(
        message == null || message.isBlank() ? "Không đăng nhập được. Hãy thử lại." : message);
  }

  public void setConnection(String message, boolean connected) {
    connection.setText(message);
    connection.setForeground(connected ? AppTheme.PRIMARY : AppTheme.MUTED);
  }

  public void focusUsername() {
    username.requestFocusInWindow();
  }

  public JButton defaultButton() {
    return submit;
  }

  public void clearPassword() {
    password.setText("");
    showPassword.setSelected(false);
    password.setEchoChar(echo);
  }
}
