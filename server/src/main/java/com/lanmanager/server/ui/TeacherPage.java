package com.lanmanager.server.ui;

/** Bốn khu vực chính; các nhật ký chỉ xuất hiện bên trong khu vực quản lý. */
enum TeacherPage {
  CLASSROOM("Lớp học", "Theo dõi sinh viên đang tham gia buổi.", 0),
  HISTORY("Lịch sử truy cập", "Tra cứu hoạt động theo sinh viên, nội dung và thời gian.", 1),
  VIOLATIONS("Đánh dấu & điểm", "Xem xét sự việc và ghi nhận điểm trừ.", 2),
  ALERTS("Cảnh báo", "Kiểm tra cảnh báo trước khi quyết định đánh dấu.", 2),
  EVIDENCE("Ảnh bằng chứng", "Ảnh đã lưu theo từng sự việc.", 2),
  MANAGEMENT("Quản lý", "Tài khoản, quy tắc và báo cáo của phòng máy.", 3),
  ERRORS("Lỗi kỹ thuật", "Theo dõi thời gian phát sinh và phục hồi.", 3),
  CONTROLS("Lệnh điều khiển", "Xem yêu cầu đã gửi và kết quả thực hiện.", 3),
  AUDIT("Lịch sử chỉnh sửa", "Nhật ký thay đổi đánh dấu và điểm trừ.", 3);
  final String title, description;
  final int section;

  TeacherPage(String title, String description, int section) {
    this.title = title;
    this.description = description;
    this.section = section;
  }

  @Override
  public String toString() {
    return title;
  }
}
