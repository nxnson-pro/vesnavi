# VesNavi 1.0.6

- Phát hành công khai: giấy phép MIT, ghi chú giao thức viết lại theo hướng tương thích.
- Nguồn **SideStore / AltStore**: thêm `https://github.com/nxnson-pro/vesnavi/releases/latest/download/apps.json` vào SideStore → Sources để nhận bản cập nhật ngay trên iPhone.

# VesNavi 1.0.5

- Màn hình **lưu ý an toàn** khi mở app lần đầu (không thao tác điện thoại khi lái, chỉ đường/giới hạn tốc độ chỉ để tham khảo, app không chính hãng); xem lại ở Cài đặt → Thông tin.
- Ghi công dữ liệu **© OpenStreetMap contributors** (giấy phép ODbL) trên màn hình tuyến đường và trong Cài đặt → Thông tin.
- README: thêm miễn trừ trách nhiệm và ghi công dữ liệu.

# VesNavi 1.0.4

- Dòng "Xem tất cả hành trình" và "Tất cả hạng mục" chạm chỗ nào trên dòng cũng mở được (trước đây chỉ nhận chạm vào chữ và mũi tên).
- Dưới thông số xe hiện "Cập nhật vừa xong" thay cho "Cập nhật sau 0 giây nữa".
- README có GIF giới thiệu (quay tự động trên máy ảo) và bản tiếng Anh `README.en.md`.

# VesNavi 1.0.3

- Không còn lưu nhầm chỗ đỗ bằng vị trí cũ lúc đang chạy: chỉ dùng vị trí đo trong lúc tắt xe; không đo được thì không lưu (cần quyền vị trí "Luôn luôn" khi điện thoại trong túi).

# VesNavi 1.0.2

- Dòng chữ thông số trên đồng hồ xe: chỉ hiện khi bật màn hình dẫn đường (đã thử trên xe) → còn 2 lựa chọn **Tắt** / **Nhiệt độ nước mát + ắc quy**; ai đang chọn "Dòng chữ" sẽ tự chuyển sang kiểu hiện được.
- Chỗ đỗ xe chính xác hơn: lúc tắt xe app đo GPS độ chính xác cao tới 20 giây (trước đây dùng vị trí thô, lệch tới ~100 m), hiện vòng sai số trên bản đồ, thêm nút **"Đặt lại tại vị trí hiện tại"** khi đứng cạnh xe.

# VesNavi 1.0.1

- Sửa lỗi GraphHopper "profile can only be one of [car, bike, foot]": gói miễn phí không có kiểu Xe tay ga → mặc định dùng **Ô tô**, key cũ đang để Xe tay ga sẽ tự chuyển sang Ô tô.

# VesNavi 1.0.0 — bản chạy thử đầu tiên

Cài: tải `VespaNav.ipa` bên dưới → Sideloadly (Apple ID miễn phí, cài lại mỗi 7 ngày bằng chính file này).

## Dẫn đường lên màn hình xe
- Mũi tên rẽ, khoảng cách, tên đường, thời gian còn lại trên TFT của Vespa GTS Supertech 300 HPE (Cobo/PMP2 qua BLE; xe PMP3 cũng hỗ trợ).
- Vòng xuyến hiện đúng hướng lối ra; lệch tuyến tự tìm đường lại; tới nơi 30 giây thì tự kết thúc.
- Tìm đường miễn phí: OSRM (mặc định) hoặc GraphHopper (có key, hết lượt tự quay về OSRM).
- Giới hạn tốc độ: OpenStreetMap + ước theo luật VN (TT 38/2024) hoặc GraphHopper; biển đỏ khi chạy quá.
- Chế độ mô phỏng để thử tại chỗ.

## Xe của tôi
- Thông số trực tiếp: vòng tua, nước làm mát, ắc quy, tốc độ, nhiệt độ ngoài trời, gió nạp, tay ga, tiêu hao, ODO.
- Popup cảnh báo trên màn hình xe: nước làm mát ≥ 105 °C, ắc quy không sạc / yếu (thử nghiệm).
- Tự kết nối khi bật xe, chạy nền khi khoá màn hình.

## Hành trình & xăng
- Tự ghi mỗi chuyến: quãng đường, thời gian, tốc độ TB/tối đa, xăng đã dùng, L/100 km, nhiệt độ.
- Xăng còn lại, quãng đường còn đi được, ngày nên đổ xăng; tự nhận biết khi đổ xăng (đồng hồ xăng tăng ≥ 2 vạch).

## Bảo dưỡng & địa điểm
- Nhắc thay nhớt, lọc gió, bugi, má phanh, dây curoa, nước mát, dầu phanh theo ODO/tháng; popup trên xe khi quá hạn.
- Nhà / Công ty / Yêu thích / Gần đây; nhớ chỗ đỗ xe khi tắt máy.

## Khác
- Tiếng Việt / English, đổi ngay trong Cài đặt.
- Tối ưu pin: GPS chính xác chỉ bật khi dẫn đường, giao diện cập nhật thưa, bản đồ phẳng.

## Cần kiểm tra khi chạy thử
- Bình đầy hiện mấy vạch xăng trên đồng hồ xe (app đang giả định 7 — chỉnh ở Xe của tôi → Xăng → ⋯).
- Popup cảnh báo và dòng chữ thông số ("Dòng chữ + bật màn hình dẫn đường") có hiện trên TFT không.
- Có lỗi: Bluetooth Lab → Xuất log, gửi cho dev.
