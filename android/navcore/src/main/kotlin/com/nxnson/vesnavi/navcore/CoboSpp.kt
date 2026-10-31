package com.nxnson.vesnavi.navcore

/**
 * The BT-ROUTER (PMP2) link over **classic Bluetooth SPP** — the way Android phones talk to the
 * scooter. iPhones can't open SPP, which is why the iOS app uses the two BLE links in [Cobo].
 * Same DQ command layer and frames as BLE, with three differences (docs/pmp2-cobo-protocol.md):
 * a 16-byte auth exchange instead of the BLE key link, a byte stream that has to be cut into
 * frames, and an `F0 n 00` acknowledgement for every frame received.
 */
object CoboSpp {
    /** Serial Port Profile. */
    const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
    const val AUTH_LENGTH = 16

    /** 0x23 payload on SPP. */
    val subscribePayload: Bytes = listOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00)

    /**
     * Reply to the scooter's `FF 00 00 00 00 00 c0 c1 c2 c3 …`: `FF 00×5 k0 k1 k2 k3 00×5 cs`,
     * the key being the same "FEDE" formula as the BLE key link, `cs` the byte sum mod 256.
     */
    fun authReply(challenge: Bytes): Bytes {
        val key = Cobo.keyReply(challenge.subList(6, 10)).take(4)
        val body = listOf(0xFF) + List(5) { 0 } + key + List(5) { 0 }
        return body + (body.sum() and 0xFF)
    }

    /** `F0 n 00`: received a frame of n bytes. */
    fun ack(n: Int): Bytes = listOf(0xF0, n and 0xFF, 0x00)
}

/**
 * Cuts the SPP byte stream into Cobo frames: `F0 n 00` acks, `C0 80 …` engine data (fixed length
 * from the 0x28 subscription), `C0 44 51 …` DQ packets and multi-frame packets (`C1`, `Dx`, `Ex`).
 * Unknown bytes are skipped one at a time until a frame start lines up again.
 */
class CoboFrameReader(private val canDataLength: Int = CoboTelemetry.frameDataLength) {
    private val buffer = ArrayList<Int>()
    /** Bytes still expected after the frames of a multi-frame packet seen so far. */
    private var multiRemaining = 0
    var skipped = 0
        private set

    fun feed(chunk: Bytes): List<Bytes> {
        buffer.addAll(chunk)
        val frames = mutableListOf<Bytes>()
        while (buffer.isNotEmpty()) {
            val size = frameSize() ?: break
            if (size < 0) {
                buffer.removeAt(0)
                skipped++
                continue
            }
            frames += ArrayList(buffer.subList(0, size))
            buffer.subList(0, size).clear()
        }
        return frames
    }

    /** Size of the frame at the head of the buffer, null when more bytes are needed, -1 when it isn't a frame. */
    private fun frameSize(): Int? {
        val head = buffer[0]
        fun need(n: Int): Boolean = buffer.size >= n
        return when {
            head == 0xF0 -> if (!need(3)) null else if (buffer[2] == 0x00) 3 else -1
            head == 0xC0 -> {
                if (!need(2)) return null
                when (buffer[1]) {
                    0x80 -> if (need(2 + canDataLength)) 2 + canDataLength else null
                    0x44 -> {
                        if (!need(6)) return null
                        if (buffer[2] != 0x51) return -1
                        val total = packetLength() ?: return null
                        if (total > Cobo.MAX_FRAME_DATA) -1 else if (need(1 + total)) 1 + total else null
                    }
                    else -> -1
                }
            }
            head == 0xC1 -> {
                if (!need(7)) return null
                if (buffer[1] != 0x44 || buffer[2] != 0x51) return -1
                val total = packetLength() ?: return null
                if (!need(1 + Cobo.MAX_FRAME_DATA)) return null
                multiRemaining = total - Cobo.MAX_FRAME_DATA
                1 + Cobo.MAX_FRAME_DATA
            }
            head and 0xF0 == 0xD0 && multiRemaining > Cobo.MAX_FRAME_DATA -> {
                if (!need(1 + Cobo.MAX_FRAME_DATA)) return null
                multiRemaining -= Cobo.MAX_FRAME_DATA
                1 + Cobo.MAX_FRAME_DATA
            }
            head and 0xF0 == 0xE0 && multiRemaining in 1..Cobo.MAX_FRAME_DATA -> {
                if (!need(1 + multiRemaining)) return null
                val size = 1 + multiRemaining
                multiRemaining = 0
                size
            }
            else -> -1
        }
    }

