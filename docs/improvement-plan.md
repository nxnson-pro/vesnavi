# VesNavi — kế hoạch cải tiến (review ngày 2026-11-12)

Tài liệu này dành cho phiên thực thi (Opus 5.5). Đọc `CLAUDE.md` của repo trước; mọi quy ước ở đó vẫn áp dụng.

## 0. Bối cảnh và ràng buộc

- **Trạng thái code được review:** nhánh `claude/sleepy-bell-cfe7zy`, commit `5d08e24` (bản 0.1.5: chỉnh giờ xe + chạy từ Xcode). CI trên nhánh xanh cả 4 job, **chưa merge, chưa phát hành, chưa thử trên xe**. Main đang ở 0.1.4 (`2f0a0dc`).
- **Phạm vi đã đọc kỹ:** `App/AppModel.swift`, `LocationService`, `VespaBLE` (phần lớn), `TripStore`, `MaintenanceStore`, `PlacesStore`, `SpeedLimitService`, `ContentView`, `NavigationHUD`, `SearchService`; NavCore: `NavigationEngine`, `Geo`, `DashboardThrottle`, `RouteSimulator`, `OSRMProvider`, `GraphHopperProvider`, `Trips`, `ScooterTelemetry`, `Cobo`. **Chỉ lướt:** các view còn lại, toàn bộ app Android (chỉ grep để tìm chỗ lệch với iOS), `PMP3*`.
- **Không kiểm chứng được từ sandbox:** không có Xcode (app iOS chỉ biên dịch trên CI), không có Android SDK, mạng chặn `overpass-api.de`, `router.project-osrm.org`, `maps.vietmap.vn`, `docs.goong.io`. Các mục gọi API bên ngoài bên dưới dựa trên hiểu biết về API, **phải kiểm chứng bằng yêu cầu thật** trước khi coi là xong.
- **Quy tắc bắt buộc khi thực thi:**
  - Logic/giao thức đổi ở Swift NavCore thì đổi cả Kotlin `android/navcore`, cùng test vector.
  - Mọi chuỗi hiển thị viết `L("Tiếng Việt", "English")`.
  - Không commit tên/định danh lấy từ app bên thứ ba, không commit log/địa chỉ/vị trí thật.
  - Không force-push (bị chặn). Nhánh hiện còn commit `9936605` có message nhắc tên hàm của app chính hãng → **mọi PR từ nhánh này phải squash merge**.
  - Mỗi giai đoạn = 1 PR + 1 lần tăng `MARKETING_VERSION` + CHANGELOG (Anh trước, Việt sau). Chỉ tạo PR/merge khi chủ repo đồng ý.
  - Chạy `cd android && ./gradlew :navcore:test -Pvesnavi.coreOnly=true --offline` trên máy; Swift test và build app chờ CI.

## 1. Việc cần chủ repo làm trước (chặn một số mục)

| # | Việc | Chặn mục |
|---|---|---|
| U1 | Quyết định 0.1.5: merge + phát hành, hay thử trên Mac trước | Giai đoạn A trở đi (nên bắt đầu từ main mới) |
| U2 | Thêm vào Allowed domains của môi trường: `router.project-osrm.org`, `overpass-api.de`, `valhalla1.openstreetmap.de` (và `maps.vietmap.vn` nếu muốn Vietmap) | B1, B2, B3, B5 |
| U3 | Gửi log một chuyến đi bằng 0.1.4+ (có dòng "nguồn giới hạn tốc độ" và "Không tra được giới hạn tốc độ: …") | B3 (biết chính xác vì sao biển tốc độ trống) |
| U4 | Thử nút "🕒 Chỉnh giờ xe" trong Bluetooth Lab, gửi log | D4 |

## 2. Phát hiện chính (xếp theo mức ảnh hưởng)

