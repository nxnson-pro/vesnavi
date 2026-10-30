package com.nxnson.vesnavi.navcore

import kotlin.math.min

/**
 * Live engine/vehicle data streamed by a PMP2 (Cobo) scooter after the 0x28 subscription.
 * `null` = not received yet (or not part of the frame).
 */
data class ScooterTelemetry(
    val rpm: Int? = null,
    /** Engine coolant, °C. */
    val coolantC: Int? = null,
    /** Intake air, °C. */
    val intakeAirC: Int? = null,
    /** Outside temperature as shown on the TFT, °C. */
    val ambientC: Int? = null,
    val batteryVolts: Double? = null,
    val odometerKm: Int? = null,
    val rearWheelKmh: Double? = null,
    /** Fuel gauge bars; 0x589 start 53, 3 bits. Read 5 with the tank about 60–70 % full. */
    val fuelBars: Int? = null,
    /** Speed shown on the TFT speedometer (reads ~10 % above the wheel speed, like any speedo). */
    val dashboardSpeedKmh: Int? = null,
    val frontWheelKmh: Double? = null,
    /** Throttle opening, 0…100. */
    val throttle: Int? = null,
    val fuelLitersPerHour: Double? = null,
    /** Traction control (ASR) mode as reported by the ECU. */
    val asrMode: Int? = null,
    /** Engine warning (MIL) lamp state, 0 = off. */
    val milLamp: Int? = null,
    /** Clock shown on the TFT. */
    val clockHour: Int? = null,
    val clockMinute: Int? = null,
) {
    val engineRunning: Boolean get() = (rpm ?: 0) > 300
}

/**
 * Decodes the scooter's `C0 80 …` data frames.
 *
 * The frame carries one value per signal of [Cobo.structuredSubscribePayload], in the same order:
 * signals of ≤ 8 bits take one byte, 16-bit signals two bytes (big-endian), followed by the
 * four 2-byte analog inputs requested by the payload trailer. Scales not marked as verified in
 * `docs/pmp2-cobo-protocol.md` are unconfirmed.
 */
object CoboTelemetry {
    data class Signal(val canID: Int, val start: Int, val length: Int) {
        val byteCount get() = if (length > 8) 2 else 1
    }

    /**
     * Signal list parsed from the subscription payload: 3-byte header, 8-byte entries
     * `00 00 00 00 idHi idLo start length`, then a trailer of 3-byte analog requests.
     */
    val signals: List<Signal> = run {
        val p = Cobo.structuredSubscribePayload
        val out = mutableListOf<Signal>()
        var i = 3
        while (i + 8 <= p.size && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 0) {
            out += Signal((p[i + 4] shl 8) or p[i + 5], p[i + 6], p[i + 7])
            i += 8
        }
        out
    }

    private const val ANALOG_BYTES = 8
    val frameDataLength: Int get() = signals.sumOf { it.byteCount } + ANALOG_BYTES

    fun isDataFrame(b: Bytes) = b.size >= 2 && b[0] == 0xC0 && b[1] == 0x80

    /** Updates `base` with every value present in `frame`; null if it is not a full data frame. */
    fun parse(frame: Bytes, base: ScooterTelemetry = ScooterTelemetry()): ScooterTelemetry? {
        if (!isDataFrame(frame) || frame.size - 2 < frameDataLength - ANALOG_BYTES) return null
        var t = base
        val odo = intArrayOf(0, 0, 0)
        var offset = 2
        for (s in signals) {
            val raw = if (s.byteCount == 2) (frame[offset] shl 8) or frame[offset + 1] else frame[offset]
            offset += s.byteCount
            t = when (s.canID to s.start) {
                0x590 to 48 -> t.copy(batteryVolts = raw / 10.0)
                0x590 to 40 -> t.copy(ambientC = raw - 20)
                0x589 to 48 -> t.copy(clockHour = raw)
                0x589 to 40 -> t.copy(clockMinute = raw)
                0x589 to 16 -> t.also { odo[1] = raw }
                0x589 to 8 -> t.also { odo[2] = raw }
                0x589 to 0 -> t.also { odo[0] = raw }
                0x180 to 24 -> t.copy(intakeAirC = raw - 40)
                0x180 to 16 -> t.copy(coolantC = raw - 40)
                0x260 to 24 -> t.copy(rearWheelKmh = raw / 100.0)
                0x220 to 0 -> t.copy(dashboardSpeedKmh = raw)
                0x589 to 53 -> t.copy(fuelBars = raw)
                0x260 to 8 -> t.copy(frontWheelKmh = raw / 100.0)
                0x130 to 61 -> t.copy(asrMode = raw)
                0x130 to 24 -> t.copy(fuelLitersPerHour = raw * 0.05)
                0x100 to 42 -> t.copy(milLamp = raw)
                0x120 to 24 -> t.copy(throttle = min(100, raw))
                0x120 to 16 -> t.copy(rpm = raw)
                else -> t
            }
        }
        // Odometer bytes: start 16 = low, 8 = mid, 0 = high.
        return t.copy(odometerKm = odo[0] * 65536 + odo[2] * 256 + odo[1])
    }
}
