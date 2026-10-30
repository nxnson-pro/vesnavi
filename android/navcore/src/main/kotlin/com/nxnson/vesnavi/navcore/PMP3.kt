package com.nxnson.vesnavi.navcore

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Piaggio "PMP3" dashboard protocol over BLE (Vespa TFT / MIA generation).
 * Field-level notes: docs/pmp3-protocol.md.
 */
object PMP3 {
    /** GATT service (Automation IO, 16-bit) and its two characteristics. */
    const val SERVICE_UUID = "00001815-0000-1000-8000-00805f9b34fb"
    /** Phone → scooter (write with response). */
    const val WRITE_CHARACTERISTIC_UUID = "00002a56-0000-1000-8000-00805f9b34fb"
    /** Scooter → phone (notify). */
    const val NOTIFY_CHARACTERISTIC_UUID = "00002a5a-0000-1000-8000-00805f9b34fb"

    /** Prefix the phone puts in front of every dashboard (0xC6) packet. */
    val dashHeader: Bytes = listOf(0x55, 0x01, 0x05)
    const val DASH_TAG = 0xC6
    /** Largest dash payload the original client allows (excluding header). */
    const val MAX_DASH_PAYLOAD = 120

    // Pairing / authentication

    /**
     * Response to a `00 00 c0 c1 c2 c3` challenge: two XTEA-style rounds over the
     * 8-byte pairing key, keyed by the 4 challenge bytes.
     */
    fun authResponse(key: Bytes, challenge: Bytes): Bytes {
        require(challenge.size >= 4)
        val k = if (key.size == 8) key else List(8) { 0 }
        val c = challenge.take(4)
        var v0 = be32(k.subList(0, 4))
        var v1 = be32(k.subList(4, 8))
        var sum = 0
        repeat(2) {
            val n0 = v0 + ((c[sum and 3] + sum) xor (((v1 shl 4) xor (v1 ushr 5)) + v1))
            sum += 0x9E3779B9.toInt()
            val n1 = v1 + ((c[(sum ushr 11) and 3] + sum) xor (((n0 shl 4) xor (n0 ushr 5)) + n0))
            v0 = n1
            v1 = n0
        }
        return bytesBE(v0 + c[2]) + bytesBE(v1 + c[3])
    }

    /** 8-byte client identifier sent first on every connection ("0000" + hex(millis)). */
    fun makeDeviceID(millis: Long): Bytes {
        var hex = "0000" + millis.toString(16).uppercase()
        if (hex.length % 2 == 1) hex = hex.dropLast(1) + "0" + hex.last()
        var bytes = Hex.decode(hex) ?: emptyList()
        if (bytes.size < 8) bytes = List(8 - bytes.size) { 0 } + bytes
        return bytes.takeLast(8)
    }

    /** Random 8-byte secret handed to the scooter when it asks for a new pairing (`00 00 00 0F`). */
    fun makeKey(random: Random = Random.Default): Bytes {
        while (true) {
            val key = List(8) { random.nextInt(256) }
            if (key[0] != 0 && key[1] != 0x0F) return key
        }
    }

    // Dashboard packet

    /** TFT pictogram codes. */
    object Icon {
        const val STRAIGHT = 1
        const val U_TURN_CLOCKWISE = 2
        const val U_TURN_COUNTER_CLOCKWISE = 3
        const val KEEP_RIGHT = 4
        const val SLIGHT_RIGHT = 5
        const val RIGHT = 6
        const val SHARP_RIGHT = 7
        const val MERGE = 8
        const val KEEP_LEFT = 9
        const val SLIGHT_LEFT = 10
        const val LEFT = 11
        const val SHARP_LEFT = 12
        const val RAMP_RIGHT = 13
        const val RAMP_LEFT = 14
        const val EXIT_RIGHT = 15
        const val EXIT_LEFT = 16
        /**
         * Counter-clockwise roundabout (right-hand traffic, e.g. Vietnam): 100 + exit direction
         * 0 U-turn, 1 sharp right, 2 right, 3 slight right, 4 straight, 5 slight left, 6 left, 7 sharp left.
         */
        const val ROUNDABOUT_CCW_STRAIGHT = 104
        const val CONTINUE_ON = 200
        const val ARRIVE = 201
        /** Shown while guidance is unavailable / recalculating. */
        const val RECALCULATING = 203
    }

    /** Icon for right-hand traffic (Vietnam): U-turns go counter-clockwise, roundabouts too. */
    fun icon(maneuver: Maneuver): Int = when (maneuver) {
        Maneuver.Depart -> Icon.CONTINUE_ON
        Maneuver.Straight -> Icon.STRAIGHT
        Maneuver.SlightLeft -> Icon.SLIGHT_LEFT
        Maneuver.Left -> Icon.LEFT
        Maneuver.SharpLeft -> Icon.SHARP_LEFT
        Maneuver.SlightRight -> Icon.SLIGHT_RIGHT
        Maneuver.Right -> Icon.RIGHT
        Maneuver.SharpRight -> Icon.SHARP_RIGHT
        Maneuver.KeepLeft -> Icon.KEEP_LEFT
        Maneuver.KeepRight -> Icon.KEEP_RIGHT
        Maneuver.UTurnLeft -> Icon.U_TURN_COUNTER_CLOCKWISE
        Maneuver.UTurnRight -> Icon.U_TURN_CLOCKWISE
        is Maneuver.Roundabout -> Icon.ROUNDABOUT_CCW_STRAIGHT
        Maneuver.Arrive -> Icon.ARRIVE
    }

