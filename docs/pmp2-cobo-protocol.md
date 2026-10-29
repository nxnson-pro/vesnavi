# PMP2 qua BLE ("Cobo"): GTS Supertech

> **Mục đích:** ghi chú định dạng dữ liệu để VesNavi tương thích (interoperability) với màn hình TFT trên xe của chính chủ repo. Repo không chứa mã, APK hay tài nguyên nào của bên thứ ba.
>
> **Trạng thái:** kênh BLE đã xác nhận trên xe thật (29/10/2026). Toàn bộ chuỗi bắt tay bên dưới lấy **từng byte** từ log HCI (`.pklg`) của iPhone chạy app chính hãng; `NavCore/Cobo.swift` + `CoboTests` tái hiện đúng byte. Chưa thử trên xe với app của ta.

## GATT (xe `BT-ROUTER` quảng bá)

| | UUID | Ghi chú |
|---|---|---|
| Service | `24839AC4-8D7E-515C-AA42-267340CF2339` | xe quảng bá; điện thoại cũng quảng bá khi làm GATT server |
| Notify | `1734594A-B8E7-5B1A-B6B1-DD5243059A57` | xe → điện thoại |
| Write | `9B00ACE7-FB0B-59B0-CBE9-AAEE0A26E1A3` | điện thoại → xe, write / writeNoResp |

Nếu không bắt tay, xe tự ngắt sau khoảng 5 giây.

## Khung (frame) Cobo

| Byte đầu | Ý nghĩa |
|---|---|
| `C0` | khung đơn (≤ 127 byte dữ liệu) |
| `C1` | khung đầu của chuỗi nhiều khung |
| `D0`–`DF` | khung giữa |
| `E0`–`EF` | khung cuối |
| `F0 n 00` | ACK: đã nhận n byte — **chỉ dùng trên Classic/SPP; app chính hãng KHÔNG gửi qua BLE** |
| `C6 …` | gói dashboard (giống PMP3, xem `pmp3-protocol.md`), gửi **thô**, không có khung `C0` |

Bên trong `C0` là gói lệnh DQ: `44 51 00 cmd len payload checksum`, với checksum = tổng các byte từ `44` đến hết payload, lấy mod 256. Payload > 127 byte: trường len thành 2 byte `len | 0x8000`, gói được cắt mỗi khung 127 byte (`C1`, `D0`, `D1`, …, `E(n)`). Khung dữ liệu CAN bắt đầu bằng `C0 80 …`.

## Xác thực

- **Classic (SPP), để tham khảo, chưa kiểm chứng:** xe gửi 16 byte `FF 00 00 00 00 00 c0 c1 c2 c3 …`, điện thoại trả `FF 00×5 k0 k1 k2 k3 00×5 cs`.
  - `k0 = c1 ^ 0x46`
  - `k1 = (c0 ^ c1) & 0x45`
  - `k2 = ~((c3 ^ c2) & 0x44)`
  - `k3 = c2 ^ 0x45` (MASK = "FEDE")
  - `cs` = tổng byte mod 256
  - Xe trả 16 byte, byte đầu `00` = OK.
- **BLE — đã giải mã từ log HCI của iPhone chạy app chính hãng (29/10/2026), khớp 4/4 phiên:**
  1. Xe notify `05 04 00 c0 c1 c2 c3` — thử thách (`c*` ngẫu nhiên mỗi lần).
  2. ~5 ms sau, điện thoại **ghi không phản hồi (write command)** 23 byte vào `9B00`: `k0 k1 k2 k3` (công thức "FEDE" như trên) + 19 byte **cố định** `03 6D 3B 33 14 92 85 BC 4A A4 4C C3 AA 75 20 6C C3 AA 75`.
  3. Xe notify 6 byte — **câu trả lời**, điện thoại giữ lại để gửi trên link dữ liệu.
  Không trả lời bước 2 → xe ngắt sau ~5 s (đúng như log test 29/10).

  | Thử thách | Điện thoại ghi (4 byte đầu) | Xe trả 6 byte |
  |---|---|---|
  | `89 06 13 66` | `40 05 BB 56` | `9D 1E A3 BE 50 25` |
  | `EC 6B B8 8B` | `2D 05 FF FD` | — |
  | `1E BA 92 F5` | `FC 04 BB D7` | `8A 1F A7 4E 68 1C` |
  | `DF 1C F8 2A` | `5A 41 BF BD` | `39 74 BC 07 A0 15` |

  Chưa rõ 19 byte đuôi là gì (giống nhau ở mọi phiên; có thể là mã định danh điện thoại/app). App gửi lại y nguyên.

