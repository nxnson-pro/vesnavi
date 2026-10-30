package com.nxnson.vesnavi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.Localizer
import com.nxnson.vesnavi.appLanguage
import com.nxnson.vesnavi.navcore.MaintenanceItem
import com.nxnson.vesnavi.navcore.MaintenanceStatus

private fun km(v: Int) = "%,d".format(Localizer.locale, v)

/** Most urgent service items on the "My scooter" screen. */
@Composable
fun MaintenanceCard(model: AppModel, nav: Navigator) {
    FloatingCard(Modifier.fillMaxWidth()) {
        Text(L("Bảo dưỡng", "Maintenance"), fontWeight = FontWeight.SemiBold)
        model.maintenance.sorted.take(2).forEach { MaintenanceRow(it.item, it.status) }
        Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.Maintenance) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("Tất cả hạng mục", "All items"), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
fun MaintenanceScreen(model: AppModel, nav: Navigator) {
    var editing by remember { mutableStateOf<MaintenanceItem?>(null) }
    val store = model.maintenance
    SubScreen(L("Bảo dưỡng", "Maintenance"), onBack = nav::pop) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(store.sorted, key = { it.item.id }) { entry ->
                Column(Modifier.clickable { editing = entry.item }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    MaintenanceRow(entry.item, entry.status)
                }
                HorizontalDivider()
            }
            item {
                val odo = store.odometer?.let { "${km(it)} km" }
                Text(
                    L(
                        "ODO hiện tại: ${odo ?: "chưa có (kết nối xe một lần)"}. Khoảng thay mặc định theo thói quen ở VN cho GTS 300, chỉnh được từng mục. Mục quá hạn sẽ hiện popup trên màn hình xe mỗi ngày một lần.",
                        "Current ODO: ${odo ?: "none yet (connect the scooter once)"}. Default intervals follow common practice in Vietnam for a GTS 300; each item can be changed. Overdue items pop up on the scooter display once a day.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    editing?.let { item -> MaintenanceEditDialog(model, item) { editing = null } }
}

@Composable
fun MaintenanceRow(item: MaintenanceItem, status: MaintenanceStatus) {
    val color = when (status.level) {
        MaintenanceStatus.Level.DUE -> Color.Red
        MaintenanceStatus.Level.SOON -> Color(0xFFFF9500)
        MaintenanceStatus.Level.OK -> Theme.accent
        MaintenanceStatus.Level.UNKNOWN -> Color.Gray
    }
    val summary = when (status.level) {
        MaintenanceStatus.Level.UNKNOWN -> L("Chưa nhập lần cuối", "Last service not set")
        MaintenanceStatus.Level.DUE -> {
            val k = status.kmLeft
            val d = status.daysLeft
            when {
                k != null && k <= 0 -> L("Quá ${km(-k)} km", "${km(-k)} km overdue")
                d != null && d <= 0 -> L("Quá hạn ${-d} ngày", "${-d} days overdue")
                else -> L("Đến hạn", "Due")
            }
        }
        MaintenanceStatus.Level.SOON, MaintenanceStatus.Level.OK -> listOfNotNull(
            status.kmLeft?.let { L("còn ${km(it)} km", "${km(it)} km left") },
            status.daysLeft?.let { if (it >= 60) L("${it / 30} tháng", "${it / 30} months") else L("$it ngày", "$it days") },
        ).joinToString(" · ")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).background(color, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Build, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(item.displayName(appLanguage), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(summary, color = color, style = MaterialTheme.typography.labelMedium)
            }
            if (status.level != MaintenanceStatus.Level.UNKNOWN) {
                LinearProgressIndicator(progress = { status.progress.toFloat().coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Mark as done / set when it was last done / change the interval. */
@Composable
private fun MaintenanceEditDialog(model: AppModel, item: MaintenanceItem, onDismiss: () -> Unit) {
    val odo = model.maintenance.odometer
    var lastKm by remember { mutableStateOf(item.lastKm?.toString() ?: "") }
    var daysAgo by remember { mutableStateOf(item.lastDate?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toString() } ?: "") }
    var intervalKm by remember { mutableIntStateOf(item.intervalKm ?: 0) }
    var intervalMonths by remember { mutableIntStateOf(item.intervalMonths ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName(appLanguage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.maintenance.markDone(item.id, odo); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    val at = odo?.let { " (ODO ${km(it)} km)" } ?: ""
                    Text(L("Vừa làm hôm nay$at", "Done today$at"))
                }
                SectionLabel(L("Lần làm gần nhất", "Last done"))
                OutlinedTextField(
                    value = lastKm, onValueChange = { lastKm = it.filter(Char::isDigit) },
                    label = { Text(L("ODO lúc làm (km)", "ODO at the time (km)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                OutlinedTextField(
                    value = daysAgo, onValueChange = { daysAgo = it.filter(Char::isDigit) },
                    label = { Text(L("Cách đây bao nhiêu ngày", "How many days ago")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                SectionLabel(L("Chu kỳ", "Interval"))
                Stepper(
                    if (intervalKm > 0) L("Mỗi ${km(intervalKm)} km", "Every ${km(intervalKm)} km") else L("Không theo km", "No km interval"),
                    onMinus = { intervalKm = (intervalKm - 500).coerceAtLeast(0) }, onPlus = { intervalKm = (intervalKm + 500).coerceAtMost(50_000) },
                )
                Stepper(
                    if (intervalMonths > 0) L("Mỗi $intervalMonths tháng", "Every $intervalMonths months") else L("Không theo thời gian", "No time interval"),
                    onMinus = { intervalMonths = (intervalMonths - 1).coerceAtLeast(0) }, onPlus = { intervalMonths = (intervalMonths + 1).coerceAtMost(60) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                model.maintenance.update(item.id) {
                    it.copy(
                        lastKm = lastKm.toIntOrNull(),
                        lastDate = daysAgo.toLongOrNull()?.let { d -> System.currentTimeMillis() - d * 86_400_000L },
                        intervalKm = intervalKm.takeIf { v -> v > 0 },
                        intervalMonths = intervalMonths.takeIf { v -> v > 0 },
                    )
                }
                onDismiss()
            }) { Text(L("Lưu", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Huỷ", "Cancel")) } },
    )
}