    /** Like [icon] for a maneuver, but roundabouts get the pictogram of the actual exit direction. */
    fun icon(state: NavigationState): Int {
        val d = state.roundaboutTurnDegrees
        if (state.maneuver is Maneuver.Roundabout && d != null) return RouteAnnotator.roundaboutIcon(d)
        return icon(state.maneuver)
    }

    /** How the road-name field (tag 4) is prefixed; depends on the scooter model. */
    enum class TextMode(val prefix: Bytes) {
        PLAIN(emptyList()), // 04 <text> 00
        MODE_FF(listOf(0xFF, 0x00)), // 04 FF 00 <text> 00
        MODE_02(listOf(0x02, 0x00)), // 04 02 00 <text> 00
        MODE_01(listOf(0x01, 0x00)), // 04 01 00 <text> 00
    }

    data class DashOptions(
        val textMode: TextMode = TextMode.PLAIN,
        val maxTextLength: Int = 20,
        val uppercase: Boolean = false,
    )

    /** Full write (header + 0xC6 payload). Distances in meters. */
    fun dashPacket(
        icon: Int,
        distanceToTurn: Int,
        distanceToDestination: Int,
        minutesToDestination: Int,
        roadName: String?,
        options: DashOptions = DashOptions(),
        includeHeader: Boolean = true,
    ): Bytes {
        var p = listOf(DASH_TAG)
        if (icon != 0) {
            // tag 1: icon, 2 reserved, lane field (unused → 0), 24-bit lane mask (unused → FF FF FF)
            p = p + listOf(0x01, icon, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00)
        }
        if (minutesToDestination > 0) {
            // tag 3: minutes (16-bit), distance to destination (24-bit), distance to turn (24-bit)
            p = p + listOf(0x03) + be16(minutesToDestination) + be24(distanceToDestination) + be24(distanceToTurn) + listOf(0x00)
        }
        val text = encodeText(roadName ?: "", options.maxTextLength, options.uppercase)
        if (text.isNotEmpty() || options.textMode != TextMode.PLAIN) {
            p = p + listOf(0x04) + options.textMode.prefix + text + listOf(0x00)
        }
        if (p.size > MAX_DASH_PAYLOAD) p = p.take(MAX_DASH_PAYLOAD)
        return if (includeHeader) dashHeader + p else p
    }

    /** `includeHeader = false` gives the bare 0xC6 payload (PMP2/Cobo sends it unframed). */
    fun dashPacket(state: NavigationState, options: DashOptions = DashOptions(), includeHeader: Boolean = true): Bytes {
        if (state.isOffRoute) {
            return dashPacket(Icon.RECALCULATING, 0, 0, 0, "Dang tim duong", options, includeHeader)
        }
        val minutes = if (state.hasArrived) 1 else max(1, ceil(state.remainingDuration / 60).toInt())
        return dashPacket(
            icon = if (state.hasArrived) Icon.ARRIVE else icon(state),
            distanceToTurn = max(1, state.distanceToManeuver.roundToInt()),
            distanceToDestination = max(1, state.remainingDistance.roundToInt()),
            minutesToDestination = minutes,
            roadName = state.streetName,
            options = options,
            includeHeader = includeHeader,
        )
    }