## Kiến trúc 2 link (từ log HCI app chính hãng)

0. **Ghép đôi Bluetooth Classic trước**, theo hướng dẫn chính hãng (vespa.com/vn_EN/app/manual): bật màn hình xe → **Settings → Configuration → Pairing configuration**, rồi Cài đặt iOS → Bluetooth → **"BT ROUTER"**, mã PIN `0000` nếu được hỏi. Trong log HCI, iPhone ghép đôi Classic (theo địa chỉ Classic của xe, link HFP với lệnh AT) ngay trước link lấy khoá, và link Classic vẫn mở suốt phiên BLE. Hai link BLE bên dưới **không mã hoá** (không có SMP). Chưa rõ xe có bắt buộc link Classic mới kết nối ngược vào iPhone không — cứ làm giống app chính hãng.

1. **Link lấy khoá — iPhone = central.** iPhone kết nối xe, bật notify trên `1734`, nhận thử thách 7 byte, ghi trả lời 23 byte vào `9B00`, rồi nhận câu trả lời 6 byte. ~30 ms sau iPhone **tự ngắt** (reason 0x16) và bắt đầu **quảng bá** service `24839AC4` (khoảng 30 ms).
2. **Link dữ liệu — iPhone = GATT server, xe = central.** ~200 ms sau, xe kết nối vào iPhone, đọc GATT của iPhone (`9B00`: write + writeNoResp; `1734`: notify + indicate), ghi CCCD `02 00` (indication), rồi ghi `05 04 00` vào `9B00`. Từ đây mọi gói iPhone → xe đi bằng **Indication** trên `1734`; xe → iPhone bằng **Write** trên `9B00`.

## Chuỗi khởi tạo trên link dữ liệu

| Xe ghi (`9B00`) | iPhone indicate (`1734`) |
|---|---|
| `05 04 00` | câu trả lời 6 byte, rồi `0x70` + `00 01 01 03 02 00 00 01 01 01 04 02 00 FF` |
| `0x70` (`C0 44 51 00 70 0A …`) | `0x01` (rỗng) |
| `0x02` | `0x06` (rỗng) **rồi** `0x10` (rỗng) |
| `0x11` (`… 11 06 FF 02 00 FF 01 00 …`) | `0x23` + `01 00`, rồi `0x28` (407 byte, 4 khung `C1 D0 D1 E2`, cố định) → **sẵn sàng** |
| `C0 80 …` liên tục (dữ liệu CAN) | — (không ACK) |

Sau khi sẵn sàng:
- `0x27` = **yêu cầu đọc thông số** (không phải keep-alive): `01 04 09 00 00 FE 0B 55 55 55 55 55` sau ~2 s, lặp lại mỗi ~10 s cho tới khi xe trả `0x30` (`… FE 0B …`); ngay sau đó hỏi `… FF 06 …` theo cách tương tự. Trả lời đủ 2 thông số thì thôi không gửi `0x27` nữa.
- Xe → điện thoại trên link dữ liệu chỉ thấy: `05 04 00`, `0x70`, `0x02`, `0x11`, `0x30` và `C0 80 …` (CAN). Mọi write của xe đều là write request (cần phản hồi).
- Phiên thứ 2 trong log: xe gửi câu trả lời nhưng **không** kết nối ngược lại; ~10 s sau app chính hãng chạy lại link lấy khoá. Link dữ liệu rớt (supervision timeout) → app chính hãng chạy lại link lấy khoá ngay.
- Gói dash `C6 …` mỗi ~0,48 s. Không có lộ trình → `C6 01 00 … 04 "NO ADDRESS" 00`.
- **Khi đang dẫn đường, mỗi gói dash phải kèm tag `06 04 00`** ("đang dẫn đường"); kết thúc thì gửi `06 03 00`. Thiếu tag này TFT GTS Supertech vẫn ở màn hình thường, không hiện mũi tên (đã kiểm chứng trên xe 29/10). Thứ tự tag: `02 03 01 04 06`; gói "NO ADDRESS" lúc chưa dẫn đường không có tag 06.
- Tag 01 đầy đủ: `01 icon số_lối_ra_vòng_xuyến 00 icon_kế_tiếp khoảng_cách_kế_tiếp24 00` (không có → `00`, `FF FF FF`).
- Gói dash có đủ tag `01 icon 00 00 00 FF FF FF 00`, `02 00 00`, `03 phút16 m_đích24 m_rẽ24 00`, `04 TEXT 00` (chữ hoa ASCII).

