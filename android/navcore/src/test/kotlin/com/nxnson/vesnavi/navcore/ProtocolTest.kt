package com.nxnson.vesnavi.navcore

import com.nxnson.vesnavi.navcore.Fixtures.ascii
import com.nxnson.vesnavi.navcore.Fixtures.hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every expected Cobo byte string is the one recorded from the scooter (same vectors as the iOS tests). */
class CoboTest {
    @Test
    fun `single frames match the capture`() {
        assertEquals(listOf(hex("C0 44 51 00 70 0E 00 01 01 03 02 00 00 01 01 01 04 02 00 FF 22")), Cobo.frames(0x70, Cobo.initPayload))
        assertEquals(listOf(hex("C0 44 51 00 01 00 96")), Cobo.frames(0x01))
        assertEquals(listOf(hex("C0 44 51 00 06 00 9B")), Cobo.frames(0x06))
        assertEquals(listOf(hex("C0 44 51 00 10 00 A5")), Cobo.frames(0x10))
        assertEquals(listOf(hex("C0 44 51 00 23 02 01 00 BB")), Cobo.frames(0x23, Cobo.subscribePayload))
        assertEquals(
            listOf(hex("C0 44 51 00 27 0C 01 04 09 00 00 FE 0B 55 55 55 55 55 88")),
            Cobo.frames(0x27, Cobo.readRequestPayload(listOf(0xFE, 0x0B))),
        )
        assertEquals(
            listOf(hex("C0 44 51 00 27 0C 01 04 09 00 00 FF 06 55 55 55 55 55 84")),
            Cobo.frames(0x27, Cobo.readRequestPayload(listOf(0xFF, 0x06))),
        )
    }

    @Test
    fun `multi frame 0x28 matches the capture`() {
        assertEquals(407, Cobo.structuredSubscribePayload.size)
        val frames = Cobo.frames(0x28, Cobo.structuredSubscribePayload)
        assertEquals(listOf(128, 128, 128, 34), frames.map { it.size })
        assertEquals(listOf(0xC1, 0xD0, 0xD1, 0xE2), frames.map { it[0] })
        assertEquals(hex("C1 44 51 00 28 81 97 00 01 0E"), frames[0].take(10))
        assertEquals(hex("01 03 07 01 03 06 99"), frames[3].takeLast(7))
    }

    @Test
    fun `idle dash matches the capture`() {
        assertEquals(hex("C6 01 00 00 00 00 FF FF FF 00 02 00 00 03 00 00 FF FF FF FF FF FF 00 04 4E 4F 20 41 44 44 52 45 53 53 00"), Cobo.idleDash)
    }

    @Test
    fun `dash for a navigation state`() {
        val s = Fixtures.state(Maneuver.Left, 199.6, street = "Lê Lợi", remaining = 1500.0, duration = 61.0)
        assertEquals(
            hex("C6 01 0B 00 00 00 FF FF FF 00 02 00 00 03 00 02 00 05 DC 00 00 C8 00 04 4C 45 20 4C 4F 49 00 06 04 00"),
            Cobo.dashPayload(s),
        )
        assertEquals(listOf(0x06, 0x03, 0x00), Cobo.stopDash.takeLast(3))
        assertFalse(0x06 in Cobo.idleDash, "idle dash has no tag 06")
    }

    @Test
    fun `key reply matches the capture`() {
        val tail = " 03 6D 3B 33 14 92 85 BC 4A A4 4C C3 AA 75 20 6C C3 AA 75"
        assertEquals(hex("40 05 BB 56$tail"), Cobo.keyReply(hex("89 06 13 66")))
        assertEquals(hex("2D 05 FF FD$tail"), Cobo.keyReply(hex("EC 6B B8 8B")))
        assertEquals(hex("FC 04 BB D7$tail"), Cobo.keyReply(hex("1E BA 92 F5")))
        assertEquals(hex("5A 41 BF BD$tail"), Cobo.keyReply(hex("DF 1C F8 2A")))
    }

