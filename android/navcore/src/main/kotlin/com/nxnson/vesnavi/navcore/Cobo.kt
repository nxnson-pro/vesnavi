package com.nxnson.vesnavi.navcore

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Cobo" link — the BT-ROUTER module of PMP2-generation Vespas, over BLE.
 * See docs/pmp2-cobo-protocol.md.
 *
 * Two BLE links are involved, in this order:
 * 1. **Key link** — phone = central. The scooter notifies a challenge `05 04 00 c0 c1 c2 c3`,
 *    the phone writes [keyReply] and the scooter notifies a 6-byte answer. The phone keeps
 *    the answer, closes the link and starts advertising.
 * 2. **Data link** — phone = peripheral (GATT server with the Cobo service). The scooter connects,
 *    subscribes to indications and writes `05 04 00`; the phone indicates the answer, then runs
 *    the DQ init sequence and streams 0xC6 dash packets.
 */
object Cobo {
    const val SERVICE_UUID = "24839ac4-8d7e-515c-aa42-267340cf2339"
    /** Key link: the scooter notifies on it. Data link: the phone indicates on it. */
    const val NOTIFY_CHARACTERISTIC_UUID = "1734594a-b8e7-5b1a-b6b1-dd5243059a57"
    /** Data link: the scooter writes commands to it. */
    const val WRITE_CHARACTERISTIC_UUID = "9b00ace7-fb0b-59b0-cbe9-aaee0a26e1a3"

    /** The scooter writes this on the data link to ask for the answer. */
    val answerRequest: Bytes = listOf(0x05, 0x04, 0x00)

    /** Constant 19 bytes appended to the 4-byte key (identical in every captured session). */
    val keyReplyTail: Bytes = listOf(
        0x03, 0x6D, 0x3B, 0x33, 0x14, 0x92, 0x85, 0xBC, 0x4A, 0xA4,
        0x4C, 0xC3, 0xAA, 0x75, 0x20, 0x6C, 0xC3, 0xAA, 0x75,
    )

    /**
     * Key-link reply to the challenge `05 04 00 c0 c1 c2 c3`: the "FEDE" key + [keyReplyTail],
     * written without response to 9B00.
     */
    fun keyReply(challenge: Bytes): Bytes {
        val c = challenge
        val k0 = c[1] xor 0x46
        val k1 = (c[0] xor c[1]) and 0x45
        val k2 = ((c[3] xor c[2]) and 0x44).inv() and 0xFF
        val k3 = c[2] xor 0x45
        return listOf(k0, k1, k2, k3) + keyReplyTail
    }

    /** Most packet bytes one frame carries (after its header byte). */
    const val MAX_FRAME_DATA = 127

    /**
     * DQ command packet (`44 51 00 cmd len payload checksum`) split into Cobo frames:
     * one `C0` frame, or `C1`, `D0`…, `En` when the packet is longer than 127 bytes.
     */
    fun frames(cmd: Int, payload: Bytes = emptyList()): List<Bytes> {
        val packet = mutableListOf(0x44, 0x51, 0x00, cmd)
        if (payload.size <= 127) {
            packet += payload.size
        } else {
            val len = payload.size or 0x8000
            packet += (len shr 8) and 0xFF
            packet += len and 0xFF
        }
        packet += payload
        packet += packet.sum() and 0xFF

        if (packet.size <= MAX_FRAME_DATA) return listOf(listOf(0xC0) + packet)
        val out = mutableListOf<Bytes>()
        var index = 0
        var start = 0
        while (start < packet.size) {
            val end = min(start + MAX_FRAME_DATA, packet.size)
            val header = when {
                index == 0 -> 0xC1
                end == packet.size -> 0xE0 or ((index - 1) and 0x0F)
                else -> 0xD0 or ((index - 1) and 0x0F)
            }
            out += listOf(header) + packet.subList(start, end)
            start = end
            index++
        }
        return out
    }

    /** 0x70 payload. */
    val initPayload: Bytes = listOf(0x00, 0x01, 0x01, 0x03, 0x02, 0x00, 0x00, 0x01, 0x01, 0x01, 0x04, 0x02, 0x00, 0xFF)
    /** 0x23 payload. */
    val subscribePayload: Bytes = listOf(0x01, 0x00)
    /** 0x28 payload (CAN signal subscription), 407 bytes, constant across sessions. */
    val structuredSubscribePayload: Bytes = Hex.decode(
        listOf(
            "00010E000000000590300800000000059028080000000005901701000000000590160100000000059015010000000005900F",
            "010000000005900E010000000005892F010000000005892E0100000000058938080000000005893503000000000589300500",
            "0000000589280600000000058910080000000005890808000000000589000800000000030018080000000003000808000000",
            "0001803010000000000180180800000000018010080000000002200901000000000220080100000000022000080000000002",
            "60181000000000026008100000000001303D02000000000130180800000000010030010000000001002A020000000001002E",
            "0100000000012018080000000001201010000000000701380800000000070130080000000007012808000000000701200800",
            "0000000701180800000000070110080000000007010808000000000701000800000000070038080000000007003008000000",
            "0007002808000000000700200800000000070018080000000007001008000000000700080800000000070000080103090103",
            "08010307010306",
        ).joinToString(""),
    ) ?: emptyList()

