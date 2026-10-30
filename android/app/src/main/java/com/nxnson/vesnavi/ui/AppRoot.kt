package com.nxnson.vesnavi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.L

/** Everything that opens on top of the map. A plain stack: no navigation library needed. */
sealed class Screen {
    object Search : Screen()
    object Scooter : Screen()
    object Settings : Screen()
    object Trips : Screen()
    data class TripDetail(val id: String) : Screen()
    object Maintenance : Screen()
    object BluetoothLab : Screen()
    object Support : Screen()
}

class Navigator {
    val stack = mutableStateListOf<Screen>()
    fun push(s: Screen) = stack.add(s)
    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
    fun popToRoot() = stack.clear()
}

@Composable
fun AppRoot(model: AppModel, requestBluetooth: () -> Unit) {
    val nav = remember { Navigator() }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    when (val top = nav.stack.lastOrNull()) {
        null -> MainScreen(model, nav, requestBluetooth)
        Screen.Search -> SearchScreen(model, nav)
        Screen.Scooter -> ScooterScreen(model, nav, requestBluetooth)
        Screen.Settings -> SettingsScreen(model, nav)
        Screen.Trips -> TripsScreen(model, nav)
        is Screen.TripDetail -> TripDetailScreen(model, nav, top.id)
        Screen.Maintenance -> MaintenanceScreen(model, nav)
        Screen.BluetoothLab -> BluetoothLabScreen(model, nav, requestBluetooth)
        Screen.Support -> SupportScreen(nav)
    }

    if (!model.settings.safetyNoticeAccepted) {
        SafetyNoticeDialog { model.settings.safetyNoticeAccepted = true }
    }
    model.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { model.errorMessage = null },
            confirmButton = { TextButton(onClick = { model.errorMessage = null }) { Text("OK") } },
            title = { Text(L("Có lỗi", "Error")) },
            text = { Text(message) },
        )
    }
}