    /** DQ packet length (`44 51 00 cmd len… payload checksum`) from the header after the frame byte. */
    private fun packetLength(): Int? {
        val len = buffer[5]
        if (len and 0x80 == 0) return 5 + len + 1
        if (buffer.size < 7) return null
        return 6 + (((len and 0x7F) shl 8) or buffer[6]) + 1
    }
}

/**
 * SPP session, transport-free: feed it whatever the socket read, write back what it returns.
 * Mirrors the BLE data link ([CoboServerSession]) with the SPP init sequence.
 */
class CoboSppSession(private val engineData: Boolean = true) {
    enum class Stage { WAITING_FOR_CHALLENGE, WAITING_FOR_AUTH_RESULT, INITIALIZING, READY, FAILED }

    sealed class Event {
        data class Challenge(val bytes: Bytes, val reply: Bytes) : Event()
        object Authenticated : Event()
        data class AuthRejected(val bytes: Bytes) : Event()
        data class Command(val cmd: Int, val payload: Bytes) : Event()
        data class CanData(val bytes: Bytes) : Event()
        object Ready : Event()
        data class Unhandled(val bytes: Bytes) : Event()
    }

    data class Output(val writes: List<Bytes> = emptyList(), val events: List<Event> = emptyList())

    var stage = Stage.WAITING_FOR_CHALLENGE
        private set
    private val authBuffer = ArrayList<Int>()
    private val reader = CoboFrameReader()
    private val multi = ArrayList<Int>()

    /** Bytes the reader had to skip to find the next frame (shown in the log). */
    val skippedBytes get() = reader.skipped

    fun feed(chunk: Bytes): Output {
        val writes = mutableListOf<Bytes>()
        val events = mutableListOf<Event>()
        var rest = chunk
        while (stage == Stage.WAITING_FOR_CHALLENGE || stage == Stage.WAITING_FOR_AUTH_RESULT) {
            val take = minOf(CoboSpp.AUTH_LENGTH - authBuffer.size, rest.size)
            authBuffer.addAll(rest.take(take))
            rest = rest.drop(take)
            if (authBuffer.size < CoboSpp.AUTH_LENGTH) return Output(writes, events)
            val block = authBuffer.toList()
            authBuffer.clear()
            if (stage == Stage.WAITING_FOR_CHALLENGE) {
                if (block[0] != 0xFF) {
                    events += Event.Unhandled(block)
                    continue
                }
                val reply = CoboSpp.authReply(block)
                writes += reply
                events += Event.Challenge(block, reply)
                stage = Stage.WAITING_FOR_AUTH_RESULT
            } else if (block[0] == 0x00) {
                events += Event.Authenticated
                writes += Cobo.frames(0x70, Cobo.initPayload)
                stage = Stage.INITIALIZING
            } else {
                events += Event.AuthRejected(block)
                stage = Stage.FAILED
                return Output(writes, events)
            }
        }
        if (stage == Stage.FAILED) return Output(writes, events)
        for (frame in reader.feed(rest)) handleFrame(frame, writes, events)
        return Output(writes, events)
    }

    private fun handleFrame(frame: Bytes, writes: MutableList<Bytes>, events: MutableList<Event>) {
        if (frame[0] == 0xF0) return // the scooter acknowledging our frame
        writes += CoboSpp.ack(frame.size)
        when (frame[0]) {
            0xC0 -> if (frame[1] == 0x80) events += Event.CanData(frame) else handlePacket(frame.drop(1), writes, events)
            0xC1 -> {
                multi.clear()
                multi.addAll(frame.drop(1))
            }
            else -> {
                multi.addAll(frame.drop(1))
                if (frame[0] and 0xF0 == 0xE0) {
                    handlePacket(multi.toList(), writes, events)
                    multi.clear()
                }
            }
        }
    }

    /** `44 51 00 cmd len payload checksum`. */
    private fun handlePacket(packet: Bytes, writes: MutableList<Bytes>, events: MutableList<Event>) {
        if (packet.size < 6) {
            events += Event.Unhandled(packet)
            return
        }
        val cmd = packet[3]
        val long = packet[4] and 0x80 != 0
        val start = if (long) 6 else 5
        val len = if (long) ((packet[4] and 0x7F) shl 8) or packet[5] else packet[4]
        events += Event.Command(cmd, packet.drop(start).take(len))
        when (cmd) {
            0x70 -> writes += Cobo.frames(0x01)
            0x02 -> writes += Cobo.frames(0x06)
            0x11 -> {
                writes += Cobo.frames(0x23, CoboSpp.subscribePayload)
                if (engineData) writes += Cobo.frames(0x28, Cobo.structuredSubscribePayload)
                stage = Stage.READY
                events += Event.Ready
            }
        }
    }
}
