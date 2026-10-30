package com.nxnson.vesnavi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.Localizer
import com.nxnson.vesnavi.appLanguage
import com.nxnson.vesnavi.navcore.DistanceFormatter
import com.nxnson.vesnavi.navcore.NavigationState
import com.nxnson.vesnavi.navcore.SavedPlace
import com.nxnson.vesnavi.navcore.ScooterTelemetry
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** "8 phút", "1 giờ 05 phút" / "8 min", "1 h 05 min" */
fun durationText(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    if (minutes < 60) return "$minutes ${L("phút", "min")}"
    return String.format(Locale.US, "%d %s %02d %s", minutes / 60, L("giờ", "h"), minutes % 60, L("phút", "min"))
}

fun clockTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, Localizer.locale).format(Date(millis))

fun arrival(afterSeconds: Double): String = clockTime(System.currentTimeMillis() + (afterSeconds * 1000).toLong())

const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

/** Single map screen: everything else floats on top of it as cards or opens as a screen. */
@Composable
fun MainScreen(model: AppModel, nav: Navigator, requestBluetooth: () -> Unit) {
    var recenter by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        RouteMap(model, recenter, bottomHeight, Modifier.fillMaxSize())

        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            val state = model.navState
            if (model.phase == AppModel.Phase.NAVIGATING && state != null) {
                ManeuverCard(state, model.routeNote, model.ble.isReady, model.ble.telemetry?.dashboardSpeedKmh)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScooterStatusPill(model.ble) { nav.push(Screen.Scooter) }
                    Spacer(Modifier.weight(1f))
                    MapIconButton(Icons.Filled.Settings, L("Cài đặt", "Settings")) { nav.push(Screen.Settings) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth().onSizeChanged { bottomHeight = it.height },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (model.phase != AppModel.Phase.NAVIGATING) {
                MapIconButton(Icons.Filled.MyLocation, L("Vị trí của tôi", "My location")) { recenter++ }
            }
            when (model.phase) {
                AppModel.Phase.IDLE -> IdlePanel(model, nav, requestBluetooth)
                AppModel.Phase.ROUTING -> FloatingCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(L("Đang tìm đường…", "Finding a route…"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = model::stopNavigation) { Text(L("Huỷ", "Cancel")) }
                    }
                }
                AppModel.Phase.PREVIEW -> PreviewPanel(model)
                AppModel.Phase.NAVIGATING -> NavigatingPanel(model)
            }
        }
    }
}

