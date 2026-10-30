package com.nxnson.vesnavi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nxnson.vesnavi.navcore.BugReport
import com.nxnson.vesnavi.navcore.Bytes
import com.nxnson.vesnavi.navcore.Cobo
import com.nxnson.vesnavi.navcore.CoboKeyFetch
import com.nxnson.vesnavi.navcore.CoboServerSession
import com.nxnson.vesnavi.navcore.CoboTelemetry
import com.nxnson.vesnavi.navcore.DistanceFormatter
import com.nxnson.vesnavi.navcore.Hex
import com.nxnson.vesnavi.navcore.NavigationState
import com.nxnson.vesnavi.navcore.PMP3
import com.nxnson.vesnavi.navcore.PMP3Pairing
import com.nxnson.vesnavi.navcore.PMP3Session
import com.nxnson.vesnavi.navcore.Persistence
import com.nxnson.vesnavi.navcore.ScooterTelemetry
import com.nxnson.vesnavi.navcore.toByteArray
import com.nxnson.vesnavi.navcore.toBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Bluetooth link to the Vespa TFT + a debug log the rider can export (port of the iOS VespaBLE).
 * - PMP3 scooters (service 0x1815): one link, phone = central.
 * - PMP2 scooters ("Cobo", e.g. GTS Supertech): two links — a short **key link** (phone = central)
 *   that collects the auth answer and is then closed by us, and a **data link** where the scooter
 *   connects to the phone's GATT server and receives indications.
 * Every Android callback is moved to the main thread, so all state below is main-thread only.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
