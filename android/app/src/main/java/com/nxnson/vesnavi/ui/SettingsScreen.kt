package com.nxnson.vesnavi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nxnson.vesnavi.AppLanguage
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.BuildConfig
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.Localizer
import com.nxnson.vesnavi.navcore.SpeedLimitSource

@Composable
fun SettingsScreen(model: AppModel, nav: Navigator) {
    val s = model.settings
    val ble = model.ble
    var showSafety by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SubScreen(L("Cài đặt", "Settings"), onBack = nav::pop) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group("Ngôn ngữ / Language") {
                AppLanguage.entries.forEach { lang ->
                    Choice(lang.label, Localizer.choice == lang) { Localizer.set(lang, s) }
                }
            }

            Group(L("Xe", "Scooter"), L("Chạm vào biểu tượng xe ở màn hình chính để xem thông số và kết nối.", "Tap the scooter badge on the main screen for engine data and connecting.")) {
                Toggle(L("Tự kết nối khi bật xe", "Auto-connect when the scooter starts"), s.autoReconnect) { s.autoReconnect = it }
                Text("${L("Trạng thái", "Status")}: ${ble.state.title}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Group(
                L("Màn hình xe (thử nghiệm)", "Scooter display (experimental)"),
                L(
                    "Dòng chữ hiện nhiệt độ nước làm mát và điện áp ắc quy, ví dụ \"NUOC 85C  AQ 13.8V\". Màn hình xe chuyển sang giao diện dẫn đường để hiện dòng chữ. Popup báo khi nước làm mát ≥ 105°C, ắc quy không sạc hoặc yếu (tối đa 1 lần / 5 phút).",
                    "The text line shows coolant temperature and battery voltage, e.g. \"COOL 85C  BAT 13.8V\". The scooter display switches to its navigation layout to show the line. Pop-ups warn when coolant ≥ 105°C or the battery is not charging or low (at most once every 5 minutes).",
                ),
            ) {
                Choice(L("Khi không dẫn đường: tắt (\"NO ADDRESS\")", "When not navigating: off (\"NO ADDRESS\")"), !s.idleText) { s.idleText = false }
                Choice(L("Khi không dẫn đường: nước mát + ắc quy", "When not navigating: coolant + battery"), s.idleText) { s.idleText = true }
                Toggle(L("Cảnh báo popup", "Pop-up warnings"), s.warningsEnabled) { s.warningsEnabled = it }
            }

            Group(
                L("Tìm đường", "Routing"),
                L(
                    "Mặc định dùng OSRM (miễn phí, không cần key). Có key GraphHopper (đăng ký miễn phí ≈500 lượt/ngày) thì dùng GraphHopper, lỗi hoặc hết lượt sẽ tự quay về OSRM. Gói miễn phí chỉ có kiểu Ô tô (đi đúng đường một chiều, phù hợp xe máy).",
                    "OSRM is used by default (free, no key). With a GraphHopper key (free sign-up, ≈500 requests/day) GraphHopper is used, falling back to OSRM on errors or when the quota runs out. The free plan only has the Car profile (respects one-way streets, fine for a scooter).",
                ),
            ) {
                Choice(L("Ô tô (gói miễn phí)", "Car (free plan)"), s.graphHopperProfile == "car") { s.graphHopperProfile = "car" }
                Choice(L("Xe tay ga (gói trả phí)", "Scooter (paid plan)"), s.graphHopperProfile == "scooter") { s.graphHopperProfile = "scooter" }
                OutlinedTextField(
                    value = s.graphHopperKey,
                    onValueChange = { s.graphHopperKey = it },
                    label = { Text(L("GraphHopper API key (tuỳ chọn)", "GraphHopper API key (optional)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Group(
                L("Dẫn đường", "Navigation"),
                L(
                    "Màn hình điện thoại có thể tắt khi dẫn đường để tiết kiệm pin. Giới hạn tốc độ hiện trên màn hình xe và trong app; nguồn miễn phí: OpenStreetMap (không cần key). \"+ luật VN\": đường chưa có dữ liệu thì ước theo Thông tư 38/2024 (trong khu dân cư: 60 km/h đường đôi/một chiều ≥ 2 làn, 50 km/h còn lại), ghi \"ước tính\".",
                    "The phone screen may turn off while navigating to save battery. The speed limit shows on the scooter and in the app; free source: OpenStreetMap (no key). \"+ VN rules\": roads without data use Vietnam's Circular 38/2024 (built-up areas: 60 km/h on divided or one-way roads with ≥ 2 lanes, 50 km/h otherwise), marked \"estimate\".",
                ),
            ) {
                Toggle(L("Giữ màn hình điện thoại sáng", "Keep the phone screen on"), s.keepScreenOn) { s.keepScreenOn = it }
                Text(L("Giới hạn tốc độ", "Speed limit"), fontWeight = FontWeight.Medium)
                Choice(L("Tắt", "Off"), s.speedLimitSource == SpeedLimitSource.OFF) { s.speedLimitSource = SpeedLimitSource.OFF }
                Choice("OpenStreetMap", s.speedLimitSource == SpeedLimitSource.OSM) { s.speedLimitSource = SpeedLimitSource.OSM }
                Choice(L("OpenStreetMap + luật VN", "OpenStreetMap + VN rules"), s.speedLimitSource == SpeedLimitSource.OSM_WITH_VIETNAM_DEFAULTS) {
                    s.speedLimitSource = SpeedLimitSource.OSM_WITH_VIETNAM_DEFAULTS
                }
                Choice(L("GraphHopper (cần key)", "GraphHopper (key needed)"), s.speedLimitSource == SpeedLimitSource.GRAPH_HOPPER) { s.speedLimitSource = SpeedLimitSource.GRAPH_HOPPER }
            }

            Group(
                null,
                L(
                    "Lưu vị trí lúc tắt xe. App chạy nền (thông báo \"VesNavi\") trong lúc kết nối xe nên lấy được vị trí kể cả khi điện thoại trong túi.",
                    "Saves the location when the scooter is switched off. The app keeps running (\"VesNavi\" notification) while connected to the scooter, so this works with the phone in a pocket.",
                ),
            ) {
                Toggle(L("Nhớ chỗ đỗ xe", "Remember parking spot"), s.rememberParking) { s.rememberParking = it }
            }

            Group(L("Chạy thử", "Test run")) {
                Stepper(
                    L("Tốc độ: ${s.simulationSpeedKmh} km/h", "Speed: ${s.simulationSpeedKmh} km/h"),
                    onMinus = { s.simulationSpeedKmh = (s.simulationSpeedKmh - 5).coerceAtLeast(10) },
                    onPlus = { s.simulationSpeedKmh = (s.simulationSpeedKmh + 5).coerceAtMost(80) },
                )
            }

            Group(
                L("Thông tin", "About"),
                L(
                    "Tuyến đường (OSRM, GraphHopper), giới hạn tốc độ (Overpass), tìm kiếm (Photon) và bản đồ (OpenFreeMap) dùng dữ liệu OpenStreetMap theo giấy phép ODbL. VesNavi là dự án cá nhân, không liên kết với Piaggio / Vespa.",
                    "Routes (OSRM, GraphHopper), speed limits (Overpass), search (Photon) and the map (OpenFreeMap) use OpenStreetMap data under the ODbL. VesNavi is an unofficial hobby project, not affiliated with Piaggio / Vespa.",
                ),
            ) {
                Link("© OpenStreetMap contributors") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT_URL))) }
                Link(L("Lưu ý an toàn", "Safety notice")) { showSafety = true }
                Link(L("☕ Ủng hộ tác giả", "☕ Support the author")) { nav.push(Screen.Support) }
                Text("${L("Phiên bản", "Version")}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showSafety) SafetyNoticeDialog { showSafety = false }
}

@Composable
private fun Group(title: String?, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { SectionLabel(it) }
        FloatingCard(Modifier.fillMaxWidth()) { content() }
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Text(label, color = Theme.accent, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp))
}

/** Shown once on first launch (and from Settings): ride safely, data is indicative, unofficial app. */
@Composable
fun SafetyNoticeDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Shield, null, tint = Theme.accent, modifier = Modifier.size(40.dp)) },
        title = { Text(L("Trước khi lên xe", "Before you ride")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("• Đặt điểm đến trước khi chạy. Không thao tác điện thoại khi đang lái — luôn chú ý đường và tuân thủ luật giao thông.", "• Set your destination before riding. Don't handle the phone while riding — watch the road and follow traffic law."))
                Text(L("• Chỉ đường và giới hạn tốc độ chỉ để tham khảo, có thể sai hoặc thiếu. Biển báo thực tế luôn được ưu tiên.", "• Directions and speed limits are for reference only and can be wrong or missing. Road signs always take priority."))
                Text(L("• VesNavi là dự án cá nhân, không liên kết với Piaggio / Vespa. Bạn tự chịu trách nhiệm khi sử dụng.", "• VesNavi is an unofficial hobby project, not affiliated with Piaggio / Vespa. Use it at your own risk."))
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text(L("Tôi đã hiểu", "I understand")) } },
    )
}