    /**
     * Parameters read once per session with 0x27 (the scooter answers with 0x30),
     * first ~2 s after init, retried every ~10 s until answered, then the next one.
     */
    val readIDs: List<Bytes> = listOf(listOf(0xFE, 0x0B), listOf(0xFF, 0x06))

    /** 0x27 read-request payload, e.g. `01 04 09 00 00 FE 0B 55 55 55 55 55`. */
    fun readRequestPayload(id: Bytes): Bytes = listOf(0x01, 0x04, 0x09, 0x00, 0x00) + id + listOf(0x55, 0x55, 0x55, 0x55, 0x55)

    /**
     * 0xC6 dash payload: tags 01, 02, 03 and 04 are always present.
     * `navigating` appends tag `06 04 00` ("navigation running"), needed on every packet while a route
     * is active on the GTS Supertech — without it the TFT keeps its normal screen and ignores the arrow.
     */
    fun dashPayload(
        icon: Int,
        distanceToTurn: Int,
        distanceToDestination: Int,
        minutesToDestination: Int,
        text: String,
        navigating: Boolean = true,
        speedLimit: Int = 0,
    ): Bytes {
        val p = mutableListOf(PMP3.DASH_TAG)
        p += listOf(0x01, icon, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00)
        // Tag 02: speed limit in km/h, 0 = none.
        p += listOf(0x02, max(0, min(speedLimit, 250)), 0x00)
        p += 0x03
        p += PMP3.be16(minutesToDestination)
        p += PMP3.be24(distanceToDestination)
        p += PMP3.be24(distanceToTurn)
        p += 0x00
        p += 0x04
        p += PMP3.encodeText(text, 20, uppercase = true)
        p += 0x00
        if (navigating) p += navigationStarted
        return p
    }

    /** Tag 06: `06 04 00` navigation running, `06 03 00` navigation stopped. */
    val navigationStarted: Bytes = listOf(0x06, 0x04, 0x00)
    val navigationStopped: Bytes = listOf(0x06, 0x03, 0x00)

    /** What is streamed while no route is active. */
    val idleDash: Bytes = dashPayload(0, 0, 0, 0, "NO ADDRESS", navigating = false)
    /** Sent once when a route ends, so the TFT leaves the navigation screen. */
    val stopDash: Bytes = idleDash + navigationStopped

    /**
     * Two-line pop-up: `C2 01 bg line1 ';' line2 00`, sent in place of the dash packet for
     * 9 consecutive packets. `background` 13 suits the GTS 300 Supertech.
     */
    fun popupPayload(line1: String, line2: String, background: Int = 13): Bytes =
        listOf(0xC2, 0x01, background) +
            PMP3.encodeText(line1.replace(";", ","), 20, uppercase = true) +
            listOf(0x3B) +
            PMP3.encodeText(line2.replace(";", ","), 20, uppercase = true) +
            listOf(0x00)

    const val POPUP_REPEATS = 9

    /** Idle text line with engine data, e.g. "NUOC 85C  AQ 13.8V" / "COOL 85C  BAT 13.8V" (≤ 20 ASCII chars). */
    fun infoText(t: ScooterTelemetry, language: Language = Language.VI): String? {
        val parts = mutableListOf<String>()
        if (t.coolantC != null && t.engineRunning) parts += "${language.pick("NUOC", "COOL")} ${t.coolantC}C"
        t.batteryVolts?.let { parts += String.format(Locale.US, "%s %.1fV", language.pick("AQ", "BAT"), it) }
        if (!t.engineRunning && t.ambientC != null) parts += "${t.ambientC}C"
        return if (parts.isEmpty()) null else parts.joinToString("  ")
    }

    /**
     * Idle dash carrying `text` on the street-name line; `navigationScreen` also sends `06 04 00`
     * (the TFT only shows the line while its navigation screen is active).
     */
    fun infoDash(text: String, navigationScreen: Boolean): Bytes =
        dashPayload(0, 0, 0, 0, text, navigating = navigationScreen)

    fun dashPayload(state: NavigationState, language: Language = Language.VI): Bytes {
        if (state.isOffRoute) {
            return dashPayload(PMP3.Icon.RECALCULATING, 0, 0, 0, language.pick("Dang tim duong", "Rerouting"))
        }
        if (state.hasArrived) {
            return dashPayload(PMP3.Icon.ARRIVE, 1, 1, 1, language.pick("Den noi", "Arrived"))
        }
        return dashPayload(
            icon = PMP3.icon(state),
            distanceToTurn = max(1, state.distanceToManeuver.roundToInt()),
            distanceToDestination = max(1, state.remainingDistance.roundToInt()),
            minutesToDestination = max(1, ceil(state.remainingDuration / 60).toInt()),
            text = dashText(state, language),
            speedLimit = state.speedLimit ?: 0,
        )
    }

