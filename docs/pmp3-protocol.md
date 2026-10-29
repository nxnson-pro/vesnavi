# Giao thức PMP3: điện thoại ↔ màn hình TFT Vespa (BLE)

> **Mục đích:** ghi chú **định dạng dữ liệu** để VesNavi tương thích (interoperability) với màn hình TFT dùng service BLE `0x1815`. Repo không chứa mã, APK hay tài nguyên nào của bên thứ ba.
>
> **Trạng thái:** **chưa kiểm chứng trên xe thật**. Kiểm chứng bằng Bluetooth Lab trong app (Cài đặt → Bluetooth Lab).

## Hai thế hệ kết nối

| Tên | Truyền dẫn | iOS dùng được? |
|---|---|---|
| PMP2 | Bluetooth Classic SPP (`00001101-…`, RFCOMM) | ❌ Không (iOS chặn SPP nếu không có chứng nhận MFi) |
| **PMP3** | **BLE GATT**, service `0x1815` | ✅ Có |

**Kết quả thử trên GTS Supertech 300 HPE của chủ repo (29/10/2026): KHÔNG có PMP3.** Xe (`BT-ROUTER`) chỉ có service BLE riêng `24839AC4-8D7E-515C-AA42-267340CF2339`:
- notify `1734594A-B8E7-5B1A-B6B1-DD5243059A57`
- write/writeNoResp `9B00ACE7-FB0B-59B0-CBE9-AAEE0A26E1A3`

Xe tự ngắt sau ~5 giây nếu không bắt tay. Kênh này (PMP2 qua BLE) được mô tả trong `pmp2-cobo-protocol.md`.

## GATT

| | UUID | Chiều |
|---|---|---|
| Service | `0x1815` (Automation IO) | |
| Write | `0x2A56` | điện thoại → xe, *write with response* |
| Notify | `0x2A5A` | xe → điện thoại (bật CCCD `0x2902`) |

Nên xin MTU 131. Gói dashboard dài tối đa 120 byte.

## Bắt tay

Mọi so khớp đều là **so tiền tố**: gói nhận được *bắt đầu bằng* mẫu.

1. Bật notify 2A5A → điện thoại gửi **8 byte ID thiết bị** (thô, không header). ID tạo một lần: `"0000" + hex(epoch_ms)` (nếu lẻ thì chèn `0` trước ký tự cuối), lưu lại theo từng xe.
2. Xe → `00 00 00 0E` : hỏi tên → trả `00 0E` + tên máy (ASCII, ≤ 20 byte).
3. Thiết bị **mới**: xe → `00 00 00 0F` → trả `00 01` + **khoá 8 byte ngẫu nhiên** (byte0 ≠ 0, byte1 ≠ 0x0F) rồi lưu lại. Màn hình xe có thể hỏi xác nhận.
4. Thiết bị **đã ghép**: xe → `00 00 c0 c1 c2 c3` (c0 ≠ 0, c1 ≠ 0x0F) → trả `00 01` + `auth(key, c0..c3)` (8 byte).
5. Xe → `00 01 …` = kết quả xác thực. Xe → `00 00 00 4F` = chấp nhận thiết bị.
6. Xe → `45 xx xx xx 01` = **dashboard sẵn sàng** (byte 4 = 1). Chỉ gửi dẫn đường sau bước này.
7. Trao đổi firmware (xe gửi trước):
   - `00 03` → trả `00 03 41 00 00…` (16 byte)
   - `40 41` → trả `44 34` + 14 byte 0
   - `44 34 v` → trả `44 38`
   - `44 38 a b` → trả `44 39`
   - `44 39 a b` = phiên bản hw
8. Sau khi sẵn sàng khoảng 3 giây: gửi `48 42` (heartbeat, một lần).

### `auth(key, c)`

Hai vòng kiểu XTEA. Dữ liệu = khoá, chia thành 2 word 32-bit big-endian. "Khoá XTEA" = 4 byte challenge.

```
v0,v1 = key[0..3], key[4..7] (BE); sum = 0
lặp 2 lần:
  n0 = v0 + ((c[sum&3] + sum) ^ (((v1<<4) ^ (v1>>5)) + v1))
  sum += 0x9E3779B9
  n1 = v1 + ((c[(sum>>11)&3] + sum) ^ (((n0<<4) ^ (n0>>5)) + n0))
  v0, v1 = n1, n0
out = BE32(v0 + c[2]) ‖ BE32(v1 + c[3])        (mọi phép tính mod 2^32)
```

Bộ test: `PMP3Tests.testAuthResponseVectors`.

## Gói dẫn đường (dashboard 0xC6)

Ghi = `55 01 05` + payload. Payload = `C6` + các tag:

| Tag | Byte | Ý nghĩa |
|---|---|---|
| `01` | `01 icon 00 00 lane FF FF FF 00` | Mũi tên. `lane`/24-bit lane mask: không dùng → `00` / `FF FF FF` |
| `03` | `03 mm mm D D D T T T 00` | phút tới đích (16-bit), khoảng cách tới đích (m, 24-bit), khoảng cách tới chỗ rẽ (m, 24-bit). Giá trị ≤ 0 → `FF FF FF`. Bỏ tag này khi phút = 0 |
| `04` | `04 [mode 00] text 00` | Tên đường, ASCII. Tiền tố `mode` tuỳ đời xe: không có, `FF`, `01` hoặc `02` |
| `02` | `02 n 00` | (tuỳ chọn) giới hạn tốc độ |
| `05`, `06` | | (tuỳ chọn) dùng cho hiển thị khác, chưa dùng |

### Mã icon (đổi từ maneuver của Google)

| Mã | Ý nghĩa | Mã | Ý nghĩa |
|---|---|---|---|
| 1 | đi thẳng | 9 | giữ trái / rẽ nhánh trái |
| 2 | quay đầu (theo chiều kim đồng hồ) | 10 | chếch trái |
| 3 | quay đầu (ngược chiều kim đồng hồ) — **VN** | 11 | rẽ trái |
| 4 | giữ phải / rẽ nhánh phải | 12 | rẽ gắt trái |
| 5 | chếch phải | 13 / 14 | lên đường dẫn phải / trái |
| 6 | rẽ phải | 15 / 16 | ra lối phải / trái |
| 7 | rẽ gắt phải | 200 | tiếp tục / xuất phát / đổi tên đường |
| 8 | nhập làn | 201 | **đến nơi** |
| 100–107 | vòng xuyến ngược chiều kim đồng hồ (VN), theo hướng ra: 100 quay đầu, 101 gắt phải, 102 phải, 103 chếch phải, 104 thẳng, 105 chếch trái, 106 trái, 107 gắt trái | 203 | đang tính lại đường / không có dẫn đường |
| 150–157 | vòng xuyến theo chiều kim đồng hồ (cùng thứ tự) | | |

VesNavi gửi khi có thay đổi và gửi lại mỗi giây.

## Xe → điện thoại (khác)

- `4B 4C|53 n` : nút trên xe (n = 2 bài tiếp, 3 bài trước, 7/8 tăng/giảm âm lượng)
- `00 20 01/02/03`, `00 02 …` : đăng ký và luồng dữ liệu ECU (RPM, nhiệt độ…), chưa dùng
- `4A 20/30/40 …` (điện thoại → xe, không header) : trạng thái sóng / nhạc / pin, chưa dùng