## Lưu ý khi cài đặt trên iOS

- Phải có `CBPeripheralManager` + `UIBackgroundModes: bluetooth-peripheral`.
- Indication bị giới hạn luồng: `updateValue` trả `false` → chờ `peripheralManagerIsReady(toUpdateSubscribers:)`.
- Mọi write request phải `respond(to:withResult:)`.
- Gói `0x28` có khung 128 byte → cần `maximumUpdateValueLength ≥ 128`.
- Không tự kết nối lại link lấy khoá khi đang chờ / đang chạy link dữ liệu; link dữ liệu rớt → xoá câu trả lời cũ, làm lại từ link lấy khoá.

## Khởi tạo trên Classic/SPP (để tham khảo, chưa kiểm chứng)

1. Điện thoại gửi lệnh `0x70` + `00 01 01 03 02 00 00 01 01 01 04 02 00 FF`.
2. Xe trả `0x70` → điện thoại gửi `0x01` (rỗng).
3. Xe trả `0x02` → điện thoại gửi `0x06` (rỗng).
4. Xe trả `0x11` → điện thoại gửi `0x23` + `01 00 02 00 03 00` → **sẵn sàng**.

Trên SPP mỗi khung nhận được đều trả `F0 n 00`. Sau đó gửi gói dẫn đường `C6 …` khoảng mỗi giây.

## Dữ liệu xe (`C0 80 …`) — thông số động cơ

Sau gói `0x28`, xe gửi liên tục (~4 lần/giây) khung `C0 80` + 61 byte: **mỗi tín hiệu trong danh sách `0x28` một byte theo đúng thứ tự** (tín hiệu 16 bit chiếm 2 byte, big-endian), cuối cùng là 4 đầu vào analog × 2 byte (phần đuôi `01 03 09 … 01 03 06`). Mục `0x28`: `00 00 00 00 idHi idLo start len`.

| CAN id / start | Ý nghĩa | Công thức | Kiểm chứng 29/10 |
|---|---|---|---|
| 0x590 / 48 | Điện áp ắc quy | ×0,1 V | 11,8 V (máy tắt) |
| 0x590 / 40 | Nhiệt độ ngoài trời | −20 °C | 32 °C = TFT ✅ |
| 0x589 / 48, 40 | Giờ, phút đồng hồ TFT | | 17:57 |
| 0x589 / 16, 8, 0 | ODO byte thấp, giữa, cao | km | 31171 = TFT ✅ |
| 0x180 / 16 | Nước làm mát | −40 °C | 34 °C |
| 0x180 / 24 | Gió nạp | −40 °C | 37 °C |
| 0x120 / 16 (16 bit) | Vòng tua | vòng/phút | ✅ garanti ~1700, chạy 4000–4800 |
| 0x120 / 24 | Tay ga | % | 0 |
| 0x260 / 24, 8 (16 bit) | Tốc độ bánh sau, trước | ×0,01 km/h | ✅ 47,2 km/h khi đồng hồ báo 52 |
| 0x220 / 0 | Tốc độ trên đồng hồ TFT | km/h | ✅ 52 (cao hơn bánh xe ~10 %) |
| 0x130 / 24 | Tiêu hao nhiên liệu | ×0,05 L/h | 0 |
| 0x130 / 61 | Chế độ ASR | | 1 |
| 0x100 / 42 | Đèn MIL | | |
| 0x700 | 6 byte địa chỉ Classic của BT-ROUTER + `01 01` | | |

Các dòng có ✅ đã đối chiếu với đồng hồ xe; hệ số còn lại chưa kiểm chứng. Bộ giải mã: `NavCore/ScooterTelemetry.swift`, test `TelemetryTests`.

## Popup và dòng chữ (thử nghiệm)

- **Popup**: `C2 01 nền dòng1 3B dòng2 00`, chữ ASCII; nền = `13` với GTS 300 Supertech. Gửi **thay cho** gói dash trong 9 gói liên tiếp (~4,5 s) (`Cobo.popupPayload`, `VespaBLE.showPopup`).
- **Dòng chữ khi không dẫn đường**: dùng tag 04 của gói dash rảnh (thay "NO ADDRESS"). TFT chỉ hiện dòng này khi màn hình dẫn đường đang bật, nên gói luôn kèm `06 04 00` (đã kiểm chứng 29/10).
