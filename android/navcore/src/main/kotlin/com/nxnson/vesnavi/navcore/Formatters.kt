package com.nxnson.vesnavi.navcore

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Instruction text + distance display (Vietnamese or English), generated locally so every
 * routing provider (GraphHopper, OSRM, …) reads the same way.
 */
object InstructionFormatter {
    fun text(maneuver: Maneuver, street: String?, language: Language = Language.VI): String {
        val name = street?.trim()?.ifEmpty { null }
        val onto = language.pick("vào", "onto")
        return when (maneuver) {
            Maneuver.Depart ->
                name?.let { language.pick("Bắt đầu trên $it", "Start on $it") } ?: language.pick("Bắt đầu", "Start")
            Maneuver.Arrive -> language.pick("Đến nơi", "Arrived")
            is Maneuver.Roundabout -> {
                val base = maneuver.exit?.let { language.pick("Vào vòng xuyến, ra lối thứ $it", "At the roundabout, take exit $it") }
                    ?: language.pick("Đi qua vòng xuyến", "Go through the roundabout")
                name?.let { "$base $onto $it" } ?: base
            }
            else -> {
                val verb = verb(maneuver, language)
                name?.let { "$verb $onto $it" } ?: verb
            }
        }
    }

    private fun verb(m: Maneuver, language: Language): String = when (m) {
        Maneuver.Straight -> language.pick("Đi thẳng", "Continue straight")
        Maneuver.SlightLeft -> language.pick("Chếch sang trái", "Bear left")
        Maneuver.Left -> language.pick("Rẽ trái", "Turn left")
        Maneuver.SharpLeft -> language.pick("Rẽ gắt sang trái", "Sharp left")
        Maneuver.SlightRight -> language.pick("Chếch sang phải", "Bear right")
        Maneuver.Right -> language.pick("Rẽ phải", "Turn right")
        Maneuver.SharpRight -> language.pick("Rẽ gắt sang phải", "Sharp right")
        Maneuver.KeepLeft -> language.pick("Đi về bên trái", "Keep left")
        Maneuver.KeepRight -> language.pick("Đi về bên phải", "Keep right")
        Maneuver.UTurnLeft, Maneuver.UTurnRight -> language.pick("Quay đầu", "Make a U-turn")
        else -> language.pick("Tiếp tục", "Continue")
    }
}

object DistanceFormatter {
    /**
     * Rounds the way car navigators do, so the number on the dashboard doesn't flicker:
     * < 100 m → 10 m steps, < 1 km → 50 m steps, otherwise 100 m steps.
     */
    fun rounded(meters: Double): Double {
        val m = max(0.0, meters)
        val step = if (m < 100) 10.0 else if (m < 1000) 50.0 else 100.0
        return Math.round(m / step) * step
    }

    /** "40 m", "350 m", "1,2 km" (English: "1.2 km"), "12 km" */
    fun text(meters: Double, language: Language = Language.VI): String {
        val r = rounded(meters)
        if (r < 1000) return "${r.toInt()} m"
        val km = r / 1000
        if (km >= 10) return "${km.roundToInt()} km"
        val tenths = Math.round(km * 10).toInt()
        return "${tenths / 10}${language.pick(",", ".")}${tenths % 10} km"
    }
}