    @Test
    fun `key fetch`() {
        val k = CoboKeyFetch()
        assertEquals(CoboKeyFetch.Event.Ignored(hex("9D 1E A3 BE 50 25")), k.handle(hex("9D 1E A3 BE 50 25")), "answer before challenge")
        assertEquals(CoboKeyFetch.Event.Ignored(hex("05 04 00")), k.handle(hex("05 04 00")), "3-byte request is not a challenge")
        assertEquals(
            CoboKeyFetch.Event.Challenge(hex("05 04 00 89 06 13 66"), Cobo.keyReply(hex("89 06 13 66"))),
            k.handle(hex("05 04 00 89 06 13 66")),
        )
        assertEquals(CoboKeyFetch.Event.Ignored(hex("01 02 03")), k.handle(hex("01 02 03")))
        assertEquals(CoboKeyFetch.Event.Answer(hex("9D 1E A3 BE 50 25")), k.handle(hex("9D 1E A3 BE 50 25")))
        assertEquals(hex("9D 1E A3 BE 50 25"), k.answer)
        // A new challenge discards the old answer.
        k.handle(hex("05 04 00 1E BA 92 F5"))
        assertNull(k.answer)
        assertEquals(CoboKeyFetch.Event.Answer(hex("8A 1F A7 4E 68 1C")), k.handle(hex("8A 1F A7 4E 68 1C")))
    }

    @Test
    fun `server session replays the recorded sequence`() {
        val answer = hex("9D 1E A3 BE 50 25")
        val s = CoboServerSession(answer)

        val r1 = s.handleWrite(Cobo.answerRequest)
        assertEquals(listOf(answer) + Cobo.frames(0x70, Cobo.initPayload), r1.indications)
        assertEquals(listOf<CoboServerSession.Event>(CoboServerSession.Event.AnswerSent), r1.events)

        assertEquals(Cobo.frames(0x01), s.handleWrite(hex("C0 44 51 00 70 0A 00 01 01 02 01 01 03 02 00 00 1A")).indications)
        assertEquals(Cobo.frames(0x06) + Cobo.frames(0x10), s.handleWrite(hex("C0 44 51 00 02 00 97")).indications)

        val r4 = s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        assertEquals(Cobo.frames(0x23, Cobo.subscribePayload) + Cobo.frames(0x28, Cobo.structuredSubscribePayload), r4.indications)
        assertTrue(CoboServerSession.Event.Ready in r4.events)
        assertEquals(CoboServerSession.Stage.READY, s.stage)

        assertEquals(Cobo.frames(0x27, Cobo.readRequestPayload(listOf(0xFE, 0x0B))), s.readRequestFrames())
        val r5 = s.handleWrite(hex("C0 44 51 00 30 1C 01 00 19 01 C0 12 FE 0B 04 00 01 00 00 00"))
        assertEquals(Cobo.frames(0x27, Cobo.readRequestPayload(listOf(0xFF, 0x06))), r5.indications)
        assertEquals(r5.indications, s.readRequestFrames(), "retry resends the pending read")
        val r6 = s.handleWrite(hex("C0 44 51 00 30 0C 01 00 09 01 43 FF 06 00 54 24 AA AA F0"))
        assertTrue(r6.indications.isEmpty())
        assertTrue(s.readRequestFrames().isEmpty())
        assertTrue(s.pendingReads.isEmpty())

        val can = hex("C0 80 78 34 01 01 00 00 00 00 00 16 05 10 19 C3 79 00 00 00")
        assertEquals(listOf<CoboServerSession.Event>(CoboServerSession.Event.CanData(can)), s.handleWrite(can).events)
        assertTrue(s.handleWrite(can).indications.isEmpty(), "no F0 acks on BLE")
    }

    @Test
    fun `server session without an answer`() {
        val s = CoboServerSession(null)
        val r = s.handleWrite(Cobo.answerRequest)
        assertEquals(listOf<CoboServerSession.Event>(CoboServerSession.Event.MissingAnswer), r.events)
        assertTrue(r.indications.isEmpty())
        assertEquals(CoboServerSession.Stage.WAITING_FOR_REQUEST, s.stage)
    }

    @Test
    fun `dash payload without header`() {
        val p = PMP3.dashPacket(PMP3.Icon.LEFT, 200, 500, 2, null, includeHeader = false)
        assertEquals(0xC6, p.first())
    }