### Nghiêm trọng — sai kết quả hoặc an toàn
1. **Tìm đường có thể đưa xe máy lên cao tốc.** `OSRMProvider.url` gọi profile `driving` không có `exclude=motorway`; GraphHopper mặc định profile `car`. Ở Việt Nam xe máy bị cấm trên cao tốc. `SpeedLimit.swift` đã biết điều này (`vn:motorway` → nil) nhưng phần tìm đường thì không.
2. **Biển tốc độ không hiện trong chuyến đi thật** (log 01/11: mọi gói dash có tag 02 = 0). Thiết kế hiện tại gọi Overpass mỗi 250 m (khoảng 120 lượt cho 30 km), dễ bị giới hạn tần suất; nguyên nhân thật chờ U3.
3. **Yêu cầu tìm đường không có timeout riêng.** `AppModel.fetchRoute` dùng `URLSession.shared.data(from:)` (mặc định 60 s) và không kiểm tra mã HTTP. Khi tìm lại đường lúc mạng yếu, app treo ở trạng thái "đang tìm" tới 1 phút. Android đã có timeout trong `Network.kt`.

### Cao — độ tin cậy
4. **Bắt điểm lên tuyến không dùng hướng đi.** `NavigationEngine.update` chọn đoạn gần nhất trong cửa sổ từ `segment-2` tới 1000 m phía trước. Tuyến đi-rồi-quay-lại cùng một đường (quay đầu, vòng qua dải phân cách) có thể nhảy sang lượt về và bỏ qua các chỉ dẫn ở giữa.
5. **Chuyến đang ghi mất khi app bị iOS đóng.** `TripRecorder.current` chỉ nằm trong RAM; chỉ lưu khi chuyến kết thúc.
6. **Lỗi ghi file bị nuốt.** `JSONFile.save`, `TripStore.save` dùng `try?` không log.
7. **Android lệch iOS:** không có `resetStalledDataLink` (đường BLE), không có log tra tốc độ / `note()`, không có nút chỉnh giờ trong Lab.

### Trung bình — hiệu năng, pin
8. **Dòng dash 0,5 s chạy cả khi không dẫn đường và app ở nền**, cộng 4 khung CAN/giây đánh thức app. Chưa biết xe có chịu tần suất thấp hơn không (cần thử trên xe).
9. **GPS khi dẫn đường dùng `kCLLocationAccuracyBestForNavigation`** (mức tốn pin nhất, Apple khuyên dùng khi cắm sạc).
10. **Log tăng nhanh khi dẫn đường:** mỗi giây một dòng "TX⇢ dash" (khoảng cách đổi liên tục) → đầy 2500 dòng sau ~40 phút, đẩy mất phần bắt tay đầu phiên, là phần cần nhất khi báo lỗi.
11. `NavigationEngine.update` duyệt tuyến tính `route.steps` 3 lần và `speedLimits` mỗi lần có GPS. Nhỏ với tuyến thường, đáng sửa khi làm mục 4.

### Trung bình — bảo trì
12. **`App/VespaBLE.swift` 1221 dòng** (Android 1432), vượt trần 800 dòng trong quy ước; gộp central, GATT server, PMP3, Cobo, cảnh báo, log, demo.
13. **Khoá lưu trong UserDefaults:** key GraphHopper, key ghép đôi PMP3. Nên chuyển Keychain (Android: EncryptedSharedPreferences).
14. **CI:** không lint (SwiftLint/detekt); `paths` của `ci.yml` thiếu `Config/**`, `scripts/**`, `UITests/**`; không có test nào cho tầng App.

### Tính năng còn thiếu (đề xuất)
15. Dẫn đường bằng giọng nói qua tai nghe/intercom (xe đã nối HFP).
16. Xuất/nhập dữ liệu (hành trình, địa điểm, bảo dưỡng): gỡ app để chuyển sang Xcode là mất hết.
17. Đèn báo lỗi động cơ (MIL, `0x100/42`) đã giải mã nhưng không dùng; cảnh báo chỉ hiện trên TFT, không có thông báo trên điện thoại.
18. Xem trước tuyến: danh sách chỉ dẫn, tuyến thay thế, tuỳ chọn tránh phí.
19. Nguồn dữ liệu Việt Nam tuỳ chọn (Vietmap/Goong, người dùng tự nhập key).

