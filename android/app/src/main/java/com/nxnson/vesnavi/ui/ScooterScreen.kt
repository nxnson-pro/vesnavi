package com.nxnson.vesnavi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.Localizer
import com.nxnson.vesnavi.VespaBLE
import com.nxnson.vesnavi.navcore.ScooterTelemetry
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** "My scooter": connection + live engine data, fuel, maintenance, trips. */
@Composable
fun ScooterScreen(model: AppModel, nav: Navigator, requestBluetooth: () -> Unit) {
    val ble = model.ble
    LaunchedEffect(Unit) { ble.ensureStarted() }
    SubScreen(L("Xe của tôi", "My scooter"), onBack = nav::pop) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionCard(ble, requestBluetooth)
            val t = ble.telemetry
            when {
                t != null -> {
                    Gauges(t)
                    Tiles(t, ble.peakRPM)
                    UpdatedLabel(ble.telemetryAt)
                }
                ble.isReady -> Text(
                    L("Đang chờ dữ liệu từ xe…", "Waiting for data from the scooter…"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally),
                )
                else -> HowTo()
            }
            FuelCard(model)
            MaintenanceCard(model, nav)
            TripsCard(model, nav)
            FloatingCard(Modifier.fillMaxWidth().clickable { nav.push(Screen.BluetoothLab) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Build, null)
                    Text(L("Bluetooth Lab (nâng cao)", "Bluetooth Lab (advanced)"), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(ble: VespaBLE, requestBluetooth: () -> Unit) {
    FloatingCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).background(if (ble.isReady) Theme.accent else Color.Gray, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.TwoWheeler, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Vespa GTS", fontWeight = FontWeight.SemiBold)
                Text(ble.state.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                ble.isConnected -> OutlinedButton(onClick = ble::disconnect, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)) {
                    Text(L("Ngắt", "Disconnect"))
                }
                ble.state == VespaBLE.LinkState.SCANNING || ble.state == VespaBLE.LinkState.CONNECTING -> CircularProgressIndicator(Modifier.size(28.dp))
                else -> Button(onClick = { if (!ble.hasPermissions()) requestBluetooth() else ble.quickConnect() }) {
                    Text(L("Kết nối", "Connect"))
                }
            }
        }
    }
}

@Composable
private fun Gauges(t: ScooterTelemetry) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RingGauge(L("Vòng tua", "RPM"), t.rpm?.toDouble(), 10_000.0, L("vòng/phút", "rpm"), if ((t.rpm ?: 0) > 8_000) Color.Red else Theme.accent, Modifier.weight(1f))
        val c = t.coolantC
        val tint = when {
            c == null -> Color.Gray
            c >= 110 -> Color.Red
            c >= 100 -> Color(0xFFFF9500)
            c < 60 -> Color(0xFF007AFF)
            else -> Theme.accent
        }
        RingGauge(L("Nước làm mát", "Coolant"), c?.toDouble(), 130.0, "°C", tint, Modifier.weight(1f))
    }
}

/** 3/4 ring gauge with the value in the middle. */
@Composable
fun RingGauge(title: String, value: Double?, max: Double, unit: String, tint: Color, modifier: Modifier = Modifier) {
    val fraction = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
    val track = MaterialTheme.colorScheme.surfaceVariant
    FloatingCard(modifier) {
        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(120.dp)) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
                drawArc(tint, 135f, 270f * fraction, false, Offset(inset, inset), arcSize, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value?.roundToInt()?.toString() ?: "–", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Tiles(t: ScooterTelemetry, peakRPM: Int) {
    val v = t.batteryVolts
    val batteryTint = when {
        v == null -> MaterialTheme.colorScheme.onSurface
        v < 11.5 -> Color.Red
        t.engineRunning && v < 12.8 -> Color(0xFFFF9500) // not charging
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tiles = buildList<Triple<String, Pair<String?, String>, Color?>> {
        add(Triple(L("Ắc quy", "Battery"), v?.let { String.format(Locale.US, "%.1f", it) } to "V", batteryTint))
        add(Triple(L("Tốc độ", "Speed"), (t.dashboardSpeedKmh ?: t.rearWheelKmh?.roundToInt())?.toString() to "km/h", null))
        add(Triple(L("Ngoài trời", "Outside"), t.ambientC?.toString() to "°C", null))
        add(Triple(L("Gió nạp", "Intake air"), t.intakeAirC?.toString() to "°C", null))
        add(Triple(L("Tay ga", "Throttle"), t.throttle?.toString() to "%", null))
        add(Triple(L("Tiêu hao", "Fuel rate"), t.fuelLitersPerHour?.let { String.format(Locale.US, "%.1f", it) } to "L/h", null))
        add(Triple("ODO", t.odometerKm?.let { "%,d".format(Localizer.locale, it) } to "km", null))
        add(Triple(L("Vòng tua cao nhất", "Peak RPM"), (if (peakRPM > 0) "$peakRPM" else null) to L("vòng/phút", "rpm"), null))
        val mil = t.milLamp
        if (mil != null && mil > 0 && t.engineRunning) add(Triple(L("Đèn báo lỗi động cơ", "Engine warning light"), L("BẬT", "ON") to "", Color.Red))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (title, value, tint) -> MetricTile(title, value.first, value.second, tint, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MetricTile(title: String, value: String?, unit: String, tint: Color? = null, modifier: Modifier = Modifier) {
    FloatingCard(modifier, padding = 14.dp) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value ?: "–", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = tint ?: MaterialTheme.colorScheme.onSurface)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun UpdatedLabel(at: Long?) {
    if (at == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    // Data arrives twice a second: "0 seconds ago" otherwise.
    Text(
        if (seconds < 3) L("Cập nhật vừa xong", "Updated just now") else L("Cập nhật $seconds giây trước", "Updated $seconds s ago"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HowTo() {
    FloatingCard(Modifier.fillMaxWidth()) {
        Text(L("Kết nối lần đầu", "First connection"), fontWeight = FontWeight.SemiBold)
        Step(1, L("Bật chìa khoá để màn hình xe sáng.", "Turn the key so the scooter display is on."))
        Step(2, L("Trên màn hình xe: Settings → Configuration → Pairing configuration.", "On the scooter display: Settings → Configuration → Pairing configuration."))
        Step(3, L("Trên điện thoại: Cài đặt → Bluetooth → ghép đôi thiết bị mới → chọn \"BT ROUTER\". Nếu được hỏi mã, nhập 0000.", "On the phone: Settings → Bluetooth → pair new device → choose \"BT ROUTER\". If asked for a code, enter 0000."))
        Step(4, L("Đóng hẳn app Vespa chính hãng (nếu có), rồi bấm Kết nối ở trên.", "Fully close the official Vespa app (if installed), then tap Connect above."))
        Text(
            L("Chỉ cần ghép đôi một lần. Những lần sau app tự kết nối khi bạn bật xe.", "You only pair once. After that the app connects by itself when you start the scooter."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).background(Theme.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
