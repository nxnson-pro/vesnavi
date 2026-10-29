# Hướng dẫn bắt log Bluetooth giữa iPhone và xe Vespa (không cần Mac)

**Mục tiêu:** ghi lại toàn bộ dữ liệu Bluetooth mà **app Vespa chính hãng** gửi tới màn hình TFT của xe, để giải mã định dạng tin nhắn → app Vespa Nav gửi được hướng rẽ lên xe (bước 2).

**Thời gian:** ~30–45 phút (phần lớn là chờ iPhone tạo file log).

---

## Chuẩn bị

| Cần có | Ghi chú |
|---|---|
| iPhone đã cài app **Vespa** chính hãng và từng kết nối được với xe | |
| Apple ID (miễn phí) | Để tải profile log từ Apple |
| Laptop Windows | Cài **7-Zip** và **Wireshark** (đều miễn phí) |
| Xe Vespa, chìa khoá, chỗ đỗ an toàn | Cần bật máy/bật điện để màn hình TFT hoạt động |
| Điện thoại thứ 2 hoặc người phụ | Để **quay video màn hình TFT** trong lúc bắt log (rất quan trọng) |

---

## Bước 1 — Cài profile ghi log Bluetooth lên iPhone

1. Trên **iPhone**, mở **Safari** → vào https://developer.apple.com/bug-reporting/profiles-and-logs/
2. Đăng nhập Apple ID (Apple sẽ hỏi đồng ý điều khoản developer — chấp nhận, **miễn phí**).
3. Tìm mục **Bluetooth** → chọn bản cho **iOS/iPadOS** → **Download Profile**. Safari báo "Trang web đang cố tải xuống hồ sơ cấu hình" → **Cho phép**.
4. Vào **Cài đặt** → dòng **"Đã tải về hồ sơ"** ở ngay đầu (hoặc **Cài đặt chung → VPN & Quản lý thiết bị**) → **Cài đặt** → nhập mật mã → **Cài đặt**.
5. **Khởi động lại iPhone** (tắt – mở lại) để profile bắt đầu ghi log.

> ⚠️ Profile tự hết hạn sau vài ngày. Nếu quá hạn thì làm lại bước 1.

---

## Bước 2 — Tái hiện kết nối và ghi chú thời gian

> 💡 **Quan trọng nhất:** mỗi thao tác hãy **ghi lại giờ:phút:giây** (ví dụ dùng app Ghi chú) và **quay video màn hình TFT**. Mình cần khớp "lúc 10:02:15 xe hiện mũi tên rẽ trái 200 m" với dữ liệu trong log.

1. Bật xe, để màn hình TFT sáng. Bật Bluetooth trên iPhone.
2. **(Nên làm — giúp bắt được bước bắt tay/ghép đôi)**: trong app Vespa và trong **Cài đặt → Bluetooth** trên iPhone, **xoá/quên** xe (thường tên `BT-ROUTER` hoặc tên xe), rồi **ghép đôi lại từ đầu** theo hướng dẫn của app. Ghi giờ lúc bắt đầu ghép đôi.
   - Nếu lo không ghép lại được thì bỏ qua bước này, chỉ cần **tắt/mở Bluetooth** trên iPhone để app kết nối lại từ đầu.
3. Chờ app báo đã kết nối → ghi giờ.
4. Làm lần lượt các việc sau, **mỗi việc cách nhau ~30 giây**, ghi giờ từng việc:
   - **Dẫn đường** (quan trọng nhất): nếu app cho dùng thử miễn phí/trial HERE, bật dẫn đường tới một điểm gần (vài trăm mét, có 2–3 chỗ rẽ). Đi hết tuyến (hoặc dắt xe/đi bộ cầm điện thoại nếu xe đứng yên được vẫn hiện hướng). Quay video màn hình TFT suốt đoạn này.
   - Nếu **không dùng được dẫn đường** (bắt trả phí): vẫn làm tiếp các mục dưới — log kết nối + thông báo vẫn rất có giá trị để hiểu cấu trúc tin nhắn.
   - Gọi điện tới iPhone từ máy khác (để xe hiện cuộc gọi đến) → từ chối.
   - Gửi 1 tin nhắn SMS/Zalo tới iPhone (nếu xe hiện thông báo).
   - Phát nhạc 10 giây (nếu xe hiện tên bài hát).
5. Tắt app Vespa / tắt xe → ghi giờ.

---

## Bước 3 — Xuất file log (sysdiagnose)