## 3. Kế hoạch theo giai đoạn

Thứ tự đề xuất: **A → B → C → D → E**. Mỗi mục ghi: file, việc làm, tiêu chí xong (TC), rủi ro.

### Giai đoạn A — Nền tảng an toàn (bản 0.1.6, không cần xe, không cần mạng)

**A1. Timeout và mã HTTP cho mọi yêu cầu mạng (iOS)**
- File: tạo `App/Network.swift`; sửa `App/AppModel.swift` (`fetchRoute`), `App/SpeedLimitService.swift`.
- Việc: một `URLSession` riêng (`timeoutIntervalForRequest = 12`, `waitsForConnectivity = false`), hàm `fetch(url) async throws -> Data` ném lỗi khi mã ≠ 2xx, gắn User-Agent `VesNavi/<version>`. Thêm `RoutingError.network(String)` (Swift + Kotlin) để báo "mạng chậm/không có mạng" thay cho thông báo hệ thống.
- TC: không còn `URLSession.shared` trong `App/`; test `RoutingError.message` cho case mới ở cả hai ngôn ngữ.

**A2. Không nuốt lỗi ghi file**
- File: `App/JSONFile.swift`, `App/TripStore.swift` (dùng chung `JSONFile`), Android `Stores.kt` / `JsonFile`.
- Việc: `save` trả `Bool` hoặc ném lỗi; nơi gọi ghi một dòng vào log Bluetooth qua `vespa.note(...)` (không lộ đường dẫn). `load` thất bại vì JSON hỏng → đổi tên file thành `*.corrupt-<ngày>` thay vì ghi đè im lặng.
- TC: test đơn vị cho hàm đổi tên file hỏng (đặt logic thuần vào NavCore nếu cần để test được trên Linux).

**A3. Lưu tạm chuyến đang ghi**
- File: `NavCore/Trips.swift` (+ Kotlin `Trips.kt`), `App/TripStore.swift`, Android `Stores.kt`.
- Việc: `TripRecorder` thêm `snapshot()` / `restore(_:)` (Codable: `current`, `lastEngineOn`, các tổng trung bình). `TripStore` ghi `trip-in-progress.json` tối đa 60 s một lần; khi khởi động, nếu có file và `lastEngineOn` cũ hơn `engineOffTimeout` thì chốt thành chuyến, còn mới thì khôi phục để ghi tiếp; xoá file khi chuyến kết thúc.
- TC: test Swift + Kotlin: khôi phục rồi ghi tiếp cho cùng tổng quãng đường; snapshot cũ được chốt đúng `end`.

**A4. Giữ phần đầu log**
- File: `App/VespaBLE.swift` (`append`, `pumpIndications`), Android tương ứng.
- Việc: (a) dash khi dẫn đường chỉ ghi log khi **icon hoặc tên đường** đổi, không ghi khi chỉ đổi khoảng cách; (b) khi cắt log, giữ nguyên các dòng từ đầu phiên kết nối gần nhất tới lúc "Sẵn sàng" (đánh dấu `pinned`), chỉ cắt phần giữa.
- TC: hàm quyết định "dash có đáng ghi log" là hàm thuần trong NavCore, có test.

**A5. Đưa Android ngang iOS**
- File: `android/app/.../VespaBLE.kt`, `SpeedLimitService.kt`, `AppModel.kt`, `ui/BluetoothLabScreen.kt`.
- Việc: thêm `note()`, log nguồn/lỗi tra tốc độ như iOS 0.1.4; nút "🕒 Chỉnh giờ xe" trong Lab (`syncClockNow`); với đường BLE dự phòng thêm cơ chế đóng link dữ liệu kẹt bằng `BluetoothGattServer.cancelConnection(device)` (Android ngắt được từ phía server, không cần mẹo mở link lấy khoá như iOS).
- TC: CI `build-android` xanh; ghi chú trong CLAUDE.md mục Android.