    /** The TFT font is ASCII-only: strip Vietnamese diacritics, map đ/Đ, drop anything else. */
    fun encodeText(s: String, maxLength: Int, uppercase: Boolean = false): Bytes {
        val source = if (uppercase) s.uppercase() else s
        val out = mutableListOf<Int>()
        val decomposed = Normalizer.normalize(source, Normalizer.Form.NFD)
        var i = 0
        while (i < decomposed.length && out.size < maxLength) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            when {
                cp == 'đ'.code -> out += 'd'.code
                cp == 'Đ'.code -> out += 'D'.code
                cp in 0x20 until 0x7F -> out += cp
            }
        }
        return out
    }

    fun be16(v: Int): Bytes {
        val x = max(0, min(v, 0xFFFF))
        return listOf((x shr 8) and 0xFF, x and 0xFF)
    }

    /** 24-bit big-endian; non-positive values mean "unknown" (FF FF FF) like the reference client. */
    fun be24(v: Int): Bytes {
        if (v <= 0) return listOf(0xFF, 0xFF, 0xFF)
        val x = min(v, 0xFFFFFE)
        return listOf((x shr 16) and 0xFF, (x shr 8) and 0xFF, x and 0xFF)
    }

    private fun be32(b: Bytes): Int = b.fold(0) { acc, v -> (acc shl 8) or v }

    private fun bytesBE(v: Int): Bytes = listOf((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
}

object Hex {
    fun encode(bytes: Bytes, separator: String = " "): String = bytes.joinToString(separator) { "%02X".format(it) }

    /** Accepts "0A1B", "0a 1b", "0A-1B"…; null on odd length or bad digit. */
    fun decode(s: String): Bytes? {
        val digits = s.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (digits.length % 2 != 0 || digits.length != s.count { it !in " -:," }) return null
        return digits.chunked(2).map { it.toInt(16) }
    }
}

/** Persisted per scooter: our client ID and the secret the scooter accepted. */
data class PMP3Pairing(
    val deviceID: Bytes,
    /** null until the scooter has asked for a new pairing (`00 00 00 0F`). */
    val key: Bytes? = null,
)

/**
 * PMP3 handshake state machine, transport-free: feed it notifications from the
 * scooter, write back whatever it returns (see docs/pmp3-protocol.md).
 */
class PMP3Session(
    pairing: PMP3Pairing?,
    val phoneName: String,
    millis: Long = System.currentTimeMillis(),
    private val makeKey: () -> Bytes = { PMP3.makeKey() },
) {
    sealed class Event {
        data class PairingChanged(val pairing: PMP3Pairing) : Event()
        /** Scooter asked for a fresh key — the TFT may ask the rider to confirm. */
        object NewPairingRequested : Event()
        data class AuthResult(val success: Boolean) : Event()
        object Authenticated : Event()
        /** Dashboard accepts commands (nav packets) when true. */
        data class Ready(val ready: Boolean) : Event()
        data class Firmware(val variant: Int, val firmware: String, val hardware: String) : Event()
        data class Button(val code: Int) : Event()
        data class Unhandled(val bytes: Bytes) : Event()
    }

    data class Output(val writes: List<Bytes> = emptyList(), val events: List<Event> = emptyList())

    var pairing: PMP3Pairing = pairing ?: PMP3Pairing(PMP3.makeDeviceID(millis))
        private set
    var isReady = false
        private set
    private var variant = 0
    private var firmware = ""
    private var pendingPairingEvent = pairing == null

    /** First write after notifications are enabled: our 8-byte client ID, unframed. */
    fun start(): Output {
        isReady = false
        val events = if (pendingPairingEvent) listOf(Event.PairingChanged(pairing)) else emptyList()
        pendingPairingEvent = false
        return Output(listOf(pairing.deviceID), events)
    }

    fun handle(b: Bytes): Output {
        fun has(vararg prefix: Int) = b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it] }
        val writes = mutableListOf<Bytes>()
        val events = mutableListOf<Event>()

        when {
            // Scooter asks who we are → phone name (ASCII, ≤ 20 bytes).
            has(0x00, 0x00, 0x00, 0x0E) -> writes += listOf(0x00, 0x0E) + PMP3.encodeText(phoneName, 20)
            has(0x00, 0x00, 0x00, 0x0F) -> {
                // New pairing: hand over a fresh secret.
                if (pairing.key == null) {
                    pairing = pairing.copy(key = makeKey())
                    events += Event.PairingChanged(pairing)
                }
                events += Event.NewPairingRequested
                writes += listOf(0x00, 0x01) + (pairing.key ?: emptyList())
            }
            b.size >= 6 && b[0] == 0 && b[1] == 0 && b[2] != 0 && b[3] != 0x0F -> {
                // Known device: prove we hold the key.
                val key = pairing.key ?: List(8) { 0 }
                writes += listOf(0x00, 0x01) + PMP3.authResponse(key, b.subList(2, 6))
            }
            has(0x00, 0x01) -> events += Event.AuthResult(b.drop(2).any { it != 0 })
            has(0x00, 0x00, 0x00, 0x4F) -> events += Event.Authenticated
            has(0x45) -> {
                isReady = b.size >= 5 && b[4] == 1
                events += Event.Ready(isReady)
            }
            has(0x00, 0x03) -> writes += listOf(0x00, 0x03, 0x41) + List(13) { 0 }
            has(0x40, 0x41) -> writes += listOf(0x44, 0x34) + List(14) { 0 }
            has(0x44, 0x34) -> {
                variant = if (b.size > 2) b[2] else 0
                writes += listOf(0x44, 0x38)
            }
            has(0x44, 0x38) -> {
                firmware = version(b)
                writes += listOf(0x44, 0x39)
            }
            has(0x44, 0x39) -> events += Event.Firmware(variant, firmware, version(b))
            b.size >= 3 && b[0] == 0x4B && (b[1] == 0x4C || b[1] == 0x53) -> events += Event.Button(b[2])
            else -> events += Event.Unhandled(b)
        }
        return Output(writes, events)
    }

    companion object {
        /** Sent once ~3 s after the dashboard reports ready. */
        val heartbeat: Bytes = listOf(0x48, 0x42)

        private fun version(b: Bytes) = if (b.size >= 4) "${b[2]}.${b[3]}" else "?"
    }
}