    @Test
    fun `English TFT text`() {
        val t = ScooterTelemetry(rpm = 1700, coolantC = 85, batteryVolts = 13.8)
        assertEquals("COOL 85C  BAT 13.8V", Cobo.infoText(t, Language.EN))
        assertEquals("NUOC 85C  AQ 13.8V", Cobo.infoText(t))
        val s = Fixtures.state(Maneuver.Left, 50.0, remaining = 500.0, duration = 60.0)
        assertEquals("Turn left", Cobo.dashText(s, Language.EN))
        val payload = Cobo.dashPayload(s.copy(isOffRoute = true), Language.EN)
        assertTrue("REROUTING" in String(payload.toByteArray(), Charsets.US_ASCII))
    }

    @Test
    fun `unnamed road falls back to the instruction`() {
        val s = Fixtures.state(Maneuver.Right, 100.0, remaining = 500.0, duration = 60.0)
        assertEquals("Rẽ phải", Cobo.dashText(s))
        assertTrue("RE PHAI" in String(Cobo.dashPayload(s).toByteArray(), Charsets.US_ASCII))
    }

    @Test
    fun `popup layout`() {
        assertEquals(
            listOf(0xC2, 0x01, 13) + ascii("NHIET DO CAO") + listOf(0x3B) + ascii("NUOC 108C") + listOf(0x00),
            Cobo.popupPayload("Nhiệt độ cao", "Nuoc 108C"),
        )
        assertEquals(1, Cobo.popupPayload("a;b", "c").count { it == 0x3B }, "a ';' inside a line would split it")
    }
}

class PMP3Test {
    @Test
    fun `auth response vectors`() {
        assertEquals("FCCE14AA45680772", Hex.encode(PMP3.authResponse(hex("0102030405060708"), hex("11223344")), ""))
        assertEquals("16C3BB4F108CA913", Hex.encode(PMP3.authResponse(hex("DEADBEEF01234567"), hex("A55AFF00")), ""))
        assertEquals("3F15C5293C6EF35A", Hex.encode(PMP3.authResponse(List(8) { 0 }, listOf(1, 2, 3, 4)), ""))
    }

    @Test
    fun `device id`() {
        assertEquals("000019A2B3C4D50E", Hex.encode(PMP3.makeDeviceID(0x19A_2B3C_4D5EL), ""))
        assertEquals(8, PMP3.makeDeviceID(1).size)
    }

    @Test
    fun `key constraints`() {
        val r = Random(42)
        repeat(200) {
            val k = PMP3.makeKey(r)
            assertEquals(8, k.size)
            assertNotEquals(0, k[0])
            assertNotEquals(0x0F, k[1])
        }
    }

    @Test
    fun `dash packet layout`() {
        val p = PMP3.dashPacket(PMP3.Icon.LEFT, 200, 0x012345, 7, "Lê Lợi")
        assertEquals("55 01 05 C6 01 0B 00 00 00 FF FF FF 00 03 00 07 01 23 45 00 00 C8 00 04 4C 65 20 4C 6F 69 00", Hex.encode(p))
    }

    @Test
    fun `text mode and unknown distance`() {
        val p = PMP3.dashPacket(0, 0, -5, 0, null, PMP3.DashOptions(textMode = PMP3.TextMode.MODE_FF))
        assertEquals("55 01 05 C6 04 FF 00 00", Hex.encode(p))
        assertEquals(listOf(0xFF, 0xFF, 0xFF), PMP3.be24(0))
    }

    @Test
    fun `dash from a navigation state`() {
        val s = Fixtures.state(Maneuver.UTurnLeft, 49.6, street = "Đường 3/2", remaining = 1500.0, duration = 61.0)
        val p = PMP3.dashPacket(s)
        assertEquals(listOf(0x55, 0x01, 0x05, 0xC6, 0x01, PMP3.Icon.U_TURN_COUNTER_CLOCKWISE), p.subList(0, 6))
        assertEquals(listOf(0x03, 0x00, 0x02, 0x00, 0x05, 0xDC, 0x00, 0x00, 0x32, 0x00), p.subList(13, 23))
        assertEquals(listOf(0x04) + ascii("Duong 3/2") + listOf(0x00), p.drop(23))
    }