**A6. CI**
- File: `.github/workflows/ci.yml`.
- Việc: thêm `Config/**`, `scripts/**`, `UITests/**` vào `paths`. Thêm job lint **không chặn** (`continue-on-error: true`): SwiftLint (cấu hình tối thiểu: `file_length` warning 800, `function_body_length` 60, `force_unwrapping`) và detekt cho Kotlin. Chuyển thành chặn sau giai đoạn C.
- TC: job chạy và in cảnh báo; không làm đỏ CI.

### Giai đoạn B — Dẫn đường đúng cho xe máy (bản 0.2.0, cần U2)

**B1. Tránh cao tốc với OSRM (sửa ngay, nhỏ)**
- File: `NavCore/OSRMProvider.swift`, Kotlin `Routing.kt`, `AppModel.fetchRoute` (cả hai nền tảng).
- Việc: thêm `exclude=motorway` vào URL. Nếu máy chủ trả lỗi `InvalidValue`/`InvalidQuery` về exclude thì gọi lại không có tham số này và đặt `routeNote` "tuyến có thể đi qua cao tốc". Cài đặt mới "Tránh cao tốc" (mặc định bật).
- Kiểm chứng (cần U2): gọi thật một tuyến TP.HCM → Long Thành, so tuyến có/không có exclude.
- TC: test URL; test phân tích phản hồi lỗi exclude → thử lại.

**B2. Thêm nguồn tìm đường Valhalla với profile xe máy (miễn phí, không key)**
- Lý do: máy chủ công cộng FOSSGIS (`valhalla1.openstreetmap.de`) có costing `motorcycle` / `motor_scooter`, tôn trọng thẻ cấm xe máy và đường chỉ dành cho xe máy; OSRM công cộng chỉ có profile ô tô.
- File mới: `NavCore/ValhallaProvider.swift` (+ Kotlin), giải mã polyline6, ánh xạ `maneuver.type` → `Maneuver` (kể cả `roundabout_exit_count`), `begin_shape_index` → `pointIndex`. Sửa `fetchRoute`: thứ tự GraphHopper (nếu có key) → Valhalla → OSRM, kèm `routeNote` khi phải lùi nguồn.
- Giả định cần kiểm chứng bằng yêu cầu thật: máy chủ còn mở, giới hạn dùng hợp lý, định dạng phản hồi đúng như fixture. Nếu không kiểm chứng được thì **không** đặt làm mặc định, để sau OSRM.
- TC: test phân tích với fixture JSON (tạo từ phản hồi thật, toạ độ giả không phải vị trí của chủ xe); test giải mã polyline6; cùng vector ở Kotlin.

**B3. Biển tốc độ: một lần tra cho cả tuyến**
- Chờ U3 để biết nguyên nhân hiện tại; thiết kế mới giải quyết cả trường hợp bị giới hạn tần suất.
- File: `NavCore/SpeedLimit.swift`, `App/SpeedLimitService.swift` (+ Kotlin).
- Việc: khi có tuyến mới, tra trước toàn tuyến rồi ghi vào `Route.speedLimits` (cấu trúc sẵn có, engine đã đọc theo `segmentIndex`):
  - Cách 1 (ưu tiên nếu B2 dùng được): Valhalla `trace_attributes` với shape của tuyến → `edge.speed_limit`, `road_class`, `lane_count` cho từng đoạn; thiếu giới hạn thì áp luật mặc định TT 38/2024 đã có.
  - Cách 2 (dự phòng): một truy vấn Overpass `way(around:25, <polyline đã giản lược>)[highway]; out tags geom;` rồi khớp way ↔ đoạn tuyến tại chỗ (điểm giữa đoạn gần way nào nhất, ưu tiên trùng tên đường).
  - Giữ cách tra theo điểm hiện tại làm dự phòng cuối khi lệch tuyến hoặc tra trước thất bại; tăng khoảng lùi khi gặp HTTP 429.
