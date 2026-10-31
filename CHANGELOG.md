# VesNavi 0.1.0 — first public beta

Free navigation that shows turn-by-turn directions on the TFT dashboard of the **Vespa GTS Supertech 300 HPE**, for **iPhone** and **Android**. This is a beta — please try it and report what breaks.

**Install**
- iPhone (iOS 17+): `VespaNav.ipa` — sideload with Sideloadly, or add the SideStore source `apps.json`. See the README.
- Android 8.0+: `VesNavi.apk`.

**Features**
- Turn arrow, distance and street name on the TFT; correct roundabout exits; automatic rerouting; simulation mode to try it standing still.
- Speed limits (OpenStreetMap, Vietnamese default limits, or GraphHopper).
- Live engine data: RPM, coolant, battery, speed, odometer…; pop-up warnings on the TFT.
- Trips, fuel left, when to refuel, fill-ups detected from the fuel gauge.
- Service reminders by km / months.
- Home, Work, favourites, recent places, parking spot.
- English / Vietnamese.
- **Report a bug on GitHub** from the app (Bluetooth Lab or Settings → About), with a log that has personal details removed.

**Status**
- iPhone: connection, navigation on the TFT and engine data work on a real GTS Supertech.
- Android: not tried on a real scooter yet. PMP2 scooters (GTS Supertech) connect over classic Bluetooth — pair "BT ROUTER" in the phone's Bluetooth settings first.
- Other TFT models (Bluetooth service 0x1815) are supported in code but untested.

### Tiếng Việt

App dẫn đường **miễn phí** hiện hướng rẽ lên màn hình TFT của **Vespa GTS Supertech 300 HPE**, cho cả **iPhone** và **Android**. Đang thử nghiệm — rất mong mọi người dùng thử và báo lỗi.

**Cài đặt**
- iPhone (iOS 17 trở lên): `VespaNav.ipa` — cài bằng Sideloadly, hoặc thêm nguồn SideStore `apps.json`. Xem README.
- Android 8.0 trở lên: `VesNavi.apk`.

**Tính năng**
- Mũi tên rẽ, khoảng cách, tên đường trên màn hình xe; vòng xuyến đúng lối ra; tự tìm lại đường khi lệch; chạy thử bằng mô phỏng.
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
- Xe đời khác (dịch vụ Bluetooth 0x1815) có hỗ trợ trong code nhưng chưa thử.