class VespaBLE(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    enum class LinkState {
        OFF, UNAUTHORIZED, IDLE, SCANNING, CONNECTING, DISCOVERING, HANDSHAKING, WAITING_FOR_SCOOTER, READY, UNKNOWN_SERVICE;

        val title: String
            get() = when (this) {
                OFF -> L("Bluetooth tắt", "Bluetooth off")
                UNAUTHORIZED -> L("Chưa cấp quyền Bluetooth", "Bluetooth permission needed")
                IDLE -> L("Chưa kết nối", "Not connected")
                SCANNING -> L("Đang quét…", "Scanning…")
                CONNECTING -> L("Đang kết nối…", "Connecting…")
                DISCOVERING -> L("Đang đọc dịch vụ…", "Reading services…")
                HANDSHAKING -> L("Đang bắt tay…", "Handshaking…")
                WAITING_FOR_SCOOTER -> L("Chờ xe kết nối vào điện thoại…", "Waiting for the scooter…")
                READY -> L("✅ Sẵn sàng", "✅ Ready")
                UNKNOWN_SERVICE -> L("⚠️ Không nhận ra dịch vụ Bluetooth của xe", "⚠️ Unknown scooter Bluetooth service")
            }
    }

    enum class LinkProtocol(val title: String) { PMP3("PMP3 (0x1815)"), COBO("PMP2 over BLE (Cobo)") }

    data class Discovered(val address: String, val name: String, val rssi: Int, val services: List<String>, val isLikelyVespa: Boolean)

    data class LogLine(val time: Long, val text: String)

    private enum class Warning { COOLANT_HOT, NOT_CHARGING, BATTERY_LOW }

    val name = "Vespa TFT (Bluetooth)"
    var state by mutableStateOf(LinkState.IDLE)
        private set
    val discovered = mutableStateListOf<Discovered>()
    val log = mutableStateListOf<LogLine>()
    var connectedName by mutableStateOf<String?>(null)
        private set
    val gattTable = mutableStateListOf<String>()
    var firmwareInfo by mutableStateOf<String?>(null)
        private set
    /** Human-readable copy of the last thing sent (shown in the HUD for debugging). */
    var lastMessage by mutableStateOf<String?>(null)
        private set
    var pairing by mutableStateOf<PMP3Pairing?>(null)
        private set
    var linkProtocol by mutableStateOf<LinkProtocol?>(null)
        private set
    /** Live engine data (PMP2/Cobo scooters only); null until the first data frame. */
    var telemetry by mutableStateOf<ScooterTelemetry?>(null)
        private set
    var telemetryAt by mutableStateOf<Long?>(null)
        private set
    /** Highest values since the data link came up. */
    var peakRPM by mutableStateOf(0)
        private set
    var dashOptions by mutableStateOf(PMP3.DashOptions())

    val isReady get() = state == LinkState.READY
    val isConnected
        get() = state in setOf(LinkState.DISCOVERING, LinkState.HANDSHAKING, LinkState.WAITING_FOR_SCOOTER, LinkState.READY, LinkState.UNKNOWN_SERVICE)

    /** The link is up or the remembered scooter is expected back: keep the app alive for it. */
    val wantsBackground
        get() = isConnected || state == LinkState.CONNECTING ||
            (settings.autoReconnect && !userDisconnected && settings.lastScooterAddress != null && hasPermissions())

    /** Set by the app from the activity lifecycle; slows UI publishing in the background. */
    var isForeground = true
    /** Every decoded data frame (trip recording). */
    var onTelemetry: ((ScooterTelemetry) -> Unit)? = null
    /** The Cobo data link went away (ignition off, out of range, Bluetooth off). */
    var onDataLinkEnded: (() -> Unit)? = null
    /** Link state changed (the app starts/stops its foreground service). */
    var onStateChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    // Central side (PMP3 link, or the Cobo key link).
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var session: PMP3Session? = null
    private var keyFetch: CoboKeyFetch? = null
    private val queue = ArrayDeque<Bytes>()
    private var writeInFlight = false
    private var pmp3KeepAlive: Job? = null
    private var scanStop: Job? = null

    // Server side (Cobo data link).
    private var server: BluetoothGattServer? = null
    private var indicateChar: BluetoothGattCharacteristic? = null
    private var coboServiceAdded = false
    private var coboAnswer: Bytes? = null
    private var serverSession: CoboServerSession? = null
    private var scooterDevice: BluetoothDevice? = null
    private var scooterSubscribed = false
    private var awaitingScooter = false
    private var advertising = false
    private val indications = ArrayDeque<Bytes>()
    private var indicationInFlight = false
    private var preparedBuffer = mutableListOf<Int>()
    private var serverWatchdog: Job? = null
    private var coboStream: Job? = null
    private var lastLoggedDash: Bytes? = null
    private var lastCanLog = 0L

    private var lastDash: Bytes? = null
    private var stopDashPending = false
    private var userDisconnected = false
    private var autoConnectOnDiscovery = false
    private var latestTelemetry: ScooterTelemetry? = null
    private var lastTelemetryPublish = 0L
    private var popup: Bytes? = null
    private var popupTicksLeft = 0
    private val lastWarning = mutableMapOf<Warning, Long>()
    private var lowChargeSince: Long? = null
    /** Keys and auth answers seen this session, removed from logs posted publicly. */
    private val logSecrets = mutableSetOf<Bytes>()

    private fun changeState(s: LinkState) {
        if (state == s) return
        state = s
        onStateChanged?.invoke()
    }

    // Permissions & adapter state

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    append(L("Bluetooth bật", "Bluetooth on"))
                    changeState(LinkState.IDLE)
                    ensureStarted()
                    reconnectIfRemembered()
                }
                BluetoothAdapter.STATE_OFF -> {
                    coboServiceAdded = false
                    server = null
                    endServerLink(reconnect = false)
                    resetCentralLink()
                    gatt = null
                    changeState(LinkState.OFF)
                }
            }
        }
    }

    /** Opens the GATT server and starts auto-reconnect; call again after permissions were granted. */
    fun ensureStarted() {
        when {
            manager == null || adapter == null -> changeState(LinkState.OFF)
            !hasPermissions() -> changeState(LinkState.UNAUTHORIZED)
            adapter?.isEnabled != true -> changeState(LinkState.OFF)
            else -> {
                if (state == LinkState.UNAUTHORIZED || state == LinkState.OFF) changeState(LinkState.IDLE)
                if (server == null) {
                    append(L("Khởi tạo Bluetooth — Android ${Build.VERSION.RELEASE}, máy ${Build.MODEL}", "Bluetooth init — Android ${Build.VERSION.RELEASE}, device ${Build.MODEL}"))
                    openServer()
                    reconnectIfRemembered()
                }
            }
        }
    }

    // Public actions

    fun startScan() {
        ensureStarted()
        val scanner = adapter?.bluetoothLeScanner
        if (!hasPermissions() || scanner == null) return
        discovered.clear()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, scanSettings, scanCallback) }
            .onFailure { append(L("⚠️ Không quét được: ${it.message}", "⚠️ Scan failed: ${it.message}")); return }
        changeState(LinkState.SCANNING)
        append(L("Bắt đầu quét", "Scan started"))
        // Android throttles long scans; 30 s is plenty to find a scooter next to the phone.
        scanStop?.cancel()
        scanStop = scope.launch {
            delay(30_000)
            stopScan()
        }
    }

    /** One-tap connect: the remembered scooter, otherwise the first Vespa found by a scan. */
    fun quickConnect() {
        ensureStarted()
        userDisconnected = false
        if (isConnected || state == LinkState.CONNECTING || !hasPermissions()) return
        val remembered = settings.lastScooterAddress
        if (remembered != null && adapter?.isEnabled == true) {
            connect(remembered)
            return
        }
        autoConnectOnDiscovery = true
        startScan()
    }

    fun stopScan() {
        scanStop?.cancel()
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (state == LinkState.SCANNING) changeState(LinkState.IDLE)
    }

    fun connect(address: String, autoConnect: Boolean = false) {
        val a = adapter ?: return
        if (!hasPermissions()) return
        stopScan()
        if (awaitingScooter || serverSession != null) {
            // A second key link while the data link runs makes the scooter drop both.
            append(L("Đang có link dữ liệu với xe — bỏ qua lệnh kết nối (bấm Ngắt trước nếu muốn làm lại)", "Data link already active — connect ignored (tap Disconnect first to start over)"))
            return
        }
        if (gatt?.device?.address == address && state in setOf(LinkState.CONNECTING, LinkState.DISCOVERING, LinkState.HANDSHAKING, LinkState.READY)) {
            append(L("Đang kết nối / đã kết nối — bỏ qua lệnh kết nối trùng", "Already connecting/connected — duplicate connect ignored"))
            return
        }
        userDisconnected = false
        endServerLink(reconnect = false)
        closeGatt()
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        changeState(LinkState.CONNECTING)
        append(L("Kết nối tới ${device.name ?: address}", "Connecting to ${device.name ?: address}"))
        // autoConnect = true waits in the background until the scooter is in range (like a pending iOS connect).
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnected = true
        autoConnectOnDiscovery = false
        closeGatt()
        endServerLink(reconnect = false)
        changeState(LinkState.IDLE)
    }

    /** Next connection starts a brand-new pairing (the TFT may ask to confirm). */
    fun forgetPairing() {
        (gatt?.device?.address ?: settings.lastScooterAddress)?.let { settings.setPairing(it, null) }
        settings.lastScooterAddress = null
        pairing = null
        append(L("Đã xoá thông tin ghép đôi", "Pairing info cleared"))
        onStateChanged?.invoke()
    }

    /** `payload` is the bare 0xC6 dash packet; PMP3 adds its 55 01 05 header, Cobo indicates it as-is. */
    fun sendDash(payload: Bytes) {
        lastDash = payload
        if (linkProtocol == LinkProtocol.COBO && serverSession == null) return // the stream picks it up once ready
        if (serverSession != null) {
            // A newer dash packet replaces any still queued.
            indications.removeAll(::isDash)
            indicate(payload)
        } else {
            queue.removeAll { it.size > 4 && it.subList(0, 4) == PMP3.dashHeader + PMP3.DASH_TAG }
            enqueue(PMP3.dashHeader + payload)
        }
    }

    /** Lab test arrow, encoded the way the connected scooter expects. */
    fun sendTestDash(icon: Int, distance: Int, road: String) {
        if (serverSession != null || linkProtocol == LinkProtocol.COBO) {
            sendDash(Cobo.dashPayload(icon, distance, distance + 1500, 5, road))
        } else {
            sendDash(PMP3.dashPacket(icon, distance, distance + 1500, 5, road, dashOptions, includeHeader = false))
        }
    }

    fun sendRaw(bytes: Bytes) = if (serverSession != null) indicate(bytes) else enqueue(bytes)

    fun clearLog() = log.clear()

    /** Writes the log to the cache (shared through the FileProvider). */
    fun exportLog(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "vespa-bluetooth-log.txt").apply { writeText(logText(redacted = false)) }
    }

    /**
     * `redacted`: for a public GitHub issue — no Bluetooth addresses, pairing keys, auth answers or
     * names of other people's devices.
     */
    fun logText(redacted: Boolean): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val lines = mutableListOf(
            "VesNavi — Bluetooth log",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
            "Scooter: ${connectedName ?: "-"} · state: $state · protocol: ${linkProtocol?.title ?: "-"} · fw: ${firmwareInfo ?: "-"}",
            "Cobo: answer=${coboAnswer?.let { Hex.encode(it, "") } ?: "-"} · data link: ${serverSession?.stage ?: "-"}",
            "Pairing: id=${pairing?.deviceID?.let { Hex.encode(it, "") } ?: "-"} key=${pairing?.key?.let { Hex.encode(it, "") } ?: "-"}",
            "", "GATT:",
        )
        lines += gattTable
        lines += listOf("", "LOG:")
        lines += log.map { "${f.format(Date(it.time))}  ${it.text}" }
        val text = lines.joinToString("\n")
        if (!redacted) return text
        val strangers = discovered.filter { !it.isLikelyVespa && it.name != "(không tên)" && it.name != "(no name)" }.map { it.name }
        return BugReport.redact(text, logSecrets.flatMap { BugReport.hexVariants(it) } + strangers)
    }

    // Dashboard sink

    fun send(state: NavigationState) {
        val text = if (state.hasArrived) L("Đến nơi", "Arrived")
        else "${DistanceFormatter.text(state.distanceToManeuver, appLanguage)} · ${state.instruction}"
        lastMessage = if (isReady) text else L("$text (xe chưa kết nối)", "$text (scooter not connected)")
        if (!isReady) return
        sendDash(
            if (serverSession != null) Cobo.dashPayload(state, appLanguage)
            else PMP3.dashPacket(state, dashOptions, includeHeader = false),
        )
    }

    fun clear() {
        lastMessage = null
        // Cobo: tell the TFT the route ended (06 03 00) once, then fall back to the idle dash.
        if (lastDash != null && serverSession != null) stopDashPending = true
        lastDash = null
    }

    /** Pop-up on the TFT (two lines, ASCII, ≤ 20 chars each). Cobo data link only. */
    fun showPopup(line1: String, line2: String) {
        if (serverSession?.stage != CoboServerSession.Stage.READY) {
            append(L("⚠️ Popup chỉ gửi được khi xe ✅ Sẵn sàng", "⚠️ Pop-ups need the scooter to be ✅ Ready"))
            return
        }
        popup = Cobo.popupPayload(line1, line2)
        popupTicksLeft = Cobo.POPUP_REPEATS
        append("Popup: $line1 / $line2")
    }

    // Internals (central side)

    private fun append(text: String) {
        log += LogLine(System.currentTimeMillis(), text)
        // Trim in batches, not on every line.
        if (log.size > 2500) log.subList(0, log.size - 2000).clear()
    }

    private fun enqueue(bytes: Bytes) {
        if (writeChar == null) {
            append(L("⚠️ Chưa có kênh ghi — bỏ qua ${Hex.encode(bytes)}", "⚠️ No write channel yet — dropped ${Hex.encode(bytes)}"))
            return
        }
        queue.addLast(bytes)
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (writeInFlight || queue.isEmpty()) return
        val bytes = queue.removeFirst()
        writeInFlight = true
        append("TX ${Hex.encode(bytes)}")
        if (!write(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            writeInFlight = false
            append(L("⚠️ Ghi thất bại", "⚠️ Write failed"))
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: Bytes, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes.toByteArray(), type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = bytes.toByteArray()
            g.writeCharacteristic(c)
        }

    private fun apply(out: PMP3Session.Output) {
        out.events.forEach(::handlePmp3)
        out.writes.forEach(::enqueue)
    }

    private fun handlePmp3(event: PMP3Session.Event) {
        when (event) {
            is PMP3Session.Event.PairingChanged -> {
                pairing = event.pairing
                event.pairing.key?.let { logSecrets.add(it) }
                gatt?.device?.address?.let { settings.setPairing(it, Persistence.pairing(event.pairing)) }
                append(L("Lưu pairing", "Pairing saved") + ": id=${Hex.encode(event.pairing.deviceID, "")} key=${event.pairing.key?.let { Hex.encode(it, "") } ?: "-"}")
            }
            PMP3Session.Event.NewPairingRequested ->
                append(L("🔑 Xe yêu cầu ghép đôi mới — nếu màn hình xe hỏi, hãy bấm xác nhận", "🔑 Scooter asks for a new pairing — confirm on the TFT if asked"))
            is PMP3Session.Event.AuthResult -> append(
                if (event.success) L("Xác thực: OK", "Authentication: OK")
                else L("Xác thực: thất bại (có thể cần 'Quên ghép đôi' rồi kết nối lại)", "Authentication failed (try 'Forget pairing' and connect again)"),
            )
            PMP3Session.Event.Authenticated -> append(L("✅ Xe chấp nhận thiết bị", "✅ Scooter accepted the phone"))
            is PMP3Session.Event.Ready -> {
                changeState(if (event.ready) LinkState.READY else LinkState.HANDSHAKING)
                append(if (event.ready) L("✅ Màn hình sẵn sàng nhận dẫn đường", "✅ TFT ready for navigation") else L("Màn hình chưa sẵn sàng (45 … 00)", "TFT not ready (45 … 00)"))
                if (event.ready) startPmp3KeepAlive()
            }
            is PMP3Session.Event.Firmware -> {
                firmwareInfo = "variant ${event.variant} · fw ${event.firmware} · hw ${event.hardware}"
                append("Firmware: $firmwareInfo")
            }
            is PMP3Session.Event.Button -> append(L("Nút trên xe: ${event.code}", "Scooter button: ${event.code}"))
            is PMP3Session.Event.Unhandled -> append(L("(chưa xử lý) ${Hex.encode(event.bytes)}", "(unhandled) ${Hex.encode(event.bytes)}"))
        }
    }

    private fun startPmp3KeepAlive() {
        pmp3KeepAlive?.cancel()
        pmp3KeepAlive = scope.launch {
            delay(3_000)
            if (!isReady) return@launch
            enqueue(PMP3Session.heartbeat)
            // The dash is refreshed several times a second by the reference client; resend each second.
            while (isActive && isReady) {
                delay(1_000)
                val dash = lastDash
                if (dash != null && queue.isEmpty()) sendDash(dash)
            }
        }
    }

    /** Clears the central link only; a running Cobo data link is unaffected. */
    private fun resetCentralLink() {
        pmp3KeepAlive?.cancel()
        pmp3KeepAlive = null
        session = null
        keyFetch = null
        writeChar = null
        queue.clear()
        writeInFlight = false
        gattTable.clear()
    }

    private fun closeGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        resetCentralLink()
        connectedName = null
    }

    private fun reconnectIfRemembered(force: Boolean = false) {
        val address = settings.lastScooterAddress ?: return
        if (!(settings.autoReconnect || force) || userDisconnected || awaitingScooter || serverSession != null) return
        if (!hasPermissions() || adapter?.isEnabled != true || gatt != null) return
        append(L("Tự kết nối lại (chờ xe bật)", "Auto-reconnect (waiting for the scooter)"))
        connect(address, autoConnect = true)
    }

    private fun isDash(b: Bytes) = b.size > 6 && b.first() == PMP3.DASH_TAG

    private fun isLikelyVespa(name: String, services: List<String>): Boolean {
        val n = name.uppercase()
        return PMP3.SERVICE_UUID in services || Cobo.SERVICE_UUID in services ||
            listOf("VESPA", "PIAGGIO", "BT-ROUTER", "MIA", "PMP").any { it in n }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val advName = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: ""
            val services = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
            val rssi = result.rssi
            main.post { onDiscovered(address, advName, services, rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                append(L("⚠️ Quét lỗi $errorCode", "⚠️ Scan error $errorCode"))
                if (state == LinkState.SCANNING) changeState(LinkState.IDLE)
            }
        }
    }

    private fun onDiscovered(address: String, advName: String, services: List<String>, rssi: Int) {
        val item = Discovered(address, advName.ifEmpty { L("(không tên)", "(no name)") }, rssi, services, isLikelyVespa(advName, services))
        val i = discovered.indexOfFirst { it.address == address }
        if (i >= 0) {
            discovered[i] = item
        } else {
            discovered += item
            append(L("Thấy: ${item.name} RSSI $rssi dịch vụ [${services.joinToString(",")}]", "Found: ${item.name} RSSI $rssi services [${services.joinToString(",")}]"))
        }
        // Auto-connect only on a sure match (known service or the scooter's own name).
        val sure = Cobo.SERVICE_UUID in services || PMP3.SERVICE_UUID in services ||
            listOf("BT-ROUTER", "BT ROUTER", "VESPA").any { it in advName.uppercase() }
        if (autoConnectOnDiscovery && sure) {
            autoConnectOnDiscovery = false
            connect(address)
        }
        val sorted = discovered.sortedWith(compareByDescending<Discovered> { it.isLikelyVespa }.thenByDescending { it.rssi })
        discovered.clear()
        discovered += sorted
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    runCatching { g.close() }
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedName = runCatching { g.device.name }.getOrNull()
                    changeState(LinkState.DISCOVERING)
                    resetCentralLink()
                    settings.lastScooterAddress = g.device.address
                    append(L("Đã kết nối ${connectedName ?: ""} — đọc toàn bộ dịch vụ", "Connected ${connectedName ?: ""} — reading all services"))
                    // The scooter asks for 131; the 23-byte key reply needs at least 26.
                    if (!g.requestMtu(185)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    gatt = null
                    resetCentralLink()
                    connectedName = null
                    if (awaitingScooter || serverSession != null) {
                        // Expected: we closed the Cobo key link ourselves; the data link takes over.
                        append(L("Đã đóng link lấy khoá (mã $status) — chờ xe kết nối vào điện thoại", "Key link closed (status $status) — waiting for the scooter to connect back"))
                        return@post
                    }
                    append(L("Mất kết nối (mã $status)", "Disconnected (status $status)"))
                    changeState(LinkState.IDLE)
                    reconnectIfRemembered()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                append("MTU $mtu")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { servicesDiscovered(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid.toString().lowercase()
            main.post { notificationsEnabled(uuid, status) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid.toString().lowercase()
            val bytes = value.toBytes()
            main.post { received(uuid, bytes) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val uuid = characteristic.uuid.toString().lowercase()
            val bytes = (characteristic.value ?: ByteArray(0)).toBytes()
            main.post { received(uuid, bytes) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) append(L("Lỗi ghi: $status", "Write error: $status"))
                writeInFlight = false
                pump()
            }
        }
    }

    private fun servicesDiscovered(g: BluetoothGatt) {
        val services = g.services
        append(L("Dịch vụ: ", "Services: ") + services.joinToString(", ") { it.uuid.toString() })
        val ids = services.map { it.uuid.toString().lowercase() }
        linkProtocol = when {
            PMP3.SERVICE_UUID in ids -> LinkProtocol.PMP3
            Cobo.SERVICE_UUID in ids -> LinkProtocol.COBO
            else -> null
        }
        for (s in services) {
            for (c in s.characteristics) {
                val line = "${s.uuid} / ${c.uuid} [${describe(c.properties)}]"
                gattTable += line
                append("GATT $line")
            }
        }
        val proto = linkProtocol
        if (proto == null) {
            changeState(LinkState.UNKNOWN_SERVICE)
            append(L("⚠️ Không có dịch vụ PMP3 (0x1815) hay Cobo — chưa hỗ trợ xe này", "⚠️ No PMP3 (0x1815) or Cobo service — this scooter isn't supported yet"))
            return
        }
        append(L("Giao thức: ${proto.title}", "Protocol: ${proto.title}"))
        if (proto == LinkProtocol.COBO && !coboServiceAdded) append(L("⚠️ Dịch vụ máy chủ Cobo trên điện thoại chưa sẵn sàng", "⚠️ Cobo GATT service on the phone not ready"))
        val (serviceId, writeId, notifyId) = if (proto == LinkProtocol.PMP3) {
            Triple(PMP3.SERVICE_UUID, PMP3.WRITE_CHARACTERISTIC_UUID, PMP3.NOTIFY_CHARACTERISTIC_UUID)
        } else {
            Triple(Cobo.SERVICE_UUID, Cobo.WRITE_CHARACTERISTIC_UUID, Cobo.NOTIFY_CHARACTERISTIC_UUID)
        }
        val service = g.getService(UUID.fromString(serviceId)) ?: return
        writeChar = service.getCharacteristic(UUID.fromString(writeId))
        // Create the key-link parser before subscribing so the first notification can't be missed.
        if (proto == LinkProtocol.COBO) keyFetch = CoboKeyFetch()
        val notify = service.getCharacteristic(UUID.fromString(notifyId))
        if (notify == null) {
            append(L("⚠️ Không thấy kênh notify $notifyId", "⚠️ Notify characteristic $notifyId not found"))
            return
        }
        changeState(LinkState.HANDSHAKING)
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) {
            append(L("⚠️ Kênh notify không có CCCD", "⚠️ Notify characteristic has no CCCD"))
            return
        }
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        writeDescriptor(g, cccd, value)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private fun notificationsEnabled(uuid: String, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            append(L("Lỗi bật notify: $status", "Notify error: $status"))
            return
        }
        if (linkProtocol == LinkProtocol.COBO && uuid == Cobo.NOTIFY_CHARACTERISTIC_UUID) {
            append(L("Link lấy khoá: đã bật notify — chờ xe gửi thử thách", "Key link: notify on — waiting for the challenge"))
            return
        }
        if (uuid != PMP3.NOTIFY_CHARACTERISTIC_UUID) return
        val address = gatt?.device?.address ?: return
        val stored = settings.pairing(address)?.let(Persistence::pairing)
        pairing = stored
        stored?.key?.let { logSecrets.add(it) }
        val s = PMP3Session(stored, Build.MODEL)
        session = s
        append(L("Notify 2A5A bật — bắt đầu bắt tay", "Notify 2A5A on — starting handshake"))
        apply(s.start())
    }

    private fun received(uuid: String, bytes: Bytes) {
        append("RX ${Hex.encode(bytes)}")
        val k = keyFetch
        val s = session
        if (uuid == Cobo.NOTIFY_CHARACTERISTIC_UUID && k != null) {
            handleKey(k.handle(bytes))
        } else if (uuid == PMP3.NOTIFY_CHARACTERISTIC_UUID && s != null) {
            apply(s.handle(bytes))
        }
    }

    private fun describe(p: Int): String = listOfNotNull(
        "read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
        "write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
        "writeNoResp".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
        "notify".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
        "indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
    ).joinToString(",")

    // Internals (Cobo key link → data link)

    private fun handleKey(event: CoboKeyFetch.Event) {
        when (event) {
            is CoboKeyFetch.Event.Challenge -> {
                append(L("🔑 Link lấy khoá: xe gửi thử thách ${Hex.encode(event.bytes)}", "🔑 Key link: challenge ${Hex.encode(event.bytes)}"))
                val g = gatt
                val c = writeChar
                if (g == null || c == null) {
                    append(L("⚠️ Chưa có kênh ghi 9B00 — không trả lời được thử thách", "⚠️ No 9B00 write channel — can't answer the challenge"))
                    return
                }
                // Answered with a write command (no response) right after the challenge.
                val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                append(L("TX ${Hex.encode(event.reply)} — trả lời thử thách, chờ xe gửi câu trả lời 6 byte", "TX ${Hex.encode(event.reply)} — challenge reply, waiting for the 6-byte answer"))
                if (!write(g, c, event.reply, type)) append(L("⚠️ Ghi trả lời thất bại", "⚠️ Reply write failed"))
            }
            is CoboKeyFetch.Event.Answer -> {
                coboAnswer = event.bytes
                logSecrets.add(event.bytes)
                append(L("🔑 Link lấy khoá: nhận câu trả lời ${Hex.encode(event.bytes)}", "🔑 Key link: answer ${Hex.encode(event.bytes)}"))
                beginServerPhase()
            }
            is CoboKeyFetch.Event.Ignored -> append(L("(link lấy khoá, bỏ qua) ${Hex.encode(event.bytes)}", "(key link, ignored) ${Hex.encode(event.bytes)}"))
        }
    }

    /** Advertise the Cobo service, then close the key link ourselves; the scooter connects back to our GATT server. */
    private fun beginServerPhase() {
        if (server == null || !coboServiceAdded) {
            append(L("⚠️ Điện thoại chưa sẵn sàng làm máy chủ Bluetooth — đóng link và thử lại", "⚠️ Phone GATT server not ready — closing the link, will retry"))
            openServer()
            closeGatt()
            changeState(LinkState.IDLE)
            reconnectIfRemembered(force = true)
            return
        }
        awaitingScooter = true
        changeState(LinkState.WAITING_FOR_SCOOTER)
        startAdvertising()
        append(L("Phát quảng bá dịch vụ Cobo và đóng link lấy khoá", "Advertising the Cobo service and closing the key link"))
        closeGatt()
        serverWatchdog?.cancel()
        serverWatchdog = scope.launch {
            // Re-run the key link ~10 s after a scooter that never connected back.
            delay(10_000)
            if (!awaitingScooter) return@launch
            append(L("⚠️ Xe không kết nối vào điện thoại sau 10 s — thử lại từ đầu", "⚠️ Scooter didn't connect back within 10 s — starting over"))
            endServerLink(reconnect = true)
        }
    }

    private fun openServer() {
        if (server != null || !hasPermissions() || adapter?.isEnabled != true) return
        val s = manager?.openGattServer(context, serverCallback) ?: run {
            append(L("⚠️ Không mở được máy chủ GATT", "⚠️ Couldn't open the GATT server"))
            return
        }
        server = s
        val write = BluetoothGattCharacteristic(
            UUID.fromString(Cobo.WRITE_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val indicate = BluetoothGattCharacteristic(
            UUID.fromString(Cobo.NOTIFY_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        indicate.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        val service = BluetoothGattService(UUID.fromString(Cobo.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(write)
        service.addCharacteristic(indicate)
        indicateChar = indicate
        if (!s.addService(service)) append(L("⚠️ Không đăng ký được dịch vụ Cobo", "⚠️ Couldn't register the Cobo service"))
    }

    private fun startAdvertising() {
        if (advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            append(L("⚠️ Điện thoại không hỗ trợ phát quảng bá BLE — không dùng được xe PMP2", "⚠️ This phone can't advertise over BLE — PMP2 scooters won't work"))
            return
        }
        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(Cobo.SERVICE_UUID)))
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.startAdvertising(advSettings, data, advertiseCallback) }
            .onSuccess { advertising = true }
            .onFailure { append(L("⚠️ Lỗi phát quảng bá: ${it.message}", "⚠️ Advertising error: ${it.message}")) }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        if (hasPermissions()) runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post { append(L("Đang phát quảng bá dịch vụ Cobo", "Advertising the Cobo service")) }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                advertising = false
                append(L("⚠️ Lỗi phát quảng bá: $errorCode", "⚠️ Advertising error: $errorCode"))
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                coboServiceAdded = status == BluetoothGatt.GATT_SUCCESS
                append(
                    if (coboServiceAdded) L("Đã đăng ký dịch vụ Cobo trên điện thoại (chế độ máy chủ)", "Cobo service registered on the phone (server mode)")
                    else L("⚠️ Không đăng ký được dịch vụ Cobo: $status", "⚠️ Couldn't register the Cobo service: $status"),
                )
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    append(L("Thiết bị kết nối vào máy chủ: ${device.address}", "Device connected to the server: ${device.address}"))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == scooterDevice?.address) {
                    append(L("Xe đã ngắt link dữ liệu — làm lại từ link lấy khoá", "Scooter dropped the data link — starting again from the key link"))
                    endServerLink(reconnect = true)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post {
                append(L("Link dữ liệu: MTU $mtu (gói tối đa ${mtu - 3} byte)", "Data link: MTU $mtu (max packet ${mtu - 3} bytes)"))
                if (mtu - 3 < 128) append(L("⚠️ Gói tối đa < 128 byte — gói 0x28 (128 byte) sẽ không gửi được", "⚠️ Max packet < 128 bytes — the 0x28 packet (128 bytes) won't fit"))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val isCobo = descriptor.characteristic.uuid.toString().lowercase() == Cobo.NOTIFY_CHARACTERISTIC_UUID
            val on = value.any { it.toInt() != 0 }
            main.post { if (isCobo) { if (on) subscribed(device) else unsubscribed() } }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (scooterSubscribed) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            // Every write request must be answered, or the scooter's ATT transaction times out.
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid.toString().lowercase() != Cobo.WRITE_CHARACTERISTIC_UUID) return
            val bytes = value.toBytes()
            main.post {
                if (preparedWrite) preparedBuffer += bytes else serverWrite(device, bytes)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            main.post {
                val bytes = preparedBuffer.toList()
                preparedBuffer = mutableListOf()
                if (execute && bytes.isNotEmpty()) serverWrite(device, bytes)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                indicationInFlight = false
                pumpIndications()
            }
        }
    }

    private fun subscribed(device: BluetoothDevice) {
        serverWatchdog?.cancel()
        awaitingScooter = false
        stopAdvertising()
        scooterDevice = device
        scooterSubscribed = true
        peakRPM = 0
        if (serverSession == null) serverSession = CoboServerSession(coboAnswer)
        if (state != LinkState.READY) changeState(LinkState.HANDSHAKING)
        append(L("Xe đã kết nối vào điện thoại (link dữ liệu)", "Scooter connected to the phone (data link)"))
        pumpIndications()
        serverWatchdog = scope.launch {
            delay(8_000)
            val s = serverSession ?: return@launch
            if (s.stage != CoboServerSession.Stage.READY) {
                append(L("⚠️ Xe dừng giữa chừng khi khởi tạo (${s.stage}) — tắt/bật chìa khoá xe rồi thử lại", "⚠️ Scooter stopped during init (${s.stage}) — cycle the ignition and try again"))
            }
        }
    }

    private fun unsubscribed() {
        if (!scooterSubscribed) return
        append(L("Xe đã ngắt link dữ liệu — làm lại từ link lấy khoá", "Scooter dropped the data link — starting again from the key link"))
        endServerLink(reconnect = true)
    }

    private fun serverWrite(device: BluetoothDevice, bytes: Bytes) {
        if (scooterDevice == null) scooterDevice = device
        val s = serverSession ?: CoboServerSession(coboAnswer).also { serverSession = it }
        val out = s.handleWrite(bytes)
        if (out.events.none { it is CoboServerSession.Event.CanData }) append("RX⇠ ${Hex.encode(bytes)}")
        out.events.forEach(::handleServer)
        out.indications.forEach(::indicate)
    }

    private fun handleServer(event: CoboServerSession.Event) {
        when (event) {
            CoboServerSession.Event.AnswerSent -> append(L("🔑 Xe hỏi câu trả lời → đã gửi, bắt đầu khởi tạo PMP2", "🔑 Scooter asked for the answer → sent, starting PMP2 init"))
            CoboServerSession.Event.MissingAnswer -> {
                append(L("⚠️ Xe hỏi câu trả lời nhưng link lấy khoá chưa cung cấp — sẽ thử lại", "⚠️ Scooter asked for the answer but the key link has none — will retry"))
                endServerLink(reconnect = true)
            }
            is CoboServerSession.Event.Command -> append(L("Xe → lệnh DQ", "Scooter → DQ command") + " 0x%02X [%s]".format(event.cmd, Hex.encode(event.payload)))
            is CoboServerSession.Event.CanData -> {
                updateTelemetry(event.bytes)
                val now = System.currentTimeMillis()
                if (now - lastCanLog > 5_000) {
                    lastCanLog = now
                    append(L("Xe → dữ liệu CAN ${Hex.encode(event.bytes)} (chỉ ghi mỗi 5 s)", "Scooter → CAN data ${Hex.encode(event.bytes)} (logged every 5 s)"))
                }
            }
            CoboServerSession.Event.Ready -> {
                changeState(LinkState.READY)
                append(L("✅ Màn hình sẵn sàng nhận dẫn đường (PMP2)", "✅ TFT ready for navigation (PMP2)"))
                startCoboStream()
            }
            is CoboServerSession.Event.Unhandled -> append(L("(chưa xử lý) ${Hex.encode(event.bytes)}", "(unhandled) ${Hex.encode(event.bytes)}"))
        }
    }

    private fun indicate(bytes: Bytes) {
        indications.addLast(bytes)
        pumpIndications()
    }

    /** One indication at a time: the next goes out from onNotificationSent (the confirmation). */
    @Suppress("DEPRECATION")
    private fun pumpIndications() {
        val s = server ?: return
        val ch = indicateChar ?: return
        val device = scooterDevice ?: return
        if (!scooterSubscribed || indicationInFlight) return
        val next = indications.firstOrNull() ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(device, ch, true, next.toByteArray()) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.value = next.toByteArray()
            s.notifyCharacteristicChanged(device, ch, true)
        }
        if (!ok) return // retried on the next stream tick
        indications.removeFirst()
        indicationInFlight = true
        if (!isDash(next)) {
            append("TX⇢ ${Hex.encode(next)}")
        } else if (next != lastLoggedDash) {
            lastLoggedDash = next
            append("TX⇢ dash ${Hex.encode(next)}")
        }
    }

    /** Dash every ~0.5 s; the two 0x27 reads start ~2 s after init and repeat every ~10 s until answered. */
    private fun startCoboStream() {
        coboStream?.cancel()
        coboStream = scope.launch {
            delay(2_000)
            var tick = 0
            while (isActive) {
                val s = serverSession ?: return@launch
                if (s.stage != CoboServerSession.Stage.READY) return@launch
                if (tick % 20 == 0) s.readRequestFrames().forEach(::indicate)
                indications.removeAll(::isDash)
                val p = popup
                when {
                    p != null && popupTicksLeft > 0 -> {
                        // The pop-up replaces the dash packet for a few packets.
                        popupTicksLeft--
                        if (popupTicksLeft == 0) popup = null
                        indications.removeAll { it.firstOrNull() == 0xC2 }
                        indicate(p)
                    }
                    stopDashPending -> {
                        stopDashPending = false
                        indicate(Cobo.stopDash)
                    }
                    else -> indicate(lastDash ?: idleDashPacket())
                }
                // A lost confirmation must not stall the link forever.
                if (indicationInFlight && tick % 4 == 3) indicationInFlight = false
                tick++
                delay(500)
            }
        }
    }

    private fun idleDashPacket(): Bytes {
        val t = latestTelemetry
        if (!settings.idleText || t == null) return Cobo.idleDash
        val text = Cobo.infoText(t, appLanguage) ?: return Cobo.idleDash
        return Cobo.infoDash(text, navigationScreen = true)
    }

    private fun checkWarnings(t: ScooterTelemetry) {
        if (!settings.warningsEnabled) return
        val c = t.coolantC
        if (c != null && t.engineRunning && c >= 105) warn(Warning.COOLANT_HOT, L("Nhiet do cao", "Engine hot"), L("Nuoc mat ${c}C", "Coolant ${c}C"))
        // A running engine should charge at ~13.5–14.5 V; below 12.5 V for 30 s means it isn't.
        val v = t.batteryVolts
        if (v != null && t.engineRunning && v < 12.5) {
            val since = lowChargeSince ?: System.currentTimeMillis().also { lowChargeSince = it }
            if (System.currentTimeMillis() - since > 30_000) warn(Warning.NOT_CHARGING, L("Ac quy khong sac", "Not charging"), "%.1fV".format(Locale.US, v))
        } else {
            lowChargeSince = null
        }
        if (v != null && !t.engineRunning && v < 11.8) warn(Warning.BATTERY_LOW, L("Ac quy yeu", "Battery low"), "%.1fV".format(Locale.US, v))
    }

    /** At most one pop-up per warning every 5 minutes. */
    private fun warn(w: Warning, line1: String, line2: String) {
        val now = System.currentTimeMillis()
        if (now - (lastWarning[w] ?: 0L) < 300_000) return
        lastWarning[w] = now
        showPopup(line1, line2)
    }

    private fun updateTelemetry(frame: Bytes) {
        val t = CoboTelemetry.parse(frame, latestTelemetry ?: ScooterTelemetry()) ?: return
        latestTelemetry = t
        // ~4 frames a second: trips and warnings use every frame, the UI only a couple a second
        // (and almost none in the background) — every publish recomposes the screens that read it.
        val now = System.currentTimeMillis()
        val interval = if (isForeground) 500L else 5_000L
        if (telemetry == null || now - lastTelemetryPublish >= interval) {
            lastTelemetryPublish = now
            telemetryAt = now
            if (t != telemetry) telemetry = t
        }
        onTelemetry?.invoke(t)
        val rpm = t.rpm
        if (rpm != null && rpm > peakRPM) peakRPM = rpm
        checkWarnings(t)
    }

    private fun endServerLink(reconnect: Boolean) {
        serverWatchdog?.cancel()
        serverWatchdog = null
        coboStream?.cancel()
        coboStream = null
        stopAdvertising()
        val wasCobo = awaitingScooter || serverSession != null
        if (telemetry != null) onDataLinkEnded?.invoke()
        scooterDevice?.let { d -> runCatching { server?.cancelConnection(d) } }
        awaitingScooter = false
        serverSession = null
        scooterDevice = null
        scooterSubscribed = false
        indications.clear()
        indicationInFlight = false
        preparedBuffer = mutableListOf()
        coboAnswer = null
        lastLoggedDash = null
        telemetry = null
        latestTelemetry = null
        telemetryAt = null
        popup = null
        popupTicksLeft = 0
        lowChargeSince = null
        if (wasCobo && state in setOf(LinkState.WAITING_FOR_SCOOTER, LinkState.HANDSHAKING, LinkState.READY)) changeState(LinkState.IDLE)
        // Part of the handshake itself, so retry even when auto-reconnect is off.
        if (reconnect) reconnectIfRemembered(force = true)
    }

    // Last in the class: the callbacks above must exist before Bluetooth is touched.
    init {
        ContextCompat.registerReceiver(context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Only touch Bluetooth once the rider has paired before (or opens the scooter screen).
        if (settings.lastScooterAddress != null) ensureStarted()
    }

    companion object {
        /** Client Characteristic Configuration descriptor. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
