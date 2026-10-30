package com.nxnson.vesnavi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.VespaBLE
import com.nxnson.vesnavi.navcore.Maneuver

/** Shared look: one accent colour, rounded cards, big readable numbers. */
object Theme {
    /** Vespa-ish teal. */
    val accent = Color(0xFF1C9E8F)
    val maneuverBackground = Color(0xFF0F3330)
    val cardRadius = 22.dp
}

@Composable
fun VesNaviTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Theme.accent, secondary = Theme.accent)
    } else {
        lightColorScheme(primary = Theme.accent, secondary = Theme.accent, background = Color(0xFFF2F2F7), surface = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Floating card used across the map screens and the scooter sheet. */
@Composable
fun FloatingCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.shadow(8.dp, RoundedCornerShape(Theme.cardRadius)),
        shape = RoundedCornerShape(Theme.cardRadius),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Circular icon button that sits on the map. */
@Composable
fun MapIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(48.dp).shadow(6.dp, CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
    }
}

/** Coloured dot + short text describing the scooter link. */
@Composable
fun ScooterStatusPill(ble: VespaBLE, onClick: () -> Unit) {
    val color = when (ble.state) {
        VespaBLE.LinkState.READY -> Color(0xFF34C759)
        VespaBLE.LinkState.OFF, VespaBLE.LinkState.UNAUTHORIZED, VespaBLE.LinkState.UNKNOWN_SERVICE -> Color(0xFFFF3B30)
        VespaBLE.LinkState.IDLE -> Color.Gray
        else -> Color(0xFFFF9500)
    }
    val t = ble.telemetry
    val label = when (ble.state) {
        VespaBLE.LinkState.READY ->
            if (t?.coolantC != null && t.engineRunning) L("Đã kết nối · ${t.coolantC}°C", "Connected · ${t.coolantC}°C") else L("Đã kết nối", "Connected")
        VespaBLE.LinkState.IDLE -> L("Chưa kết nối xe", "Scooter not connected")
        VespaBLE.LinkState.SCANNING, VespaBLE.LinkState.CONNECTING -> L("Đang tìm xe…", "Looking for the scooter…")
        VespaBLE.LinkState.DISCOVERING, VespaBLE.LinkState.HANDSHAKING, VespaBLE.LinkState.WAITING_FOR_SCOOTER -> L("Đang kết nối…", "Connecting…")
        VespaBLE.LinkState.OFF -> L("Bluetooth tắt", "Bluetooth off")
        VespaBLE.LinkState.UNAUTHORIZED -> L("Cần quyền Bluetooth", "Bluetooth permission needed")
        VespaBLE.LinkState.UNKNOWN_SERVICE -> L("Xe không hỗ trợ", "Scooter not supported")
    }
    Surface(
        modifier = Modifier.height(48.dp).shadow(6.dp, RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Icon(Icons.Filled.TwoWheeler, contentDescription = null)
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Turn arrow drawn for any angle (the TFT has its own pictograms); arrival and roundabouts get icons. */
@Composable
fun ManeuverIcon(maneuver: Maneuver, roundaboutDegrees: Double?, tint: Color, modifier: Modifier = Modifier) {
    when (maneuver) {
        Maneuver.Arrive -> Icon(Icons.Filled.Flag, null, modifier, tint)
        is Maneuver.Roundabout -> if (roundaboutDegrees == null) Icon(Icons.Filled.Sync, null, modifier, tint) else Arrow(roundaboutDegrees.toFloat(), tint, modifier)
        else -> Arrow(angle(maneuver), tint, modifier)
    }
}

private fun angle(m: Maneuver): Float = when (m) {
    Maneuver.SlightLeft, Maneuver.KeepLeft -> -45f
    Maneuver.Left -> -90f
    Maneuver.SharpLeft -> -135f
    Maneuver.SlightRight, Maneuver.KeepRight -> 45f
    Maneuver.Right -> 90f
    Maneuver.SharpRight -> 135f
    Maneuver.UTurnLeft, Maneuver.UTurnRight -> 180f
    else -> 0f
}

@Composable
private fun Arrow(degrees: Float, tint: Color, modifier: Modifier) {
    Canvas(modifier.rotate(degrees)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.9f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.95f)
            lineTo(w * 0.38f, h * 0.95f)
            lineTo(w * 0.38f, h * 0.5f)
            lineTo(w * 0.1f, h * 0.5f)
            close()
        }
        drawPath(path, tint)
    }
}

/** Screen with a back arrow, used for everything that isn't the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Quay lại", "Back")) }
                },
                actions = { actions() },
            )
            Box(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** Grey section label like an iOS form header. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}
