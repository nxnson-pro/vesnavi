import CoreBluetooth
import Observation
import UIKit
import NavCore

/// CoreBluetooth link to the Vespa TFT + a debug log the rider can export.
/// - PMP3 scooters (service 0x1815): one link, phone = central.
/// - PMP2 scooters ("Cobo", e.g. GTS Supertech): two links, exactly like the official app —
///   a short **key link** (phone = central) that collects the auth answer and is then closed by us,
///   and a **data link** where the scooter connects to the phone's GATT server and receives indications.
/// Also the app's DashboardSink: navigation states become 0xC6 dash packets.
@MainActor
@Observable
final class VespaBLE: NSObject, DashboardSink {
    enum LinkState {
        case off, unauthorized, idle, scanning, connecting, discovering, handshaking, waitingForScooter, ready,
             unknownService

        var title: String {
            switch self {
            case .off: return L("Bluetooth tắt", "Bluetooth off")
            case .unauthorized: return L("Chưa cấp quyền Bluetooth", "Bluetooth permission needed")
            case .idle: return L("Chưa kết nối", "Not connected")
            case .scanning: return L("Đang quét…", "Scanning…")
            case .connecting: return L("Đang kết nối…", "Connecting…")
            case .discovering: return L("Đang đọc dịch vụ…", "Reading services…")
            case .handshaking: return L("Đang bắt tay…", "Handshaking…")
            case .waitingForScooter: return L("Chờ xe kết nối vào iPhone…", "Waiting for the scooter…")
            case .ready: return L("✅ Sẵn sàng", "✅ Ready")
            case .unknownService: return L("⚠️ Không nhận ra dịch vụ Bluetooth của xe", "⚠️ Unknown scooter Bluetooth service")
            }
        }
    }

    enum LinkProtocol: String {
        case pmp3 = "PMP3 (0x1815)"
        case cobo = "PMP2 over BLE (Cobo)"
    }

    struct Discovered: Identifiable {
        let id: UUID
        var name: String
        var rssi: Int
        var services: [String]
        var isLikelyVespa: Bool
    }

    struct LogLine: Identifiable {
        let id = UUID()
        let time: Date
        let text: String
    }

    let name = "Vespa TFT (Bluetooth)"
    private(set) var state: LinkState = .idle
    private(set) var discovered: [Discovered] = []
    private(set) var log: [LogLine] = []
    private(set) var connectedName: String?
    private(set) var gattTable: [String] = []
    private(set) var firmwareInfo: String?
    private(set) var lastMessage: String?
    private(set) var pairing: PMP3Pairing?
    private(set) var linkProtocol: LinkProtocol?
    /// Live engine data (PMP2/Cobo scooters only); nil until the first data frame.
    private(set) var telemetry: ScooterTelemetry?
    private(set) var telemetryAt: Date?
    /// Highest values since the data link came up.
    private(set) var peakRPM = 0
    private(set) var peakCoolantC: Int?
    var dashOptions = PMP3.DashOptions()
    private var autoReconnectValue: Bool
    var autoReconnect: Bool {
        get { autoReconnectValue }
        set {
            autoReconnectValue = newValue
            UserDefaults.standard.set(newValue, forKey: Keys.autoReconnect)
        }
    }

    /// What the TFT text line shows while no route is active. The text only appears with the TFT's
    /// navigation screen on (`06 04 00`), seen on the scooter 2026-10-29, so `.text` always turns it on.
    enum IdleDisplay: String, CaseIterable, Identifiable {
        case off
        case text = "textOnNavScreen"
        var id: String { rawValue }
        var title: String {
            switch self {
            case .off: return L("Tắt (\"NO ADDRESS\" như app gốc)", "Off (\"NO ADDRESS\" like the official app)")
            case .text: return L("Nhiệt độ nước mát + ắc quy", "Coolant + battery")
            }
        }
    }

    private var idleDisplayValue: IdleDisplay
    var idleDisplay: IdleDisplay {
        get { idleDisplayValue }
        set {
            idleDisplayValue = newValue
            UserDefaults.standard.set(newValue.rawValue, forKey: Keys.idleDisplay)
        }
    }
    /// Pop-up warnings on the TFT (coolant, battery).
    private var warningsEnabledValue: Bool
    var warningsEnabled: Bool {
        get { warningsEnabledValue }
        set {
            warningsEnabledValue = newValue
            UserDefaults.standard.set(newValue, forKey: Keys.warnings)
        }
    }

    var isReady: Bool { state == .ready }
    var isConnected: Bool { [.discovering, .handshaking, .waitingForScooter, .ready, .unknownService].contains(state) }

    // Central side (PMP3 link, or the Cobo key link).
    @ObservationIgnored private var central: CBCentralManager?
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var writeChar: CBCharacteristic?
    @ObservationIgnored private var session: PMP3Session?
    @ObservationIgnored private var keyFetch: CoboKeyFetch?
    @ObservationIgnored private var queue: [[UInt8]] = []
    @ObservationIgnored private var writeInFlight = false
    @ObservationIgnored private var pmp3KeepAlive: Task<Void, Never>?

    // Server side (Cobo data link).
    @ObservationIgnored private var peripheralManager: CBPeripheralManager?
    @ObservationIgnored private var coboIndicateChar: CBMutableCharacteristic?
    @ObservationIgnored private var coboServiceAdded = false
    @ObservationIgnored private var coboAnswer: [UInt8]?
    @ObservationIgnored private var server: CoboServerSession?
    @ObservationIgnored private var scooterCentral: CBCentral?
    @ObservationIgnored private var scooterSubscribed = false
    @ObservationIgnored private var awaitingScooter = false
    @ObservationIgnored private var indications: [[UInt8]] = []
    @ObservationIgnored private var serverWatchdog: Task<Void, Never>?
    @ObservationIgnored private var coboStream: Task<Void, Never>?
    @ObservationIgnored private var lastLoggedDash: [UInt8]?
    @ObservationIgnored private var lastCanLog = Date.distantPast