    @Test
    fun `text encoding strips Vietnamese marks`() {
        assertEquals(ascii("Nguyen Thi Minh Khai"), PMP3.encodeText("Nguyễn Thị Minh Khai", 40))
        assertEquals(ascii("DIEN B"), PMP3.encodeText("Điện Biên Phủ", 6, uppercase = true))
    }

    @Test
    fun hex() {
        assertEquals(listOf(0x0A, 0x1B, 0xFF), Hex.decode("0a 1B-ff"))
        assertNull(Hex.decode("0a1"))
        assertNull(Hex.decode("zz"))
    }

    private fun session(pairing: PMP3Pairing? = null) =
        PMP3Session(pairing, "Điện thoại của tôi", 0x19A_2B3C_4D5EL) { listOf(0xAA, 0xBB, 1, 2, 3, 4, 5, 6) }

    @Test
    fun `first pairing flow`() {
        val s = session()
        val start = s.start()
        assertEquals(listOf(hex("000019A2B3C4D50E")), start.writes)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.PairingChanged(PMP3Pairing(hex("000019A2B3C4D50E")))), start.events)
        assertEquals(listOf(listOf(0x00, 0x0E) + ascii("Dien thoai cua toi")), s.handle(listOf(0, 0, 0, 0x0E)).writes)

        val pair = s.handle(listOf(0, 0, 0, 0x0F))
        assertEquals(listOf(listOf(0x00, 0x01, 0xAA, 0xBB, 1, 2, 3, 4, 5, 6)), pair.writes)
        assertTrue(PMP3Session.Event.NewPairingRequested in pair.events)
        assertEquals(listOf(0xAA, 0xBB, 1, 2, 3, 4, 5, 6), s.pairing.key)

        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.Authenticated), s.handle(listOf(0, 0, 0, 0x4F)).events)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.Ready(true)), s.handle(listOf(0x45, 0, 0, 0, 1)).events)
        assertTrue(s.isReady)
    }

    @Test
    fun `challenge uses the stored key`() {
        val s = session(PMP3Pairing(listOf(1, 2, 3, 4, 5, 6, 7, 8), hex("0102030405060708")))
        assertTrue(s.start().events.isEmpty(), "known pairing → no pairingChanged event")
        assertEquals(listOf(listOf(0x00, 0x01) + hex("FCCE14AA45680772")), s.handle(listOf(0x00, 0x00, 0x11, 0x22, 0x33, 0x44)).writes)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.AuthResult(true)), s.handle(listOf(0x00, 0x01, 0x01)).events)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.AuthResult(false)), s.handle(listOf(0x00, 0x01, 0x00)).events)
    }

    @Test
    fun `firmware exchange`() {
        val s = session()
        assertEquals(16, s.handle(listOf(0x00, 0x03)).writes.first().size)
        assertEquals(listOf(listOf(0x44, 0x34) + List(14) { 0 }), s.handle(listOf(0x40, 0x41)).writes)
        assertEquals(listOf(listOf(0x44, 0x38)), s.handle(listOf(0x44, 0x34, 0x07)).writes)
        assertEquals(listOf(listOf(0x44, 0x39)), s.handle(listOf(0x44, 0x38, 0x02, 0x05)).writes)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.Firmware(7, "2.5", "1.0")), s.handle(listOf(0x44, 0x39, 0x01, 0x00)).events)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.Button(2)), s.handle(listOf(0x4B, 0x53, 0x02)).events)
        assertEquals(listOf<PMP3Session.Event>(PMP3Session.Event.Unhandled(listOf(0x99))), s.handle(listOf(0x99)).events)
    }

    @Test
    fun `byte conversion round trips`() {
        val b = listOf(0x00, 0x7F, 0x80, 0xFF)
        assertContentEquals(byteArrayOf(0, 127, -128, -1), b.toByteArray())
        assertEquals(b, b.toByteArray().toBytes())
    }

    @Test
    fun `pairing persists`() {
        val p = PMP3Pairing(hex("0102030405060708"), hex("AABB010203040506"))
        assertEquals(p, Persistence.pairing(Persistence.pairing(p)))
        assertNotNull(Persistence.pairing(Persistence.pairing(PMP3Pairing(hex("0102030405060708")))))
    }
}
