package com.lanmanager.server.ui;

import com.google.gson.*;
import com.lanmanager.common.ui.AppTheme;
import com.lanmanager.common.ui.JsonTableModel;
import javax.swing.*;
import javax.swing.table.TableRowSorter;

/** Bảng chỉ đọc có tìm kiếm, giữ lựa chọn khi cập nhật dữ liệu. */
final class DataTable extends JPanel {
  final JsonTableModel model;
  final JTable table;
  private final String[] fields;
  private final java.util.List<javax.swing.table.TableColumn> allColumns;
  private final JPanel searchBar;
  private final JLabel summary = AppTheme.muted("Chưa có dữ liệu. Chọn buổi học để xem danh sách.");

  DataTable(String[] fields, String[] labels) {
    super(new java.awt.BorderLayout(0, 12));
    setBackground(AppTheme.SURFACE);
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    this.fields = fields.clone();
    model = new JsonTableModel(fields, labels);
    table =
        new JTable(model) {
          @Override
          public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
            if (row < 0 || column < 0) return null;
            Object value = getValueAt(row, column);
            return value == null ? null : value.toString();
          }
        };
    allColumns = java.util.Collections.list(table.getColumnModel().getColumns());
    for (var column : allColumns) {
      String field = fields[column.getModelIndex()];
      boolean content =
          java.util.Set.of("description", "details", "message", "note", "result", "pattern")
              .contains(field);
      column.setPreferredWidth(
          content ? 320 : field.endsWith("_at") || field.equals("full_name") ? 165 : 110);
    }
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(36);
    table.setShowGrid(false);
    table.setIntercellSpacing(new java.awt.Dimension(0, 0));
    table.setFillsViewportHeight(true);
    table.getTableHeader().setReorderingAllowed(false);
    var sorter = new TableRowSorter<>(model);
    table.setRowSorter(sorter);
    JTextField search = new JTextField();
    search.putClientProperty("JTextField.placeholderText", "Nhập tên hoặc nội dung cần tìm…");
    search.setPreferredSize(new java.awt.Dimension(240, 36));
    JPanel top = new JPanel(new java.awt.BorderLayout());
    searchBar = top;
    top.setOpaque(false);
    JLabel searchLabel = AppTheme.muted("Tìm trong danh sách   ");
    searchLabel.setLabelFor(search);
    top.add(searchLabel, java.awt.BorderLayout.WEST);
    top.add(search);
    add(top, java.awt.BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(table);
    scroll.setColumnHeaderView(table.getTableHeader());
    add(scroll);
    add(summary, java.awt.BorderLayout.SOUTH);
    search
        .getDocument()
        .addDocumentListener(
            new javax.swing.event.DocumentListener() {
              private void filter() {
                sorter.setRowFilter(
                    search.getText().isBlank()
                        ? null
                        : RowFilter.regexFilter(
                            "(?iu)" + java.util.regex.Pattern.quote(search.getText())));
              }

              public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filter();
              }

              public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filter();
              }

              public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filter();
              }
            });
  }

  JsonObject selected() {
    int row = table.getSelectedRow();
    if (row < 0) throw new IllegalArgumentException("Chọn một dòng trong bảng");
    return model.row(table.convertRowIndexToModel(row));
  }

  void setSearchVisible(boolean visible) {
    searchBar.setVisible(visible);
  }

  void setRows(JsonArray rows) {
    String id = null;
    try {
      id = selected().get("id").getAsString();
    } catch (IllegalArgumentException ignored) {
    }
    model.setRows(rows);
    summary.setText(
        rows.isEmpty() ? "Chưa có dữ liệu trong mục này." : rows.size() + " mục đã tải");
    if (id != null)
      for (int i = 0; i < rows.size(); i++)
        if (rows.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
          int index = table.convertRowIndexToView(i);
          if (index >= 0) table.setRowSelectionInterval(index, index);
          break;
        }
  }

  /** Giữ mã nội bộ trong dữ liệu để xử lý, không bắt người dùng đọc các cột kỹ thuật. */
  void hideColumns(String... names) {
    var hidden = java.util.Set.of(names);
    var columns = table.getColumnModel();
    for (int i = columns.getColumnCount() - 1; i >= 0; i--) {
      if (hidden.contains(fields[columns.getColumn(i).getModelIndex()]))
        columns.removeColumn(columns.getColumn(i));
    }
  }

  void showColumns(String... names) {
    var columns = table.getColumnModel();
    while (columns.getColumnCount() > 0) columns.removeColumn(columns.getColumn(0));
    for (String name : names) {
      for (var column : allColumns) {
        if (fields[column.getModelIndex()].equals(name)) columns.addColumn(column);
      }
    }
  }
}