    @ObservationIgnored private var lastDash: [UInt8]?
    @ObservationIgnored private var stopDashPending = false
    @ObservationIgnored private var userDisconnected = false
    @ObservationIgnored private var autoConnectOnDiscovery = false
    /// Every decoded frame (the published `telemetry` is throttled for the UI).
    @ObservationIgnored private(set) var latestTelemetry: ScooterTelemetry?
    @ObservationIgnored private var lastTelemetryPublish = Date.distantPast
    /// Set by the app from the scene phase; slows UI publishing in the background.
    @ObservationIgnored var isForeground = true
    /// README recordings: fake scooter, CoreBluetooth never touched (see `startDemo`).
    @ObservationIgnored private var isDemo = false
    /// Every decoded data frame (trip recording).
    @ObservationIgnored var onTelemetry: ((ScooterTelemetry) -> Void)?
    /// The Cobo data link went away (ignition off, out of range, Bluetooth off).
    @ObservationIgnored var onDataLinkEnded: (() -> Void)?
    @ObservationIgnored private var popup: [UInt8]?
    @ObservationIgnored private var popupTicksLeft = 0
    @ObservationIgnored private var lastWarning: [Warning: Date] = [:]
    @ObservationIgnored private var lowChargeSince: Date?
    /// Keys and auth answers seen this session, removed from logs posted publicly.
    @ObservationIgnored private var logSecrets: Set<[UInt8]> = []

    private enum Warning { case coolantHot, notCharging, batteryLow }

    private enum Keys {
        static let lastPeripheral = "vespaLastPeripheral"
        static let autoReconnect = "vespaAutoReconnect"
        static let idleDisplay = "vespaIdleDisplay"
        static let warnings = "vespaWarnings"
        static func pairing(_ id: UUID) -> String { "vespaPairing.\(id.uuidString)" }
    }

    override init() {
        autoReconnectValue = UserDefaults.standard.object(forKey: Keys.autoReconnect) as? Bool ?? true
        idleDisplayValue = UserDefaults.standard.string(forKey: Keys.idleDisplay)
            .map { $0 == "off" ? IdleDisplay.off : .text } ?? .off
        warningsEnabledValue = UserDefaults.standard.object(forKey: Keys.warnings) as? Bool ?? true
        super.init()
        // Only touch CoreBluetooth (and trigger the permission prompt) once the rider has paired before.
        if UserDefaults.standard.string(forKey: Keys.lastPeripheral) != nil { ensureCentral() }
    }

    // MARK: - Public actions