    /**
     * Street name, or the short instruction ("RE PHAI") when the road has no name —
     * an empty text line looks broken on the TFT.
     */
    fun dashText(state: NavigationState, language: Language = Language.VI): String {
        val street = state.streetName?.trim()
        if (!street.isNullOrEmpty()) return street
        return InstructionFormatter.text(state.maneuver, null, language)
    }
}

/** Key link (phone = central): answer the challenge with [Cobo.keyReply], then keep the scooter's 6-byte answer. */
class CoboKeyFetch {
    sealed class Event {
        /** Write `reply` to 9B00 (without response); the scooter then notifies the 6-byte answer. */
        data class Challenge(val bytes: Bytes, val reply: Bytes) : Event()
        data class Answer(val bytes: Bytes) : Event()
        data class Ignored(val bytes: Bytes) : Event()
    }

    var answer: Bytes? = null
        private set
    private var challenge: Bytes? = null

    fun handle(b: Bytes): Event {
        if (b.size == 7 && b[0] == 0x05 && b[1] == 0x04 && b[2] == 0x00) {
            // A new challenge always restarts the exchange.
            challenge = b
            answer = null
            return Event.Challenge(b, Cobo.keyReply(b.drop(3)))
        }
        if (challenge != null && answer == null && b.size == 6) {
            answer = b
            return Event.Answer(b)
        }
        return Event.Ignored(b)
    }
}

/**
 * Data link (phone = GATT server): answers the scooter's writes.
 * No F0 acks on BLE — ATT write responses and indication confirmations replace them.
 */
class CoboServerSession(private val answer: Bytes?) {
    enum class Stage { WAITING_FOR_REQUEST, INITIALIZING, READY }

    sealed class Event {
        object AnswerSent : Event()
        /** The scooter asked for the answer but the key link didn't provide one. */
        object MissingAnswer : Event()
        data class Command(val cmd: Int, val payload: Bytes) : Event()
        data class CanData(val bytes: Bytes) : Event()
        object Ready : Event()
        data class Unhandled(val bytes: Bytes) : Event()
    }

    data class Output(val indications: List<Bytes> = emptyList(), val events: List<Event> = emptyList())

    var stage = Stage.WAITING_FOR_REQUEST
        private set
    /** 0x27 reads not answered yet (front = the one in flight). */
    var pendingReads: List<Bytes> = Cobo.readIDs
        private set

    fun handleWrite(b: Bytes): Output {
        val indications = mutableListOf<Bytes>()
        val events = mutableListOf<Event>()
        when {
            b == Cobo.answerRequest -> {
                if (answer == null) {
                    events += Event.MissingAnswer
                } else {
                    indications += answer
                    indications += Cobo.frames(0x70, Cobo.initPayload)
                    events += Event.AnswerSent
                    stage = Stage.INITIALIZING
                }
            }
            b.size >= 6 && b[0] == 0xC0 && b[1] == 0x44 && b[2] == 0x51 -> {
                val cmd = b[4]
                events += Event.Command(cmd, b.drop(6).take(b[5]))
                when (cmd) {
                    0x70 -> indications += Cobo.frames(0x01)
                    0x02 -> {
                        indications += Cobo.frames(0x06)
                        indications += Cobo.frames(0x10)
                    }
                    0x30 -> {
                        // Answer to our 0x27 read: drop it and ask for the next parameter straight away.
                        val first = pendingReads.firstOrNull()
                        if (first != null && b.size >= 8 && contains(b.drop(6), first)) {
                            pendingReads = pendingReads.drop(1)
                            indications += readRequestFrames()
                        }
                    }
                    0x11 -> {
                        indications += Cobo.frames(0x23, Cobo.subscribePayload)
                        indications += Cobo.frames(0x28, Cobo.structuredSubscribePayload)
                        stage = Stage.READY
                        events += Event.Ready
                    }
                }
            }
            b.size >= 2 && b[0] == 0xC0 && b[1] == 0x80 -> events += Event.CanData(b)
            else -> events += Event.Unhandled(b)
        }
        return Output(indications, events)
    }

    /** 0x27 frames for the next unanswered read, or nothing once both were answered. */
    fun readRequestFrames(): List<Bytes> {
        if (stage != Stage.READY) return emptyList()
        val id = pendingReads.firstOrNull() ?: return emptyList()
        return Cobo.frames(0x27, Cobo.readRequestPayload(id))
    }

    private fun contains(haystack: Bytes, needle: Bytes): Boolean =
        haystack.size >= needle.size && (0..haystack.size - needle.size).any { haystack.subList(it, it + needle.size) == needle }
}
