package com.nxnson.vesnavi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.Localizer
import com.nxnson.vesnavi.navcore.FuelEstimate
import com.nxnson.vesnavi.navcore.FuelEstimator
import com.nxnson.vesnavi.navcore.TripSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun f(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

private fun dateTime(millis: Long, pattern: String) = SimpleDateFormat(pattern, Localizer.locale).format(Date(millis))

/** "1 h 05 min" style without the leading "1 min" floor of [durationText]. */
private fun hm(seconds: Double): String {
    val m = (seconds / 60).roundToInt()
    return if (m < 60) "$m ${L("phút", "min")}" else "${m / 60} ${L("giờ", "h")} ${m % 60} ${L("phút", "min")}"
}

/** Fuel left, range and when to refuel, estimated from the ECU's fuel rate since the last fill-up. */
@Composable
fun FuelCard(model: AppModel) {
    val store = model.trips
    var menu by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(false) }
    FloatingCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalGasStation, null)
            Spacer(Modifier.size(8.dp))
            Text(L("Nhiên liệu", "Fuel"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, L("Thêm", "More")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(L("Vừa đổ đầy bình", "Just filled up")) }, onClick = { menu = false; store.filledUp() })
                    DropdownMenuItem(text = { Text(L("Chỉnh mức xăng hiện tại…", "Set current fuel level…")) }, onClick = { menu = false; showLevel = true })
                }
            }
        }
        val detected = store.refuelDetectedAt
        if (detected != null && System.currentTimeMillis() - detected < 3_600_000) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF34C759))
                Text(L("Đã nhận biết bạn vừa đổ xăng — mức xăng đã cập nhật", "Fill-up detected — fuel level updated"), color = Color(0xFF34C759))
            }
        }
        val e = store.fuelEstimate
        if (e != null) {
            val low = e.litersLeft <= FuelEstimator.RESERVE_LITERS
            LinearProgressIndicator(progress = { e.fraction.toFloat().coerceIn(0f, 1f) }, color = if (low) Color.Red else Theme.accent, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.Bottom) {
                Text("≈ ${e.rangeKm.roundToInt()} km", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(" " + L("còn đi được", "range"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(bottom = 4.dp))
                Text("${f(e.litersLeft, 1)} L", fontWeight = FontWeight.SemiBold)
            }
            Text(refuelText(e), color = if (low) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${L("Tiêu thụ", "Consumption")} ${f(e.litersPer100Km, 1)} L/100 km · ${f(100 / e.litersPer100Km, 0)} km/L",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            store.fuel.lastFuelBars?.let { bars ->
                val gaugeOnly = store.fuel.filledAt == null
                Text(
                    L(
                        "Đồng hồ xăng trên xe: $bars/${store.fuel.barsMax} vạch${if (gaugeOnly) " (đang ước theo đồng hồ)" else ""}",
                        "Scooter fuel gauge: $bars/${store.fuel.barsMax} bars${if (gaugeOnly) " (estimating from the gauge)" else ""}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                L(
                    "Kết nối xe để đọc đồng hồ xăng, hoặc khi đổ đầy bấm ⋮ → \"Vừa đổ đầy bình\". App tự nhận biết khi bạn đổ xăng (đồng hồ tăng ≥ 2 vạch) và tính xăng còn lại theo lượng xăng xe báo đã tiêu thụ.",
                    "Connect the scooter to read its fuel gauge, or after filling up tap ⋮ → \"Just filled up\". The app detects fill-ups (gauge up ≥ 2 bars) and tracks fuel left from the consumption the scooter reports.",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = store::filledUp) { Text(L("Vừa đổ đầy bình", "Just filled up")) }
        }
    }
    if (showLevel) FuelLevelDialog(model) { showLevel = false }
}

private fun refuelText(e: FuelEstimate): String {
    if (e.litersLeft <= FuelEstimator.RESERVE_LITERS) return L("Sắp hết xăng — nên đổ ngay", "Almost empty — refuel now")
    val date = e.refuelBy ?: return L("Ghi thêm vài hành trình để dự tính ngày đổ xăng", "Record a few more trips to predict the refuel date")
    fun day(millis: Long) = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((day(date) - day(System.currentTimeMillis())) / 86_400_000L).toInt()
    val whenText = when {
        days < 1 -> L("hôm nay", "today")
        days == 1 -> L("ngày mai", "tomorrow")
        else -> {
            val d = dateTime(date, "EEEE d/M")
            L("$d ($days ngày nữa)", "$d (in $days days)")
        }
    }
    return L("Nên đổ xăng: $whenText", "Refuel: $whenText")
}

/** The rider reads the fuel gauge and sets the level by hand (e.g. after a partial fill). */
@Composable
private fun FuelLevelDialog(model: AppModel, onDismiss: () -> Unit) {
    val fuel = model.trips.fuel
    var fraction by remember { mutableFloatStateOf((model.trips.fuelEstimate?.fraction ?: 0.5).toFloat()) }
    var tank by remember { mutableFloatStateOf(fuel.tankLiters.toFloat()) }
    var barsMax by remember { mutableIntStateOf(fuel.barsMax) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Mức xăng hiện tại", "Current fuel level")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${(fraction * 100).roundToInt()}% · ${f(fraction * tank.toDouble(), 1)} L", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Slider(value = fraction, onValueChange = { fraction = (it * 8).roundToInt() / 8f }, steps = 7)
                Text(L("Ước theo đồng hồ xăng trên xe (mỗi nấc = 1/8 bình).", "Read it off the scooter's fuel gauge (each step = 1/8 tank)."), style = MaterialTheme.typography.bodySmall)
                Stepper("${L("Dung tích bình", "Tank size")}: ${f(tank.toDouble(), 1)} L", onMinus = { tank = (tank - 0.5f).coerceAtLeast(5f) }, onPlus = { tank = (tank + 0.5f).coerceAtMost(15f) })
                Stepper(L("Bình đầy = $barsMax vạch trên đồng hồ xe", "Full tank = $barsMax bars on the gauge"), onMinus = { barsMax = (barsMax - 1).coerceAtLeast(4) }, onPlus = { barsMax = (barsMax + 1).coerceAtMost(10) })
                Text(L("Đếm số vạch khi bình đầy để app đọc đồng hồ xăng của xe chính xác.", "Count the bars with a full tank so the app reads the scooter's gauge correctly."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                model.trips.setFuel(fraction.toDouble(), tank.toDouble(), barsMax)
                onDismiss()
            }) { Text(L("Lưu", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Huỷ", "Cancel")) } },
    )
}

/** − value + row, like an iOS Stepper. */
@Composable
fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", fontSize = 20.sp) }
        TextButton(onClick = onPlus) { Text("+", fontSize = 20.sp) }
    }
}

/** Live trip + link to the history. */
@Composable
fun TripsCard(model: AppModel, nav: Navigator) {
    val store = model.trips
    FloatingCard(Modifier.fillMaxWidth()) {
        Text(L("Hành trình", "Trips"), fontWeight = FontWeight.SemiBold)
        store.current?.let { live ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                Text("${L("Đang ghi", "Recording")} · ${f(live.distanceKm, 1)} km · ${hm(live.duration)}")
            }
        }
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val inMonth = store.trips.filter { t -> Calendar.getInstance().apply { timeInMillis = t.start }.let { it.get(Calendar.MONTH) == month && it.get(Calendar.YEAR) == year } }
        Row(Modifier.fillMaxWidth()) {
            Stat("${inMonth.sumOf { it.distanceKm }.roundToInt()} km", L("Tháng này", "This month"))
            Stat("${f(inMonth.sumOf { it.fuelLiters }, 1)} L", L("Xăng", "Fuel"))
            Stat("${inMonth.size}", L("Chuyến", "Trips"))
        }
        Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.Trips) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("Xem tất cả hành trình", "See all trips"), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(value: String, title: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TripsScreen(model: AppModel, nav: Navigator) {
    val trips = model.trips.trips.reversed()
    SubScreen(L("Hành trình", "Trips"), onBack = nav::pop) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (trips.isEmpty()) {
                item {
                    Text(
                        L("Chưa có hành trình nào. App tự ghi mỗi khi xe nổ máy và đã kết nối.", "No trips yet. The app records one whenever the engine runs while connected."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(trips, key = { it.id }) { trip -> TripRow(trip, onOpen = { nav.push(Screen.TripDetail(trip.id)) }, onDelete = { model.trips.delete(trip.id) }) }
        }
    }
}

@Composable
private fun TripRow(trip: TripSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateTime(trip.start, "EEE d/M HH:mm"), fontWeight = FontWeight.SemiBold)
                val consumption = trip.litersPer100Km?.let { " · ${f(it, 1)} L/100" } ?: ""
                Text("${f(trip.distanceKm, 1)} km · ${hm(trip.duration)}$consumption", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, L("Xoá", "Delete")) }
        }
        HorizontalDivider()
    }
}

@Composable
fun TripDetailScreen(model: AppModel, nav: Navigator, id: String) {
    val trip = model.trips.trips.firstOrNull { it.id == id }
    SubScreen(trip?.let { dateTime(it.start, "d/M HH:mm") } ?: "", onBack = nav::pop) {
        if (trip == null) return@SubScreen
        val tiles = listOf(
            Triple(L("Quãng đường", "Distance"), f(trip.distanceKm, 1), "km"),
            Triple(L("Thời gian", "Time"), hm(trip.duration), ""),
            Triple(L("Tốc độ TB", "Avg speed"), f(trip.avgSpeedKmh, 0), "km/h"),
            Triple(L("Tốc độ tối đa", "Max speed"), f(trip.maxSpeedKmh, 0), "km/h"),
            Triple(L("Xăng đã dùng", "Fuel used"), f(trip.fuelLiters, 2), "L"),
            Triple(L("Tiêu thụ", "Consumption"), trip.litersPer100Km?.let { f(it, 1) }, "L/100km"),
            Triple(L("Nước mát TB", "Avg coolant"), trip.avgCoolantC?.let { f(it, 0) }, "°C"),
            Triple(L("Nước mát cao nhất", "Max coolant"), trip.maxCoolantC?.toString(), "°C"),
            Triple(L("Nhiệt độ ngoài TB", "Avg outside temp"), trip.avgAmbientC?.let { f(it, 0) }, "°C"),
            Triple(L("Vòng tua cao nhất", "Peak RPM"), "${trip.maxRPM}", L("vòng/ph", "rpm")),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (title, value, unit) -> MetricTile(title, value, unit, modifier = Modifier.weight(1f)) }
                }
            }
            val s = trip.startOdometerKm
            val e = trip.endOdometerKm
            if (s != null && e != null) Text("ODO $s → $e km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