    func ensureCentral() {
        if central == nil, !isDemo {
            // Restore identifiers let iOS relaunch the app in the background for Bluetooth events
            // after it was terminated to free memory (not after a force-quit).
            central = CBCentralManager(delegate: self, queue: nil,
                                       options: [CBCentralManagerOptionRestoreIdentifierKey: "vespa.central"])
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil,
                                                    options: [CBPeripheralManagerOptionRestoreIdentifierKey: "vespa.server"])
            append(L("Khởi tạo Bluetooth — iOS \(UIDevice.current.systemVersion), máy \(UIDevice.current.model)", "Bluetooth init — iOS \(UIDevice.current.systemVersion), device \(UIDevice.current.model)"))
        }
    }

    func startScan() {
        ensureCentral()
        guard let central, central.state == .poweredOn else { return }
        discovered.removeAll()
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        state = .scanning
        append(L("Bắt đầu quét", "Scan started"))
    }

    /// One-tap connect: the remembered scooter, otherwise the first Vespa found by a scan.
    func quickConnect() {
        ensureCentral()
        userDisconnected = false
        guard !isConnected, state != .connecting else { return }
        if let id = lastPeripheralID, central?.state == .poweredOn,
           central?.retrievePeripherals(withIdentifiers: [id]).first != nil {
            connect(id)
            return
        }
        autoConnectOnDiscovery = true
        startScan()  // runs again from centralManagerDidUpdateState if Bluetooth is still starting
    }

    func stopScan() {
        central?.stopScan()
        if state == .scanning { state = .idle }
    }

    func connect(_ id: UUID) {
        guard let central, let p = central.retrievePeripherals(withIdentifiers: [id]).first else { return }
        central.stopScan()
        if awaitingScooter || server != nil {
            // A second key link while the data link runs makes the scooter drop both (seen on the scooter).
            append(L("Đang có link dữ liệu với xe — bỏ qua lệnh kết nối (bấm Ngắt trước nếu muốn làm lại)", "Data link already active — connect ignored (tap Disconnect first to start over)"))
            return
        }
        if p.identifier == peripheral?.identifier, p.state == .connected || p.state == .connecting {
            append(L("Đang kết nối / đã kết nối \(p.name ?? "") — bỏ qua lệnh kết nối trùng", "Already connecting/connected \(p.name ?? "") — duplicate connect ignored"))
            return
        }
        userDisconnected = false
        endServerLink(reconnect: false)
        peripheral = p
        p.delegate = self
        state = .connecting
        append(L("Kết nối tới \(p.name ?? id.uuidString)", "Connecting to \(p.name ?? id.uuidString)"))
        central.connect(p)
    }

    func disconnect() {
        userDisconnected = true
        autoConnectOnDiscovery = false
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        endServerLink(reconnect: false)
        state = .idle
    }

    /// Next connection starts a brand-new pairing (the TFT may ask to confirm).
    func forgetPairing() {
        if let id = peripheral?.identifier ?? lastPeripheralID {
            UserDefaults.standard.removeObject(forKey: Keys.pairing(id))
        }
        UserDefaults.standard.removeObject(forKey: Keys.lastPeripheral)
        pairing = nil
        append(L("Đã xoá thông tin ghép đôi", "Pairing info cleared"))
    }

    /// `payload` is the bare 0xC6 dash packet; PMP3 adds its 55 01 05 header, Cobo indicates it as-is.
    func sendDash(_ payload: [UInt8]) {
        lastDash = payload
        if linkProtocol == .cobo && server == nil {
            return  // Cobo dash only goes over the data link; the stream picks lastDash up once ready.
        }
        if server != nil {
            // A newer dash packet replaces any still queued (like both reference clients).
            indications.removeAll { Self.isDash($0) }
            indicate(payload)
        } else {
            queue.removeAll { $0.starts(with: PMP3.dashHeader + [PMP3.dashTag]) }
            enqueue(PMP3.dashHeader + payload)
        }
    }

    /// Lab test arrow, encoded the way the connected scooter expects.
    func sendTestDash(icon: UInt8, distance: Int, road: String) {
        if server != nil || linkProtocol == .cobo {
            sendDash(Cobo.dashPayload(icon: icon, distanceToTurn: distance, distanceToDestination: distance + 1500,
                                      minutesToDestination: 5, text: road))
        } else {
            sendDash(PMP3.dashPacket(icon: icon, distanceToTurn: distance, distanceToDestination: distance + 1500,
                                     minutesToDestination: 5, roadName: road, options: dashOptions, includeHeader: false))
        }
    }

    func sendRaw(_ bytes: [UInt8]) {
        if server != nil { indicate(bytes) } else { enqueue(bytes) }
    }

    func clearLog() { log.removeAll() }

    /// `redacted`: for a public GitHub issue — no Bluetooth addresses, pairing keys, auth answers or
    /// names of other people's devices.
    func exportLog(redacted: Bool = false) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var lines = ["VesNavi — Bluetooth log",
                     "iOS \(UIDevice.current.systemVersion) · \(UIDevice.current.model)",
                     "Scooter: \(connectedName ?? "-") · state: \(state) · protocol: \(linkProtocol?.rawValue ?? "-") · fw: \(firmwareInfo ?? "-")",
                     "Cobo: answer=\(coboAnswer.map { Hex.encode($0, separator: "") } ?? "-") · data link: \(server.map { "\($0.stage)" } ?? "-")",
                     "Pairing: id=\(pairing.map { Hex.encode($0.deviceID, separator: "") } ?? "-") key=\(pairing?.key.map { Hex.encode($0, separator: "") } ?? "-")",
                     "", "GATT:"] + gattTable + ["", "LOG:"]
        lines += log.map { "\(f.string(from: $0.time))  \($0.text)" }
        let text = lines.joined(separator: "\n")
        guard redacted else { return text }
        let strangers = discovered.filter { !$0.isLikelyVespa && !["(không tên)", "(no name)"].contains($0.name) }.map(\.name)
        return BugReport.redact(text, secrets: logSecrets.flatMap { BugReport.hexVariants($0) } + strangers)
    }

    // MARK: - DashboardSink

    func send(_ state: NavigationState) {
        let text = state.hasArrived ? L("Đến nơi", "Arrived")
            : "\(DistanceFormatter.text(state.distanceToManeuver, language: appLanguage)) · \(state.instruction)"
        lastMessage = isReady ? text : L("\(text) (xe chưa kết nối)", "\(text) (scooter not connected)")
        guard isReady else { return }
        sendDash(server != nil ? Cobo.dashPayload(for: state, language: appLanguage)
                               : PMP3.dashPacket(for: state, options: dashOptions, includeHeader: false))
    }

    func clear() {
        lastMessage = nil
        // Cobo: tell the TFT the route ended (06 03 00) once, then fall back to the idle dash.
        if lastDash != nil, server != nil { stopDashPending = true }
        lastDash = nil
    }

    // MARK: - Internals (central side)

    private var lastPeripheralID: UUID? {
        UserDefaults.standard.string(forKey: Keys.lastPeripheral).flatMap(UUID.init(uuidString:))
    }

    private func append(_ text: String) {
        log.append(LogLine(time: Date(), text: text))
        // Trim in batches: removeFirst shifts the whole array, so don't do it on every line.
        if log.count > 2500 { log.removeFirst(log.count - 2000) }
    }

    private func enqueue(_ bytes: [UInt8]) {
        guard writeChar != nil else {
            append(L("⚠️ Chưa có kênh ghi — bỏ qua \(Hex.encode(bytes))", "⚠️ No write channel yet — dropped \(Hex.encode(bytes))"))
            return
        }
        queue.append(bytes)
        pump()
    }

    private func pump() {
        guard !writeInFlight, let p = peripheral, let c = writeChar, !queue.isEmpty else { return }
        let bytes = queue.removeFirst()
        writeInFlight = true
        append("TX \(Hex.encode(bytes))")
        p.writeValue(Data(bytes), for: c, type: .withResponse)
    }

    private func apply(_ out: PMP3Session.Output) {
        for event in out.events { handle(event) }
        for w in out.writes { enqueue(w) }
    }

    private func handle(_ event: PMP3Session.Event) {
        switch event {
        case .pairingChanged(let p):
            pairing = p
            if let key = p.key { logSecrets.insert(key) }
            if let id = peripheral?.identifier, let data = try? JSONEncoder().encode(p) {
                UserDefaults.standard.set(data, forKey: Keys.pairing(id))
            }
            append(L("Lưu pairing", "Pairing saved") + ": id=\(Hex.encode(p.deviceID, separator: "")) key=\(p.key.map { Hex.encode($0, separator: "") } ?? "-")")
        case .newPairingRequested:
            append(L("🔑 Xe yêu cầu ghép đôi mới — nếu màn hình xe hỏi, hãy bấm xác nhận", "🔑 Scooter asks for a new pairing — confirm on the TFT if asked"))
        case .authResult(let ok):
            append(ok ? L("Xác thực: OK", "Authentication: OK") : L("Xác thực: thất bại (có thể cần 'Quên ghép đôi' rồi kết nối lại)", "Authentication failed (try 'Forget pairing' and connect again)"))
        case .authenticated:
            append(L("✅ Xe chấp nhận thiết bị", "✅ Scooter accepted the phone"))
        case .ready(let ready):
            state = ready ? .ready : .handshaking
            append(ready ? L("✅ Màn hình sẵn sàng nhận dẫn đường", "✅ TFT ready for navigation") : L("Màn hình chưa sẵn sàng (45 … 00)", "TFT not ready (45 … 00)"))
            if ready { startPMP3KeepAlive() }
        case .firmware(let variant, let fw, let hw):
            firmwareInfo = "variant \(variant) · fw \(fw) · hw \(hw)"
            append("Firmware: \(firmwareInfo ?? "")")
        case .button(let b):
            append(L("Nút trên xe: \(b)", "Scooter button: \(b)"))
        case .unhandled(let b):
            append(L("(chưa xử lý) \(Hex.encode(b))", "(unhandled) \(Hex.encode(b))"))
        }
    }

    private func startPMP3KeepAlive() {
        pmp3KeepAlive?.cancel()
        pmp3KeepAlive = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let self, self.isReady else { return }
            self.enqueue(PMP3Session.heartbeat)
            // The reference client refreshes the dash several times a second; resend the last packet each second.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard self.isReady else { return }
                if let dash = self.lastDash, self.queue.isEmpty { self.sendDash(dash) }
            }
        }
    }

    /// Clears the central link only; a running Cobo data link is unaffected.
    private func resetCentralLink() {
        pmp3KeepAlive?.cancel()
        pmp3KeepAlive = nil
        session = nil
        keyFetch = nil
        writeChar = nil
        queue.removeAll()
        writeInFlight = false
        gattTable.removeAll()
    }

    private func reconnectIfRemembered(force: Bool = false) {
        guard autoReconnect || force, !userDisconnected, !awaitingScooter, server == nil,
              let central, let id = lastPeripheralID,
              let p = central.retrievePeripherals(withIdentifiers: [id]).first,
              p.state != .connected, p.state != .connecting else { return }
        peripheral = p
        p.delegate = self
        state = .connecting
        append(L("Tự kết nối lại \(p.name ?? id.uuidString) (chờ xe bật)", "Auto-reconnect \(p.name ?? id.uuidString) (waiting for the scooter)"))
        central.connect(p)  // iOS keeps this pending until the scooter is in range
    }

    /// The 6-byte auth answer may start with 0xC6 too; dash packets are always longer.
    private static func isDash(_ b: [UInt8]) -> Bool { b.count > 6 && b.first == PMP3.dashTag }

    private static func isLikelyVespa(name: String, services: [String]) -> Bool {
        let n = name.uppercased()
        return services.contains(PMP3.serviceUUID) || services.contains(Cobo.serviceUUID)
            || ["VESPA", "PIAGGIO", "BT-ROUTER", "MIA", "PMP"].contains { n.contains($0) }
    }

    // MARK: - Internals (Cobo key link → data link)

    private func handle(_ event: CoboKeyFetch.Event) {
        switch event {
        case .challenge(let b, let reply):
            append(L("🔑 Link lấy khoá: xe gửi thử thách \(Hex.encode(b))", "🔑 Key link: challenge \(Hex.encode(b))"))
            guard let p = peripheral, let c = writeChar else {
                append(L("⚠️ Chưa có kênh ghi 9B00 — không trả lời được thử thách", "⚠️ No 9B00 write channel — can't answer the challenge"))
                return
            }
            // The official app answers with a write command (no response) ~5 ms after the challenge.
            // Needs ATT MTU ≥ 26 (the scooter asks for 131); otherwise fall back to a (long) write request.
            let noResp = c.properties.contains(.writeWithoutResponse)
                && p.maximumWriteValueLength(for: .withoutResponse) >= reply.count
            let type: CBCharacteristicWriteType = noResp ? .withoutResponse : .withResponse
            if !noResp { append(L("⚠️ Gói ghi không phản hồi quá nhỏ (\(p.maximumWriteValueLength(for: .withoutResponse)) byte) — dùng ghi có phản hồi", "⚠️ Write-without-response too small (\(p.maximumWriteValueLength(for: .withoutResponse)) bytes) — using write with response")) }
            append(L("TX \(Hex.encode(reply)) — trả lời thử thách, chờ xe gửi câu trả lời 6 byte", "TX \(Hex.encode(reply)) — challenge reply, waiting for the 6-byte answer"))
            p.writeValue(Data(reply), for: c, type: type)
        case .answer(let a):
            coboAnswer = a
            logSecrets.insert(a)
            append(L("🔑 Link lấy khoá: nhận câu trả lời \(Hex.encode(a))", "🔑 Key link: answer \(Hex.encode(a))"))
            beginServerPhase()
        case .ignored(let b):
            append(L("(link lấy khoá, bỏ qua) \(Hex.encode(b))", "(key link, ignored) \(Hex.encode(b))"))
        }
    }

    /// Mirrors the official app: advertise the Cobo service, then close the key link ourselves;
    /// the scooter reconnects to the iPhone's GATT server within a fraction of a second.
    private func beginServerPhase() {
        guard let pm = peripheralManager, pm.state == .poweredOn, coboServiceAdded else {
            append(L("⚠️ iPhone chưa sẵn sàng làm máy chủ Bluetooth — đóng link và thử lại", "⚠️ iPhone GATT server not ready — closing the link, will retry"))
            addCoboServiceIfNeeded()
            if let p = peripheral { central?.cancelPeripheralConnection(p) }  // didDisconnect retries
            return
        }
        awaitingScooter = true
        state = .waitingForScooter
        if !pm.isAdvertising {
            pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: Cobo.serviceUUID)]])
        }
        append(L("Phát quảng bá dịch vụ Cobo và đóng link lấy khoá (giống app chính hãng)", "Advertising the Cobo service and closing the key link (like the official app)"))
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        serverWatchdog?.cancel()
        serverWatchdog = Task { [weak self] in
            // The official app also re-runs the key link ~10 s after a scooter that never connected back.
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self, !Task.isCancelled, self.awaitingScooter else { return }
            self.append(L("⚠️ Xe không kết nối vào iPhone sau 10 s — thử lại từ đầu", "⚠️ Scooter didn't connect back within 10 s — starting over"))
            self.endServerLink(reconnect: true)
        }
    }

    private func addCoboServiceIfNeeded() {
        guard let pm = peripheralManager, !coboServiceAdded else { return }
        let write = CBMutableCharacteristic(type: CBUUID(string: Cobo.writeCharacteristicUUID),
                                            properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        let indicate = CBMutableCharacteristic(type: CBUUID(string: Cobo.notifyCharacteristicUUID),
                                               properties: [.notify, .indicate], value: nil, permissions: [.readable])
        let service = CBMutableService(type: CBUUID(string: Cobo.serviceUUID), primary: true)
        service.characteristics = [write, indicate]
        pm.add(service)
        coboIndicateChar = indicate
        coboServiceAdded = true
    }

    private func indicate(_ bytes: [UInt8]) {
        indications.append(bytes)
        pumpIndications()
    }

    /// Indications are flow-controlled: when `updateValue` returns false, wait for
    /// `peripheralManagerIsReady(toUpdateSubscribers:)` and continue from there.
    private func pumpIndications() {
        guard scooterSubscribed, let pm = peripheralManager, let ch = coboIndicateChar, let c = scooterCentral else { return }
        while let next = indications.first {
            guard pm.updateValue(Data(next), for: ch, onSubscribedCentrals: [c]) else { return }
            indications.removeFirst()
            if !Self.isDash(next) {
                append("TX⇢ \(Hex.encode(next))")
            } else if next != lastLoggedDash {
                lastLoggedDash = next
                append("TX⇢ dash \(Hex.encode(next))")
            }
        }
    }

    private func handle(_ event: CoboServerSession.Event) {
        switch event {
        case .answerSent:
            append(L("🔑 Xe hỏi câu trả lời → đã gửi, bắt đầu khởi tạo PMP2", "🔑 Scooter asked for the answer → sent, starting PMP2 init"))
        case .missingAnswer:
            append(L("⚠️ Xe hỏi câu trả lời nhưng link lấy khoá chưa cung cấp — sẽ thử lại", "⚠️ Scooter asked for the answer but the key link has none — will retry"))
            endServerLink(reconnect: true)
        case .command(let cmd, let payload):
            append(L("Xe → lệnh DQ", "Scooter → DQ command") + " 0x\(String(format: "%02X", cmd)) [\(Hex.encode(payload))]")
        case .canData(let b):
            updateTelemetry(b)
            if Date().timeIntervalSince(lastCanLog) > 5 {
                lastCanLog = Date()
                append(L("Xe → dữ liệu CAN \(Hex.encode(b)) (chỉ ghi mỗi 5 s)", "Scooter → CAN data \(Hex.encode(b)) (logged every 5 s)"))
            }
        case .ready:
            state = .ready
            append(L("✅ Màn hình sẵn sàng nhận dẫn đường (PMP2)", "✅ TFT ready for navigation (PMP2)"))
            startCoboStream()
        case .unhandled(let b):
            append(L("(chưa xử lý) \(Hex.encode(b))", "(unhandled) \(Hex.encode(b))"))
        }
    }

    /// Official cadence: dash every ~0.5 s; the two 0x27 reads start ~2 s after init.
    private func startCoboStream() {
        coboStream?.cancel()
        coboStream = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            var tick = 0
            while !Task.isCancelled {
                guard let self, let server = self.server, server.stage == .ready else { return }
                if tick % 20 == 0 {
                    // 0x27 read: once after ~2 s, retried every ~10 s until the scooter answers (0x30).
                    for frame in server.readRequestFrames() { self.indicate(frame) }
                }
                self.indications.removeAll { Self.isDash($0) }
                if let popup = self.popup, self.popupTicksLeft > 0 {
                    // Like the official app: the pop-up replaces the dash packet for a few packets.
                    self.popupTicksLeft -= 1
                    if self.popupTicksLeft == 0 { self.popup = nil }
                    self.indications.removeAll { $0.first == 0xC2 }
                    self.indicate(popup)
                } else if self.stopDashPending {
                    self.stopDashPending = false
                    self.indicate(Cobo.stopDash)
                } else {
                    self.indicate(self.lastDash ?? self.idleDashPacket())
                }
                tick += 1
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    /// Pop-up on the TFT (two lines, ASCII, ≤ 20 chars each). Cobo data link only.
    func showPopup(_ line1: String, _ line2: String) {
        guard server?.stage == .ready else {
            append(L("⚠️ Popup chỉ gửi được khi xe ✅ Sẵn sàng", "⚠️ Pop-ups need the scooter to be ✅ Ready"))
            return
        }
        popup = Cobo.popupPayload(line1, line2)
        popupTicksLeft = Cobo.popupRepeats
        append("Popup: \(line1) / \(line2)")
    }

    private func idleDashPacket() -> [UInt8] {
        guard idleDisplay != .off, let t = latestTelemetry, let text = Cobo.infoText(t, language: appLanguage) else { return Cobo.idleDash }
        return Cobo.infoDash(text, navigationScreen: true)
    }

    private func checkWarnings(_ t: ScooterTelemetry) {
        guard warningsEnabled else { return }
        if let c = t.coolantC, t.engineRunning, c >= 105 {
            warn(.coolantHot, L("Nhiet do cao", "Engine hot"), L("Nuoc mat \(c)C", "Coolant \(c)C"))
        }
        // Running engine should charge at ~13.5–14.5 V; below 12.5 V for 30 s means it isn't.
        if let v = t.batteryVolts, t.engineRunning, v < 12.5 {
            if lowChargeSince == nil { lowChargeSince = Date() }
            if let since = lowChargeSince, Date().timeIntervalSince(since) > 30 {
                warn(.notCharging, L("Ac quy khong sac", "Not charging"), String(format: "%.1fV", v))
            }
        } else {
            lowChargeSince = nil
        }
        if let v = t.batteryVolts, !t.engineRunning, v < 11.8 {
            warn(.batteryLow, L("Ac quy yeu", "Battery low"), String(format: "%.1fV", v))
        }
    }

    /// At most one pop-up per warning every 5 minutes.
    private func warn(_ w: Warning, _ line1: String, _ line2: String) {
        if let last = lastWarning[w], Date().timeIntervalSince(last) < 300 { return }
        lastWarning[w] = Date()
        showPopup(line1, line2)
    }

    private func updateTelemetry(_ frame: [UInt8]) {
        guard let t = CoboTelemetry.parse(frame, into: latestTelemetry ?? ScooterTelemetry()) else { return }
        publish(t)
    }

    private func publish(_ t: ScooterTelemetry) {
        latestTelemetry = t
        // The scooter sends ~4 frames a second. Trips and warnings use every frame, but the UI only
        // needs a couple of refreshes a second (and almost none in the background): every publish
        // re-renders the views that read it, which is what heats a phone up.
        let now = Date()
        let interval: TimeInterval = isForeground ? 0.5 : 5
        if telemetry == nil || now.timeIntervalSince(lastTelemetryPublish) >= interval {
            lastTelemetryPublish = now
            telemetryAt = now
            if t != telemetry { telemetry = t }
        }
        onTelemetry?(t)
        if let rpm = t.rpm, rpm > peakRPM { peakRPM = rpm }
        if let c = t.coolantC, t.engineRunning, c > (peakCoolantC ?? Int.min) { peakCoolantC = c }
        checkWarnings(t)
    }

    private func endServerLink(reconnect: Bool) {
        serverWatchdog?.cancel()
        serverWatchdog = nil
        coboStream?.cancel()
        coboStream = nil
        peripheralManager?.stopAdvertising()
        let wasCobo = awaitingScooter || server != nil
        if telemetry != nil { onDataLinkEnded?() }
        awaitingScooter = false
        server = nil
        scooterCentral = nil
        scooterSubscribed = false
        indications.removeAll()
        coboAnswer = nil
        lastLoggedDash = nil
        telemetry = nil
        latestTelemetry = nil
        telemetryAt = nil
        popup = nil
        popupTicksLeft = 0
        lowChargeSince = nil
        if wasCobo, [.waitingForScooter, .handshaking, .ready].contains(state) { state = .idle }
        guard reconnect else { return }
        // Immediately, not after a delay: in the background a sleeping Task doesn't run until the app
        // is woken again (seen on the scooter: no reconnect after an ignition cycle), while a pending
        // CoreBluetooth connect is kept by iOS and wakes the app when the scooter comes back.
        // Part of the handshake itself, so retry even when auto-reconnect is off.
        reconnectIfRemembered(force: true)
    }
}

// MARK: - CBCentralManagerDelegate

extension VespaBLE: CBCentralManagerDelegate {
    /// iOS relaunched the app in the background with a pending/open connection.
    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? []
        MainActor.assumeIsolated {
            guard let p = restored.first else { return }
            peripheral = p
            p.delegate = self
            append(L("Khôi phục Bluetooth sau khi iOS mở lại app (\(p.name ?? "xe"))", "Bluetooth restored after iOS relaunched the app (\(p.name ?? "scooter"))"))
        }
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let s = central.state
        MainActor.assumeIsolated {
            switch s {
            case .poweredOn:
                if state == .off || state == .unauthorized { state = .idle }
                append(L("Bluetooth bật", "Bluetooth on"))
                if autoConnectOnDiscovery && lastPeripheralID == nil { startScan() } else { reconnectIfRemembered() }
            case .unauthorized:
                state = .unauthorized
            default:
                state = .off
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let services = ((advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []).map(\.uuidString)
        let rssi = RSSI.intValue
        MainActor.assumeIsolated {
            let item = Discovered(id: id, name: name.isEmpty ? L("(không tên)", "(no name)") : name, rssi: rssi, services: services,
                                  isLikelyVespa: Self.isLikelyVespa(name: name, services: services))
            if let i = discovered.firstIndex(where: { $0.id == id }) {
                discovered[i] = item
            } else {
                discovered.append(item)
                append(L("Thấy: \(item.name) RSSI \(rssi) dịch vụ [\(services.joined(separator: ","))]", "Found: \(item.name) RSSI \(rssi) services [\(services.joined(separator: ","))]"))
            }
            // Auto-connect only on a sure match (known service or the scooter's own name).
            if autoConnectOnDiscovery,
               services.contains(Cobo.serviceUUID) || services.contains(PMP3.serviceUUID)
                || ["BT-ROUTER", "VESPA"].contains(where: { name.uppercased().contains($0) }) {
                autoConnectOnDiscovery = false
                connect(id)
            }
            discovered.sort { ($0.isLikelyVespa ? 1 : 0, $0.rssi) > ($1.isLikelyVespa ? 1 : 0, $1.rssi) }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            connectedName = peripheral.name
            state = .discovering
            resetCentralLink()
            UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Keys.lastPeripheral)
            append(L("Đã kết nối \(peripheral.name ?? "") — đọc toàn bộ dịch vụ", "Connected \(peripheral.name ?? "") — reading all services"))
            peripheral.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let msg = error?.localizedDescription ?? "?"
        MainActor.assumeIsolated {
            append(L("Kết nối thất bại: \(msg)", "Connection failed: \(msg)"))
            state = .idle
            reconnectIfRemembered()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let msg = error?.localizedDescription ?? L("bình thường", "normal")
        MainActor.assumeIsolated {
            resetCentralLink()
            connectedName = nil
            if awaitingScooter || server != nil {
                // Expected: we closed the Cobo key link ourselves; the data link takes over.
                append(L("Đã đóng link lấy khoá (\(msg)) — chờ xe kết nối vào iPhone", "Key link closed (\(msg)) — waiting for the scooter to connect back"))
                return
            }
            append(L("Mất kết nối (\(msg))", "Disconnected (\(msg))"))
            state = .idle
            reconnectIfRemembered()
        }
    }
}

// MARK: - CBPeripheralDelegate (central side)

extension VespaBLE: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        MainActor.assumeIsolated {
            if let error { append(L("Lỗi đọc dịch vụ: \(error.localizedDescription)", "Service discovery error: \(error.localizedDescription)")) }
            append(L("Dịch vụ: \(services.map(\.uuid.uuidString).joined(separator: ", "))", "Services: \(services.map(\.uuid.uuidString).joined(separator: ", "))"))
            if services.contains(where: { $0.uuid == CBUUID(string: PMP3.serviceUUID) }) {
                linkProtocol = .pmp3
            } else if services.contains(where: { $0.uuid == CBUUID(string: Cobo.serviceUUID) }) {
                linkProtocol = .cobo
                if !coboServiceAdded { append(L("⚠️ Dịch vụ máy chủ Cobo trên iPhone chưa sẵn sàng", "⚠️ Cobo GATT service on the iPhone not ready")) }
            } else {
                linkProtocol = nil
                state = .unknownService
                append(L("⚠️ Không có dịch vụ PMP3 (0x1815) hay Cobo — chưa hỗ trợ xe này", "⚠️ No PMP3 (0x1815) or Cobo service — this scooter isn't supported yet"))
            }
            if let linkProtocol { append(L("Giao thức: \(linkProtocol.rawValue)", "Protocol: \(linkProtocol.rawValue)")) }
            for s in services { peripheral.discoverCharacteristics(nil, for: s) }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let chars = service.characteristics ?? []
        let serviceID = service.uuid
        MainActor.assumeIsolated {
            for c in chars {
                let line = "\(serviceID.uuidString) / \(c.uuid.uuidString) [\(Self.describe(c.properties))]"
                gattTable.append(line)
                append("GATT \(line)")
            }
            let ids: (service: String, write: String, notify: String)
            switch linkProtocol {
            case .pmp3: ids = (PMP3.serviceUUID, PMP3.writeCharacteristicUUID, PMP3.notifyCharacteristicUUID)
            case .cobo: ids = (Cobo.serviceUUID, Cobo.writeCharacteristicUUID, Cobo.notifyCharacteristicUUID)
            case nil: return
            }
            guard serviceID == CBUUID(string: ids.service) else { return }
            writeChar = chars.first { $0.uuid == CBUUID(string: ids.write) }
            // Create the key-link parser before subscribing so the first notification can't be missed.
            if linkProtocol == .cobo { keyFetch = CoboKeyFetch() }
            if let notify = chars.first(where: { $0.uuid == CBUUID(string: ids.notify) }) {
                state = .handshaking
                peripheral.setNotifyValue(true, for: notify)
            } else {
                append(L("⚠️ Không thấy kênh notify \(ids.notify)", "⚠️ Notify characteristic \(ids.notify) not found"))
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        let notifying = characteristic.isNotifying
        let id = peripheral.identifier
        let msg = error?.localizedDescription
        MainActor.assumeIsolated {
            if let msg { append(L("Lỗi bật notify: \(msg)", "Notify error: \(msg)")); return }
            guard notifying else { return }
            if linkProtocol == .cobo, characteristic.uuid == CBUUID(string: Cobo.notifyCharacteristicUUID) {
                append(L("Link lấy khoá: đã bật notify — chờ xe gửi thử thách", "Key link: notify on — waiting for the challenge"))
                return
            }
            guard characteristic.uuid == CBUUID(string: PMP3.notifyCharacteristicUUID) else { return }
            let stored = UserDefaults.standard.data(forKey: Keys.pairing(id)).flatMap { try? JSONDecoder().decode(PMP3Pairing.self, from: $0) }
            pairing = stored
            if let key = stored?.key { logSecrets.insert(key) }
            let s = PMP3Session(pairing: stored, phoneName: UIDevice.current.name)
            session = s
            append(L("Notify 2A5A bật — MTU ghi tối đa \(peripheral.maximumWriteValueLength(for: .withResponse)) byte — bắt đầu bắt tay", "Notify 2A5A on — max write \(peripheral.maximumWriteValueLength(for: .withResponse)) bytes — starting handshake"))
            apply(s.start())
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let bytes = [UInt8](characteristic.value ?? Data())
        let uuid = characteristic.uuid
        MainActor.assumeIsolated {
            append("RX \(Hex.encode(bytes))")
            if uuid == CBUUID(string: Cobo.notifyCharacteristicUUID), let keyFetch {
                handle(keyFetch.handle(bytes))
            } else if uuid == CBUUID(string: PMP3.notifyCharacteristicUUID), let session {
                apply(session.handle(bytes))
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let msg = error?.localizedDescription
        MainActor.assumeIsolated {
            if let msg { append(L("Lỗi ghi: \(msg)", "Write error: \(msg)")) }
            writeInFlight = false
            pump()
        }
    }

    private nonisolated static func describe(_ p: CBCharacteristicProperties) -> String {
        var parts: [String] = []
        if p.contains(.read) { parts.append("read") }
        if p.contains(.write) { parts.append("write") }
        if p.contains(.writeWithoutResponse) { parts.append("writeNoResp") }
        if p.contains(.notify) { parts.append("notify") }
        if p.contains(.indicate) { parts.append("indicate") }
        return parts.joined(separator: ",")
    }
}

// MARK: - CBPeripheralManagerDelegate (Cobo data link, iPhone = GATT server)

extension VespaBLE: CBPeripheralManagerDelegate {
    /// iOS relaunched the app in the background: our GATT service is still published.
    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        let services = (dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService]) ?? []
        let cobo = services.first { $0.uuid == CBUUID(string: Cobo.serviceUUID) }
        let notifyID = CBUUID(string: Cobo.notifyCharacteristicUUID)
        let indicate = cobo?.characteristics?.first(where: { $0.uuid == notifyID }) as? CBMutableCharacteristic
        MainActor.assumeIsolated {
            guard let indicate else { return }
            coboIndicateChar = indicate
            coboServiceAdded = true
            append(L("Khôi phục dịch vụ Cobo sau khi iOS mở lại app", "Cobo service restored after iOS relaunched the app"))
        }
    }

    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let s = peripheral.state
        MainActor.assumeIsolated {
            if s == .poweredOn {
                addCoboServiceIfNeeded()
            } else {
                coboServiceAdded = false
                endServerLink(reconnect: false)
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        let msg = error?.localizedDescription
        MainActor.assumeIsolated {
            if let msg {
                coboServiceAdded = false
                append(L("⚠️ Không đăng ký được dịch vụ Cobo trên iPhone: \(msg)", "⚠️ Couldn't register the Cobo service on the iPhone: \(msg)"))
            } else {
                append(L("Đã đăng ký dịch vụ Cobo trên iPhone (chế độ máy chủ)", "Cobo service registered on the iPhone (server mode)"))
            }
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        let msg = error?.localizedDescription
        MainActor.assumeIsolated {
            append(msg.map { L("⚠️ Lỗi phát quảng bá: \($0)", "⚠️ Advertising error: \($0)") } ?? L("Đang phát quảng bá dịch vụ Cobo", "Advertising the Cobo service"))
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                       didSubscribeTo characteristic: CBCharacteristic) {
        let maxLen = central.maximumUpdateValueLength
        let isCobo = characteristic.uuid == CBUUID(string: Cobo.notifyCharacteristicUUID)
        MainActor.assumeIsolated {
            guard isCobo else { return }
            serverWatchdog?.cancel()
            serverWatchdog = nil
            awaitingScooter = false
            peripheral.stopAdvertising()
            scooterCentral = central
            scooterSubscribed = true
            peakRPM = 0
            peakCoolantC = nil
            if server == nil { server = CoboServerSession(answer: coboAnswer) }
            if state != .ready { state = .handshaking }
            append(L("Xe đã kết nối vào iPhone (link dữ liệu) — gói tối đa \(maxLen) byte", "Scooter connected to the iPhone (data link) — max packet \(maxLen) bytes"))
            if maxLen < 128 { append(L("⚠️ Gói tối đa < 128 byte — gói 0x28 (128 byte) sẽ không gửi được", "⚠️ Max packet < 128 bytes — the 0x28 packet (128 bytes) won't fit")) }
            pumpIndications()
            // iOS can't drop a central from the server side; a stalled init can only be reported.
            serverWatchdog = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                guard let self, !Task.isCancelled, let server = self.server, server.stage != .ready else { return }
                self.append(L("⚠️ Xe dừng giữa chừng khi khởi tạo (\(server.stage)) — tắt/bật chìa khoá xe rồi thử lại", "⚠️ Scooter stopped during init (\(server.stage)) — cycle the ignition and try again"))
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                       didUnsubscribeFrom characteristic: CBCharacteristic) {
        let isCobo = characteristic.uuid == CBUUID(string: Cobo.notifyCharacteristicUUID)
        MainActor.assumeIsolated {
            guard isCobo else { return }
            append(L("Xe đã ngắt link dữ liệu — làm lại từ link lấy khoá", "Scooter dropped the data link — starting again from the key link"))
            endServerLink(reconnect: true)
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        let writeID = CBUUID(string: Cobo.writeCharacteristicUUID)
        let items = requests.map { (uuid: $0.characteristic.uuid, bytes: [UInt8]($0.value ?? Data()), central: $0.central) }
        let first = requests.first
        MainActor.assumeIsolated {
            for item in items where item.uuid == writeID {
                if scooterCentral == nil { scooterCentral = item.central }
                if server == nil { server = CoboServerSession(answer: coboAnswer) }
                guard let server else { continue }
                let out = server.handleWrite(item.bytes)
                if !out.events.contains(where: { if case .canData = $0 { return true } else { return false } }) {
                    append("RX⇠ \(Hex.encode(item.bytes))")
                }
                for e in out.events { handle(e) }
                for i in out.indications { indicate(i) }
            }
            // Every write request must be answered once, or the scooter's ATT transaction times out.
            if let first { peripheral.respond(to: first, withResult: .success) }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        MainActor.assumeIsolated {
            request.value = Data()
            peripheral.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        MainActor.assumeIsolated { pumpIndications() }
    }
}

#if DEBUG
extension VespaBLE {
    /// README recordings (`-demoMode YES`, simulator has no Bluetooth): pretend the scooter is
    /// connected and stream plausible engine data; `speedKmh` follows the route simulation.
    func startDemo(speedKmh: @escaping @MainActor () -> Double) {
        isDemo = true
        state = .ready
        linkProtocol = .cobo  // dash packets then wait for a data link that never comes: nothing is sent
        connectedName = "BT-ROUTER"
        Task { [weak self] in
            var seconds = 0.0
            var odometer = 12_480.0
            while !Task.isCancelled {
                guard let self else { return }
                let speed = speedKmh()
                odometer += speed / 3600 / 2
                var t = ScooterTelemetry()
                t.rpm = speed > 0 ? Int(3_600 + speed * 60 + 400 * sin(seconds)) : Int(1_700 + 40 * sin(seconds * 3))
                t.coolantC = min(88, 74 + Int(seconds / 4))
                t.intakeAirC = 36
                t.ambientC = 31
                t.batteryVolts = 14.3 + 0.1 * sin(seconds / 2)
                t.odometerKm = Int(odometer)
                t.rearWheelKmh = speed
                t.frontWheelKmh = speed
                t.dashboardSpeedKmh = Int((speed * 1.08).rounded())
                t.fuelBars = 5
                t.throttle = speed > 0 ? Int(22 + 8 * sin(seconds)) : 0
                t.fuelLitersPerHour = speed > 0 ? 1.1 + speed / 60 : 0.25
                t.milLamp = 0
                self.publish(t)
                seconds += 0.5
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }
}
#endif
