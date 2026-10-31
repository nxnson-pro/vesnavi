# VesNavi 0.1.0 — bản thử nghiệm công khai đầu tiên

App dẫn đường **miễn phí** hiện hướng rẽ lên màn hình TFT của **Vespa GTS Supertech 300 HPE**, cho cả **iPhone** và **Android**. Đang thử nghiệm — rất mong mọi người dùng thử và báo lỗi.

**Cài đặt**
- iPhone: `VespaNav.ipa` (cài bằng Sideloadly, hoặc SideStore với nguồn `apps.json`) — xem README.
- Android 8.0 trở lên: `VesNavi.apk`.

**Tính năng**
- Dẫn đường lên màn hình xe: mũi tên rẽ, khoảng cách, tên đường; vòng xuyến đúng lối ra; tự tìm lại đường khi lệch; chạy thử bằng mô phỏng.
- Giới hạn tốc độ (OpenStreetMap, ước theo luật VN, hoặc GraphHopper).
- Thông số xe trực tiếp: vòng tua, nước làm mát, ắc quy, tốc độ, ODO…; popup cảnh báo trên màn hình xe.
- Hành trình, xăng còn lại, ngày nên đổ xăng, tự nhận biết đổ xăng.
- Nhắc bảo dưỡng theo ODO / thời gian.
- Nhà, Công ty, Yêu thích, Gần đây, nhớ chỗ đỗ xe.
- Tiếng Việt / English.
- **Báo lỗi lên GitHub** ngay trong app (Bluetooth Lab hoặc Cài đặt → Thông tin), kèm log đã ẩn thông tin cá nhân.

**Tình trạng**
- iPhone: kết nối xe, dẫn đường và thông số xe đã chạy thật trên GTS Supertech.
- Android: chưa thử trên xe thật. Xe PMP2 (GTS Supertech) kết nối qua Bluetooth cổ điển — cần ghép đôi "BT ROUTER" trong Cài đặt → Bluetooth trước.
- Xe đời khác (PMP3, dịch vụ Bluetooth 0x1815) có hỗ trợ trong code nhưng chưa thử.