@Composable
private fun IdlePanel(model: AppModel, nav: Navigator, requestBluetooth: () -> Unit) {
    FloatingCard(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(54.dp).clickable { nav.push(Screen.Search) },
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Search, null, tint = Theme.accent)
                Text(L("Đi đâu?", "Where to?"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
        QuickPlaces(model)
        val t = model.ble.telemetry
        if (t != null) {
            Box(Modifier.clickable { nav.push(Screen.Scooter) }) { TelemetryStrip(t, model.trips.fuelEstimate?.rangeKm) }
        } else if (model.ble.state == com.nxnson.vesnavi.VespaBLE.LinkState.IDLE || model.ble.state == com.nxnson.vesnavi.VespaBLE.LinkState.UNAUTHORIZED) {
            OutlinedButton(
                onClick = {
                    if (!model.ble.hasPermissions()) requestBluetooth() else model.ble.quickConnect()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text(L("Kết nối xe", "Connect scooter"))
            }
        }
    }
}

@Composable
private fun PreviewPanel(model: AppModel) {
    val context = LocalContext.current
    FloatingCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(model.destination?.name ?: L("Điểm đến", "Destination"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                model.route?.let { r ->
                    Text(
                        "${durationText(r.duration)} · ${DistanceFormatter.text(r.distance, appLanguage)} · ${L("đến lúc", "arrive")} ${arrival(r.duration)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            model.destination?.let { SavePlaceMenu(model, it) }
            IconButton(onClick = model::stopNavigation) { Icon(Icons.Filled.Close, L("Huỷ", "Cancel")) }
        }
        model.routeNote?.let { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Info, null, tint = Color(0xFFFF9500), modifier = Modifier.size(16.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
            }
        }
        // ODbL: the route line is OpenStreetMap data (OSRM / GraphHopper).
        Text(
            L("Tuyến đường: © OpenStreetMap contributors", "Route: © OpenStreetMap contributors"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT_URL))) },
        )
        Button(onClick = { model.startNavigation(simulate = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(L("Bắt đầu", "Start"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        TextButton(onClick = { model.startNavigation(simulate = true) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(L("Chạy thử (mô phỏng)", "Test run (simulation)"))
        }
    }
}

@Composable
private fun NavigatingPanel(model: AppModel) {
    FloatingCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                model.navState?.let { s ->
                    Text(arrival(s.remainingDuration), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${durationText(s.remainingDuration)} · ${DistanceFormatter.text(s.remainingDistance, appLanguage)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (model.isSimulating) Text(L("Đang mô phỏng", "Simulating"), color = Color(0xFFFF9500), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            }
            val t = model.ble.telemetry
            val rpm = t?.rpm
            if (t != null && rpm != null && t.engineRunning) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$rpm", fontWeight = FontWeight.Bold)
                    Text(L("vòng/ph", "rpm"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = CircleShape, color = Color(0xFFFF3B30), modifier = Modifier.size(52.dp).clickable(onClick = model::stopNavigation)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, L("Dừng dẫn đường", "Stop navigation"), tint = Color.White) }
            }
        }
    }
}

/** Big next-turn card shown at the top while navigating — the same arrow goes to the scooter TFT. */
@Composable
fun ManeuverCard(state: NavigationState, note: String?, onScooter: Boolean, speedKmh: Int?) {
    Surface(shape = RoundedCornerShape(Theme.cardRadius), color = Theme.maneuverBackground, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ManeuverIcon(state.maneuver, state.roundaboutTurnDegrees, Color.White, Modifier.size(60.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.hasArrived) L("Đã đến", "Arrived") else DistanceFormatter.text(state.distanceToManeuver, appLanguage),
                        color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(state.streetName ?: state.instruction, color = Color.White.copy(alpha = 0.9f), fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.speedLimit?.let { limit ->
                    SpeedLimitSign(limit, state.speedLimitEstimated, speeding = (speedKmh ?: 0) > limit + 3)
                }
            }
            if (state.isOffRoute) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFCC00))
                    Text(L("Lệch tuyến — đang tìm đường mới", "Off route — finding a new route"), color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold)
                }
            }
            note?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }
            if (onScooter) {
                Text("✓ " + L("Đang hiện trên màn hình xe", "Showing on the scooter TFT"), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Round Vietnamese-style limit sign; red when the scooter is faster, labelled when estimated. */
@Composable
fun SpeedLimitSign(kmh: Int, estimated: Boolean, speeding: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).background(if (speeding) Color.Red else Color.White, CircleShape).border(5.dp, Color.Red, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$kmh", color = if (speeding) Color.White else Color.Black, fontWeight = FontWeight.Black, fontSize = 21.sp)
        }
        if (estimated) Text(L("ước tính", "est."), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/** Three key engine numbers under the search field. */
@Composable
fun TelemetryStrip(t: ScooterTelemetry, rangeKm: Double?) {
    Row(Modifier.fillMaxWidth()) {
        StripItem(t.coolantC?.let { "$it°" } ?: "–", L("Nước mát", "Coolant"))
        StripItem(t.batteryVolts?.let { String.format(Locale.US, "%.1f V", it) } ?: "–", L("Ắc quy", "Battery"))
        if (rangeKm != null) StripItem("${rangeKm.roundToInt()} km", L("Còn đi được", "Range"))
        else StripItem(t.odometerKm?.let { "%,d km".format(Localizer.locale, it) } ?: "–", "ODO")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StripItem(value: String, title: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-tap chips under the search field: home, work, parked scooter. */
@Composable
private fun QuickPlaces(model: AppModel) {
    val p = model.places.places
    if (p.home == null && p.work == null && p.parking == null) return
    val context = LocalContext.current
    var showParking by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        p.home?.let { home -> Chip(L("Nhà", "Home"), Icons.Filled.Home) { model.planRoute(home) } }
        p.work?.let { work -> Chip(L("Công ty", "Work"), Icons.Filled.Work) { model.planRoute(work) } }
        p.parking?.let { spot -> Chip("${L("Xe đỗ", "Parked")} · ${clockTime(spot.date)}", Icons.Filled.LocalParking) { showParking = true } }
    }
    val spot = p.parking
    if (showParking && spot != null) {
        val accuracy = spot.accuracy?.let { " (± ${it.roundToInt()} m)" } ?: ""
        AlertDialog(
            onDismissRequest = { showParking = false },
            title = { Text(L("Chỗ đỗ xe", "Parking spot")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        L(
                            "Lưu lúc ${clockTime(spot.date)} khi tắt xe$accuracy. Lệch thì đứng cạnh xe và chọn \"Đặt lại tại vị trí hiện tại\".",
                            "Saved at ${clockTime(spot.date)} when the scooter was switched off$accuracy. If it's off, stand next to the scooter and choose \"Set to where I am now\".",
                        ),
                    )
                    TextButton(onClick = {
                        showParking = false
                        val uri = Uri.parse("google.navigation:q=${spot.latitude},${spot.longitude}&mode=w")
                        val fallback = Uri.parse("geo:${spot.latitude},${spot.longitude}?q=${spot.latitude},${spot.longitude}")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, fallback)) } }
                    }) { Text(L("Chỉ đường đi bộ (ứng dụng bản đồ)", "Walking directions (maps app)")) }
                    TextButton(onClick = { showParking = false; model.setParkingHere() }) { Text(L("Đặt lại tại vị trí hiện tại", "Set to where I am now")) }
                    TextButton(onClick = { showParking = false; model.places.clearParking() }) { Text(L("Xoá chỗ đỗ", "Clear parking spot"), color = Color.Red) }
                }
            },
            confirmButton = { TextButton(onClick = { showParking = false }) { Text(L("Đóng", "Close")) } },
        )
    }
}

@Composable
private fun Chip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.height(36.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Star menu on the route preview: favourite / set as home / set as work. */
@Composable
private fun SavePlaceMenu(model: AppModel, place: SavedPlace) {
    var open by remember { mutableStateOf(false) }
    val favorite = model.places.places.isFavorite(place)
    Box {
        IconButton(onClick = { open = true }) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, L("Lưu địa điểm", "Save place"), tint = if (favorite) Color(0xFFFFCC00) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (favorite) L("Bỏ yêu thích", "Remove from favourites") else L("Thêm vào yêu thích", "Add to favourites")) },
                onClick = { open = false; model.places.toggleFavorite(place) },
            )
            DropdownMenuItem(text = { Text(L("Đặt làm Nhà", "Set as Home")) }, onClick = { open = false; model.places.setHome(place.copy(name = L("Nhà", "Home"), subtitle = place.name)) })
            DropdownMenuItem(text = { Text(L("Đặt làm Công ty", "Set as Work")) }, onClick = { open = false; model.places.setWork(place.copy(name = L("Công ty", "Work"), subtitle = place.name)) })
        }
    }
}