- TC: test hàm khớp way ↔ đoạn với fixture; test giản lược polyline; trong log có một dòng "tra tốc độ cho cả tuyến: N đoạn có giới hạn".

**B4. Bắt điểm lên tuyến có xét hướng và quãng đường hợp lý**
- File: `NavCore/NavigationEngine.swift` (+ Kotlin `Navigation.kt`), `AppModel.handle` (truyền `course`, `speed`, `timestamp` từ `CLLocation`).
- Việc: `update(location:accuracy:course:speed:time:)`. Trong cửa sổ tìm cục bộ, chấm điểm ứng viên = khoảng cách + phạt lệch hướng (khi `course` hợp lệ và tốc độ > 2 m/s) + phạt nhảy xa hơn `speed × Δt × 2 + 50 m`. Thay 3 lần duyệt `steps` bằng con trỏ `nextStepIndex` tiến dần; `speedLimits` tìm nhị phân. Giữ nguyên chữ ký cũ (tham số mới có giá trị mặc định) để mô phỏng không đổi.
- TC: test mới: tuyến đi-rồi-quay-lại (2 lượt cách nhau 8 m) không nhảy lượt; tuyến cắt chính nó; toàn bộ test cũ vẫn qua.

**B5. (Tuỳ chọn, cần U2 + chủ repo có key) Vietmap làm nguồn tìm đường và tốc độ**
- Chỉ làm khi đọc được tài liệu Route API. Người dùng tự nhập key trong Cài đặt, không nhúng key vào repo. Thêm vào chuỗi lùi nguồn của B2.

### Giai đoạn C — Tái cấu trúc để bảo trì (bản 0.2.1, không đổi hành vi)

**C1. Tách `VespaBLE.swift`** thành các file extension, không đổi logic: `VespaBLE.swift` (trạng thái, API công khai), `VespaBLE+Central.swift`, `VespaBLE+CoboServer.swift`, `VespaBLE+PMP3.swift`, `VespaBLE+Telemetry.swift` (publish, cảnh báo, chỉnh giờ), `VespaBLE+Log.swift`, `VespaBLE+Demo.swift`. Thuộc tính `private` cần dùng chéo file đổi thành `fileprivate`→`internal` có chú thích.
**C2. Đưa máy trạng thái thử lại ra NavCore** để test được: lùi thời gian link lấy khoá (2/4/8/10 s), chính sách khi khởi tạo kẹt (gửi lại ×2 → reset 1 lần → chỉ dash). Hiện nằm rải trong `VespaBLE` và không có test.
**C3. Tách tương tự cho `VespaBLE.kt`** (BLE central, GATT server, SPP, telemetry).
**C4. Bật lint thành chặn** sau khi dọn cảnh báo.
- TC chung: diff chỉ di chuyển code + test mới cho C2; CI xanh; sau đó **bắt buộc thử kết nối trên xe** trước khi phát hành (rủi ro hồi quy cao nhất của cả kế hoạch).

### Giai đoạn D — Pin và hiệu năng (bản 0.2.2, cần đo trên xe)

**D1. Giảm tần suất dash khi rảnh.** Thử nghiệm có công tắc trong Bluetooth Lab: khi không dẫn đường, không popup, gửi dash mỗi 2 s thay vì 0,5 s. Chỉ đặt mặc định nếu xe giữ link ≥ 30 phút và dòng chữ rảnh vẫn hiện. Giữ 0,5 s khi dẫn đường.
**D2. GPS:** đổi sang `kCLLocationAccuracyBest`; thêm `distanceFilter = 5` khi tốc độ xe (từ telemetry) = 0 quá 20 s, trả về `None` khi xe chạy. Android: `minDistance` tương ứng.
**D3. Đo trước/sau:** hướng dẫn chủ repo đo bằng Xcode Energy Gauge / Instruments (30 phút dẫn đường màn hình tắt, 30 phút kết nối rảnh ở nền) và ghi số vào `docs/`. Không tuyên bố "tiết kiệm pin" khi chưa có số đo.
**D4. Chốt tính năng chỉnh giờ** theo kết quả U4: nếu xe nhận → cập nhật CLAUDE.md thành ✅; nếu không → giữ nút trong Lab, ẩn công tắc khỏi Cài đặt.