1. **Ngay sau khi làm xong bước 2**, trên iPhone: bấm **cùng lúc** nút **Tăng âm lượng + Giảm âm lượng + nút Nguồn (bên cạnh)**, giữ khoảng **1–1,5 giây** rồi thả. iPhone **rung nhẹ** là được.
   - Nếu màn hình tắt nguồn hiện lên → thả ra, bấm "Huỷ", thử lại nhanh hơn. Nếu chụp màn hình thì không sao, iPhone vẫn tạo log.
2. **Chờ ~10 phút** (iPhone đang gom log ở chế độ nền, cứ dùng bình thường).
3. Vào **Cài đặt → Quyền riêng tư & Bảo mật → Phân tích & Cải tiến → Dữ liệu phân tích**.
4. Tìm file tên bắt đầu bằng **`sysdiagnose_`** có ngày giờ hôm nay (kéo xuống danh sách theo thứ tự chữ cái). File khá nặng (~200–500 MB).
5. Bấm vào file → nút **Chia sẻ** (góc trên) → **Lưu vào Tệp** → chọn **iCloud Drive** (hoặc gửi qua Google Drive / cắm cáp copy sang laptop).

---

## Bước 4 — Lấy file Bluetooth trên laptop Windows

1. Tải file `sysdiagnose_….tar.gz` về laptop (từ icloud.com/iclouddrive hoặc Google Drive).
2. Chuột phải → **7-Zip → Extract Here** (giải nén 2 lần: `.gz` → `.tar` → thư mục).
3. Trong thư mục vừa giải nén, vào **`logs` → `Bluetooth`** (tên có thể khác chút tuỳ iOS). Tìm file đuôi **`.pklg`** (ví dụ `bluetoothd-hci-latest.pklg`).
   - Không thấy? Dùng ô tìm kiếm của File Explorer, gõ `*.pklg`.
4. **(Tuỳ chọn — để tự kiểm tra)** mở file `.pklg` bằng **Wireshark**, gõ vào ô filter: `btatt` → Enter. Nếu thấy các dòng **Write Request / Write Command / Handle Value Notification** vào khoảng giờ bạn ghi chú → log đã bắt thành công 🎉.

> 🔒 **Chỉ gửi file `.pklg`**, đừng gửi cả thư mục sysdiagnose (nó chứa nhiều thông tin cá nhân khác của iPhone).

---

## Bước 5 — Gửi cho mình

Upload lên repo (repo đang private, chỉ bạn thấy):

1. Vào trang GitHub của repo này (nhánh **`main`**).
2. **Add file → Upload files** → kéo vào:
   - file **`.pklg`**
   - file **ghi chú thời gian** (`.txt`, ví dụ dưới)
   - (nếu nhẹ) vài **ảnh chụp** màn hình TFT lúc hiện mũi tên — video nặng thì gửi link Google Drive trong file ghi chú.
3. Đặt vào thư mục `captures/` (gõ `captures/` trước tên file ở ô đường dẫn) → **Commit changes**.

**Mẫu file ghi chú (`captures/ghi-chu.txt`):**
```
iOS: 18.x   |   App Vespa: phiên bản ...   |   Xe: GTS Supertech 300 HPE (năm ...)
10:00:05  Quên thiết bị, bắt đầu ghép đôi lại
10:01:10  App báo đã kết nối
10:02:00  Bắt đầu dẫn đường tới ...
10:02:30  TFT hiện: mũi tên rẽ phải, 150 m, tên đường "..."
10:03:10  TFT hiện: đi thẳng 400 m
10:05:00  Gọi điện tới → từ chối
10:06:00  Tắt app
```

---

## Sau khi nhận log, mình sẽ làm gì

1. Tìm **service/characteristic Bluetooth** mà app Vespa dùng để nói chuyện với TFT.
2. Giải mã **bước bắt tay** (xem xe có yêu cầu xác thực/mã hoá riêng không).
3. Đối chiếu thời gian → tìm **tin nhắn hướng rẽ** (loại mũi tên, khoảng cách, tên đường).
4. Viết module Bluetooth trong Vespa Nav thay cho `LoggingDashboardSink` hiện tại.

## Rủi ro cần biết

- **Nếu xe dùng mã hoá riêng ở tầng app** (ngoài mã hoá Bluetooth chuẩn): log vẫn bắt được nhưng nội dung khó đọc → có thể không làm tiếp được bằng iPhone.
- **Nếu không bật được dẫn đường trong app chính hãng**: mình hiểu được cấu trúc chung nhưng phải **đoán** định dạng tin nhắn dẫn đường bằng cách thử gửi (sẽ làm 1 màn hình thử nghiệm trong app) — lâu hơn và không chắc thành công.
- Việc phân tích giao thức chỉ dùng cho xe của chính bạn; có thể không được Piaggio hỗ trợ bảo hành nếu phát sinh lỗi phần mềm trên xe.
