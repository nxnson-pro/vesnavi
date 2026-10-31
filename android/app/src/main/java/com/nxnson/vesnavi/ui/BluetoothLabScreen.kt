package com.nxnson.vesnavi.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.navcore.Hex
import com.nxnson.vesnavi.navcore.PMP3
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-scooter test bench: connect, watch the handshake, push test arrows, export the log. */
@Composable
fun BluetoothLabScreen(model: AppModel, nav: Navigator, requestBluetooth: () -> Unit) {
    val ble = model.ble
    val context = LocalContext.current
    var icon by remember { mutableIntStateOf(PMP3.Icon.LEFT) }
    var distance by remember { mutableIntStateOf(200) }
    var road by remember { mutableStateOf("Lê Lợi") }
    var rawHex by remember { mutableStateOf("") }
    var line1 by remember { mutableStateOf("Xin chao") }
    var line2 by remember { mutableStateOf("VesNavi") }
    var showReport by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun test(i: Int, d: Int) {
        icon = i
        distance = d
        ble.sendTestDash(i, d, road)
    }

    SubScreen("Bluetooth Lab", onBack = nav::pop) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Text("${L("Kết nối", "Connection")}: ${ble.state.title}", fontWeight = FontWeight.SemiBold)
                    ble.connectedName?.let { Text("${L("Xe", "Scooter")}: $it") }
                    ble.linkProtocol?.let { Text("${L("Giao thức", "Protocol")}: ${it.title}") }
                    ble.firmwareInfo?.let { Text("Firmware: $it") }
                    Text("${L("Ghép đôi", "Pairing")}: ${if (ble.pairing?.key == null) L("Chưa", "None") else L("Đã có khoá", "Key stored")}")
                    if (!ble.hasPermissions()) Button(onClick = requestBluetooth) { Text(L("Cấp quyền Bluetooth", "Grant Bluetooth permission")) }
                    if (ble.isConnected) OutlinedButton(onClick = ble::disconnect) { Text(L("Ngắt kết nối", "Disconnect"), color = Color.Red) }
                    TextButton(onClick = ble::forgetPairing) { Text(L("Quên ghép đôi (ghép lại từ đầu)", "Forget pairing (pair again from scratch)"), color = Color.Red) }
                }
            }
            item {
                SectionLabel(L("Xe PMP2 (GTS Supertech) — Bluetooth cổ điển", "PMP2 scooters (GTS Supertech) — classic Bluetooth"))
                FloatingCard(Modifier.fillMaxWidth()) {
                    val router = ble.bondedRouter()
                    Text(
                        if (router != null) L("Đã ghép đôi: ${router.name ?: "BT ROUTER"}", "Paired: ${router.name ?: "BT ROUTER"}")
                        else L("Chưa thấy \"BT ROUTER\" trong thiết bị đã ghép đôi của điện thoại", "No \"BT ROUTER\" among the phone's paired devices"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(L("Kết nối xe PMP2 qua SPP", "Connect PMP2 scooters over SPP"), Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = model.settings.pmp2OverSpp, onCheckedChange = { model.settings.pmp2OverSpp = it })
                    }
                    if (router != null) {
                        Button(onClick = { ble.connectSpp(router) }) { Text(L("Kết nối SPP", "Connect over SPP")) }
                    }
                }
                Text(
                    L(
                        "Trên Android, xe PMP2 nói chuyện qua Bluetooth cổ điển (SPP): ghép đôi \"BT ROUTER\" một lần trong Cài đặt → Bluetooth (bật chế độ ghép đôi trên màn hình xe: Settings → Configuration → Pairing configuration, mã 0000), rồi bấm Kết nối. Tắt công tắc để thử đường BLE hai link như iPhone.",
                        "On Android, PMP2 scooters talk over classic Bluetooth (SPP): pair \"BT ROUTER\" once in Settings → Bluetooth (put the scooter display in pairing mode: Settings → Configuration → Pairing configuration, code 0000), then tap Connect. Turn the switch off to try the iPhone-style two-link BLE path.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionLabel(L("Tìm xe", "Find the scooter"))
                FloatingCard(Modifier.fillMaxWidth()) {
                    if (ble.state == com.nxnson.vesnavi.VespaBLE.LinkState.SCANNING) {
                        OutlinedButton(onClick = ble::stopScan) { Text(L("Dừng quét", "Stop scan")) }
                    } else {
                        Button(onClick = { if (!ble.hasPermissions()) requestBluetooth() else ble.startScan() }) { Text(L("🔍 Quét thiết bị Bluetooth", "🔍 Scan Bluetooth devices")) }
                    }
                    ble.discovered.forEach { d ->
                        Column(Modifier.fillMaxWidth().clickable { ble.connect(d.address) }.padding(vertical = 6.dp)) {
                            Text((if (d.isLikelyVespa) "🛵 " else "") + d.name)
                            Text(
                                "RSSI ${d.rssi}" + if (d.services.isEmpty()) "" else " · " + d.services.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    L(
                        "Quét BLE dùng cho xe PMP3 (dịch vụ 1815), hoặc để thử đường BLE hai link như iPhone khi đã tắt SPP ở trên (điện thoại kết nối xe để lấy khoá, tự ngắt, rồi xe kết nối ngược vào điện thoại). Thiết bị nghi là Vespa có biểu tượng 🛵. Bản Android chưa được thử trên xe thật — nếu không được, hãy báo lỗi kèm log.",
                        "The BLE scan is for PMP3 scooters (service 1815), or to try the iPhone-style two-link BLE path with SPP switched off above (the phone fetches a key, disconnects, then the scooter connects back to the phone). Likely Vespa devices are marked 🛵. The Android version hasn't been tried on a real scooter yet — if it doesn't work, report a bug with the log.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionLabel(L("Gửi thử lên màn hình", "Test on the display"))
                FloatingCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { test(PMP3.Icon.LEFT, 200) }, Modifier.weight(1f)) { Text("⬅️ 200m", fontSize = 12.sp) }
                        OutlinedButton(onClick = { test(PMP3.Icon.RIGHT, 50) }, Modifier.weight(1f)) { Text("➡️ 50m", fontSize = 12.sp) }
                        OutlinedButton(onClick = { test(PMP3.Icon.STRAIGHT, 1200) }, Modifier.weight(1f)) { Text("⬆️ 1,2km", fontSize = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { test(PMP3.Icon.U_TURN_COUNTER_CLOCKWISE, 120) }, Modifier.weight(1f)) { Text(L("↩️ Quay đầu", "↩️ U-turn"), fontSize = 12.sp) }
                        OutlinedButton(onClick = { test(PMP3.Icon.ROUNDABOUT_CCW_STRAIGHT, 300) }, Modifier.weight(1f)) { Text(L("🔄 Vòng xuyến", "🔄 Roundabout"), fontSize = 12.sp) }
                        OutlinedButton(onClick = { test(PMP3.Icon.ARRIVE, 10) }, Modifier.weight(1f)) { Text(L("🏁 Đến nơi", "🏁 Arrive"), fontSize = 12.sp) }
                    }
                    Stepper(L("Mã icon: $icon", "Icon code: $icon"), onMinus = { icon = (icon - 1).coerceAtLeast(0) }, onPlus = { icon = (icon + 1).coerceAtMost(255) })
                    Stepper(L("Khoảng cách: $distance m", "Distance: $distance m"), onMinus = { distance = (distance - 50).coerceAtLeast(0) }, onPlus = { distance = (distance + 50).coerceAtMost(20_000) })
                    OutlinedTextField(road, { road = it }, label = { Text(L("Tên đường", "Street name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(L("Kiểu chữ (tag 4, xe PMP3)", "Text style (tag 4, PMP3 scooters)"), fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PMP3.TextMode.entries.forEach { mode ->
                            val selected = ble.dashOptions.textMode == mode
                            TextButton(onClick = { ble.dashOptions = ble.dashOptions.copy(textMode = mode) }) {
                                Text(mode.name.removePrefix("MODE_"), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Theme.accent else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    TextButton(onClick = { ble.dashOptions = ble.dashOptions.copy(uppercase = !ble.dashOptions.uppercase) }) {
                        Text((if (ble.dashOptions.uppercase) "☑ " else "☐ ") + L("Chữ IN HOA", "UPPERCASE"))
                    }
                    Button(onClick = { test(icon, distance) }, enabled = ble.isConnected) { Text(L("Gửi icon $icon", "Send icon $icon")) }
                }
            }
            item {
                SectionLabel(L("Popup (thử nghiệm)", "Pop-up (experimental)"))
                FloatingCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(line1, { line1 = it }, label = { Text(L("Dòng 1", "Line 1")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(line2, { line2 = it }, label = { Text(L("Dòng 2", "Line 2")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { ble.showPopup(line1, line2) }, enabled = ble.isReady) { Text(L("Gửi popup", "Send pop-up")) }
                }
            }
            item {
                SectionLabel(L("Gửi byte thô (debug)", "Send raw bytes (debug)"))
                FloatingCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(rawHex, { rawHex = it }, placeholder = { Text("VD: 48 42") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val bytes = Hex.decode(rawHex)
                    Button(onClick = { bytes?.let(ble::sendRaw) }, enabled = ble.isConnected && !bytes.isNullOrEmpty()) { Text(L("Gửi", "Send")) }
                }
            }
            if (ble.gattTable.isNotEmpty()) {
                item {
                    SectionLabel(L("Dịch vụ GATT", "GATT services"))
                    FloatingCard(Modifier.fillMaxWidth()) {
                        ble.gattTable.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                }
            }
            item {
                SectionLabel(L("Log (${ble.log.size} dòng, mới nhất ở trên)", "Log (${ble.log.size} lines, newest first)"))
                Button(onClick = { showReport = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(L("🐞 Báo lỗi lên GitHub (kèm log)", "🐞 Report a bug on GitHub (with log)"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val file = ble.exportLog()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, L("Xuất log", "Export log")))
                    }) { Text(L("Xuất log (gửi cho dev)", "Export log (send to dev)")) }
                    OutlinedButton(onClick = ble::clearLog) { Text(L("Xoá log", "Clear log")) }
                }
            }
            items(ble.log.takeLast(200).reversed()) { line ->
                Text("${time.format(Date(line.time))}  ${line.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
    if (showReport) BugReportDialog(model, includeLogByDefault = true) { showReport = false }
}
