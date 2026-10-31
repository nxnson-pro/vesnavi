package com.nxnson.vesnavi

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nxnson.vesnavi.navcore.SpeedLimitSource
import java.io.File

/**
 * User settings (SharedPreferences, same keys as the iOS app's UserDefaults where they exist).
 * Values the UI shows are snapshot state, so Compose recomposes when they change.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vesnavi", Context.MODE_PRIVATE)

    private fun str(key: String, default: String) = mutableStateOf(prefs.getString(key, null) ?: default)
    private fun bool(key: String, default: Boolean) = mutableStateOf(prefs.getBoolean(key, default))

    private val graphHopperKeyState = str("graphHopperApiKey", "")
    var graphHopperKey: String
        get() = graphHopperKeyState.value
        set(v) { graphHopperKeyState.value = v; prefs.edit().putString("graphHopperApiKey", v).apply() }

    private val graphHopperProfileState = str("graphHopperProfile", "car")
    var graphHopperProfile: String
        get() = graphHopperProfileState.value
        set(v) { graphHopperProfileState.value = v; prefs.edit().putString("graphHopperProfile", v).apply() }

    private val simulationSpeedState = mutableStateOf(prefs.getInt("simulationSpeedKmh", 30))
    var simulationSpeedKmh: Int
        get() = simulationSpeedState.value
        set(v) { simulationSpeedState.value = v; prefs.edit().putInt("simulationSpeedKmh", v).apply() }

    private val keepScreenOnState = bool("keepScreenOn", false)
    var keepScreenOn: Boolean
        get() = keepScreenOnState.value
        set(v) { keepScreenOnState.value = v; prefs.edit().putBoolean("keepScreenOn", v).apply() }

    private val speedLimitSourceState = mutableStateOf(
        SpeedLimitSource.fromKey(prefs.getString("speedLimitSource", null)) ?: SpeedLimitSource.OSM_WITH_VIETNAM_DEFAULTS,
    )
    var speedLimitSource: SpeedLimitSource
        get() = speedLimitSourceState.value
        set(v) { speedLimitSourceState.value = v; prefs.edit().putString("speedLimitSource", v.key).apply() }

    private val rememberParkingState = bool("rememberParking", true)
    var rememberParking: Boolean
        get() = rememberParkingState.value
        set(v) { rememberParkingState.value = v; prefs.edit().putBoolean("rememberParking", v).apply() }

    var maintenancePopupDay: Long
        get() = prefs.getLong("maintenancePopupDay", 0)
        set(v) = prefs.edit().putLong("maintenancePopupDay", v).apply()

    var appLanguage: String?
        get() = prefs.getString("appLanguage", null)
        set(v) = prefs.edit().putString("appLanguage", v).apply()

    private val safetyAcceptedState = bool("safetyNoticeAccepted", false)
    var safetyNoticeAccepted: Boolean
        get() = safetyAcceptedState.value
        set(v) { safetyAcceptedState.value = v; prefs.edit().putBoolean("safetyNoticeAccepted", v).apply() }

    // Scooter link

    var lastScooterAddress: String?
        get() = prefs.getString("vespaLastPeripheral", null)
        set(v) = prefs.edit().putString("vespaLastPeripheral", v).apply()

    private val autoReconnectState = bool("vespaAutoReconnect", true)
    var autoReconnect: Boolean
        get() = autoReconnectState.value
        set(v) { autoReconnectState.value = v; prefs.edit().putBoolean("vespaAutoReconnect", v).apply() }

    private val idleTextState = mutableStateOf(prefs.getString("vespaIdleDisplay", "off") != "off")
    /** TFT text line with coolant + battery while no route is active. */
    var idleText: Boolean
        get() = idleTextState.value
        set(v) { idleTextState.value = v; prefs.edit().putString("vespaIdleDisplay", if (v) "textOnNavScreen" else "off").apply() }

    private val pmp2OverSppState = bool("vespaPmp2OverSpp", true)
    /** PMP2 scooters: classic Bluetooth SPP (Android's path) instead of the two BLE links the iPhone needs. */
    var pmp2OverSpp: Boolean
        get() = pmp2OverSppState.value
        set(v) { pmp2OverSppState.value = v; prefs.edit().putBoolean("vespaPmp2OverSpp", v).apply() }

    /** Subscribe to engine data (0x28) on SPP; turned off by itself if the scooter drops the link right after it. */
    var sppEngineData: Boolean
        get() = prefs.getBoolean("vespaSppEngineData", true)
        set(v) = prefs.edit().putBoolean("vespaSppEngineData", v).apply()

    private val warningsState = bool("vespaWarnings", true)
    var warningsEnabled: Boolean
        get() = warningsState.value
        set(v) { warningsState.value = v; prefs.edit().putBoolean("vespaWarnings", v).apply() }

    fun pairing(address: String): String? = prefs.getString("vespaPairing.$address", null)
    fun setPairing(address: String, json: String?) = prefs.edit().putString("vespaPairing.$address", json).apply()
}

/** JSON files in the app's private storage (trips, places, maintenance). */
class JsonFile(context: Context, name: String) {
    private val file = File(context.filesDir, name)

    fun read(): String? = runCatching { file.readText() }.getOrNull()

    /** Write to a temp file and rename, so a crash mid-write never leaves half a file. */
    fun write(text: String) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}
