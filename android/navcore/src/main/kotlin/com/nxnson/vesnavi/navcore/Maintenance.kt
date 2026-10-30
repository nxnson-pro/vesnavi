package com.nxnson.vesnavi.navcore

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val DAY_MS = 86_400_000L

/** A service item tracked against the scooter's odometer and/or the calendar. */
data class MaintenanceItem(
    val id: String,
    val name: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    /** ASCII text for the TFT pop-up ("THAY NHOT MAY"). */
    val tftText: String,
    val lastKm: Int? = null,
    /** Epoch millis. */
    val lastDate: Long? = null,
) {
    fun displayName(language: Language): String =
        if (language == Language.EN) english[id]?.first ?: name else name

    /** TFT pop-up line (ASCII, upper-cased when sent). */
    fun popupText(language: Language): String =
        if (language == Language.EN) english[id]?.second ?: tftText else tftText

    companion object {
        /**
         * Common practice in Vietnam for a GTS 300 HPE (shorter than the factory schedule because of
         * heat and stop-and-go traffic). Every interval can be changed in the app.
         */
        val defaults = listOf(
            MaintenanceItem("engineOil", "Nhớt máy", 3_000, 6, "Thay nhot may"),
            MaintenanceItem("gearOil", "Nhớt hộp số (láp)", 6_000, 12, "Thay nhot lap"),
            MaintenanceItem("airFilter", "Lọc gió", 10_000, 12, "Thay loc gio"),
            MaintenanceItem("sparkPlug", "Bugi", 10_000, 24, "Thay bugi"),
            MaintenanceItem("brakePads", "Má phanh (kiểm tra)", 6_000, 12, "Kiem tra ma phanh"),
            MaintenanceItem("belt", "Dây curoa + bi nồi", 20_000, 24, "Thay day curoa"),
            MaintenanceItem("coolant", "Nước làm mát", 20_000, 24, "Thay nuoc mat"),
            MaintenanceItem("brakeFluid", "Dầu phanh", null, 24, "Thay dau phanh"),
        )

        /** English name + TFT text of the built-in items (the stored `name`/`tftText` are Vietnamese). */
        private val english = mapOf(
            "engineOil" to ("Engine oil" to "Change engine oil"),
            "gearOil" to ("Gear oil" to "Change gear oil"),
            "airFilter" to ("Air filter" to "Change air filter"),
            "sparkPlug" to ("Spark plug" to "Change spark plug"),
            "brakePads" to ("Brake pads (check)" to "Check brake pads"),
            "belt" to ("Drive belt + rollers" to "Change drive belt"),
            "coolant" to ("Coolant" to "Change coolant"),
            "brakeFluid" to ("Brake fluid" to "Change brake fluid"),
        )
    }
}

data class MaintenanceStatus(
    val level: Level,
    /** Negative = overdue by that much. */
    val kmLeft: Int?,
    val daysLeft: Int?,
    /** 0 = just done, 1 = due. */
    val progress: Double,
) {
    /** Ordered by urgency. */
    enum class Level { UNKNOWN, OK, SOON, DUE }
}

object MaintenancePlanner {
    /** "Soon" within 10 % of the interval (at least 300 km / 14 days). */
    fun status(item: MaintenanceItem, odometer: Int?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): MaintenanceStatus {
        var kmLeft: Int? = null
        var daysLeft: Int? = null
        var progress = 0.0
        var soon = false
        if (item.intervalKm != null && item.lastKm != null && odometer != null) {
            val left = item.lastKm + item.intervalKm - odometer
            kmLeft = left
            progress = max(progress, (odometer - item.lastKm).toDouble() / item.intervalKm)
            soon = left <= max(300, item.intervalKm / 10)
        }
        if (item.intervalMonths != null && item.lastDate != null) {
            val due = Instant.ofEpochMilli(item.lastDate).atZone(zone).plusMonths(item.intervalMonths.toLong()).toInstant().toEpochMilli()
            val left = floor((due - nowMs).toDouble() / DAY_MS).toInt()
            daysLeft = left
            val total = due - item.lastDate
            if (total > 0) progress = max(progress, (nowMs - item.lastDate).toDouble() / total)
            soon = soon || left <= max(14, item.intervalMonths * 3)
        }
        val level = when {
            kmLeft == null && daysLeft == null -> MaintenanceStatus.Level.UNKNOWN
            (kmLeft ?: 1) <= 0 || (daysLeft ?: 1) <= 0 -> MaintenanceStatus.Level.DUE
            soon -> MaintenanceStatus.Level.SOON
            else -> MaintenanceStatus.Level.OK
        }
        return MaintenanceStatus(level, kmLeft, daysLeft, min(max(progress, 0.0), 1.5))
    }
}