### Giai đoạn E — Tính năng mới (mỗi mục một bản phát hành riêng, làm theo thứ tự chủ repo chọn)

**E1. Giọng nói dẫn đường.** `AVSpeechSynthesizer` (vi-VN / en-US theo ngôn ngữ app), thêm `audio` vào `UIBackgroundModes` trong `project.yml`, `AVAudioSession` category `.playback` với `.duckOthers` + `.interruptSpokenAudioAndMixWithOthers`. Logic chọn thời điểm đọc đặt trong NavCore (`VoicePrompter`: đọc ở ~500 m, ~150 m và ngay tại chỗ rẽ, theo tốc độ; không đọc lặp) để test được; Kotlin dùng `TextToSpeech`. Cài đặt: bật/tắt, mặc định tắt. Rủi ro: định tuyến âm thanh qua HFP của BT ROUTER cần thử trên xe.
**E2. Xuất/nhập dữ liệu.** Một file `vesnavi-backup.json` (hành trình, nhiên liệu, địa điểm, bảo dưỡng, có `schemaVersion`) qua `ShareLink` / `fileImporter`; Android dùng Storage Access Framework. Cùng định dạng hai nền tảng, test khứ hồi trong NavCore.
**E3. Cảnh báo trên điện thoại + đèn MIL.** Thông báo cục bộ (`UNUserNotificationCenter`) cho nước mát nóng, không sạc, ắc quy yếu và MIL bật (`0x100/42`, cần xác nhận giá trị trên xe trước khi bật mặc định); dùng lại cơ chế chống lặp 5 phút.
**E4. Xem trước tuyến.** Danh sách chỉ dẫn trong thẻ xem trước; tuyến thay thế (`alternatives=true` với OSRM, `alternates` với Valhalla) chọn bằng chạm trên bản đồ; tô xám phần tuyến đã đi.
**E5. Keychain cho khoá** (GraphHopper/Vietmap, key ghép đôi PMP3), tự chuyển dữ liệu cũ từ UserDefaults; Android `EncryptedSharedPreferences`.

## 4. Ước lượng và rủi ro

| Giai đoạn | Khối lượng | Rủi ro chính | Cần thử trên xe |
|---|---|---|---|
| A | Nhỏ–vừa (6 mục độc lập) | Thấp | Không |
| B | Lớn | Máy chủ công cộng (Valhalla/Overpass) không ổn định hoặc đổi định dạng; B4 đổi hành vi bám tuyến | Chạy thử một tuyến thật |
| C | Vừa | Hồi quy kết nối Bluetooth | **Bắt buộc** |
| D | Nhỏ | Xe ngắt link khi giảm tần suất | Bắt buộc |
| E | Mỗi mục vừa | E1: định tuyến âm thanh; E3: nghĩa của byte MIL | E1, E3 |

## 5. Điều chưa biết (không đoán khi thực thi)

- Vì sao biển tốc độ trống ở chuyến 01/11 (chờ U3).
- Xe có nhận lệnh chỉnh giờ không (chờ U4).
- Vì sao xe kẹt khởi tạo ở lần kết nối đầu sau khi bật máy (đã có cơ chế vượt qua, chưa rõ gốc).
- Máy chủ OSRM công cộng có chấp nhận `exclude=motorway` không; máy chủ Valhalla FOSSGIS có mở `trace_attributes` không.
- Byte đèn MIL và vạch xăng (`0x589/53`) vẫn là giả định.
