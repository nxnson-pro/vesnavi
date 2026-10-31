package com.nxnson.vesnavi.navcore

import com.nxnson.vesnavi.navcore.Fixtures.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoboSppTest {
    private val challenge = hex("FF 00 00 00 00 00 89 06 13 66 00 00 00 00 00 00")
    private val authOk = hex("00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")

    @Test
    fun `auth reply uses the FEDE key and a byte-sum checksum`() {
        // Same key as the BLE key link for this challenge (40 05 BB 56, from the capture).
        assertEquals(hex("FF 00 00 00 00 00 40 05 BB 56 00 00 00 00 00 55"), CoboSpp.authReply(challenge))
    }

    @Test
    fun `init sequence over SPP`() {
        val s = CoboSppSession()
        // The challenge may arrive in pieces.
        assertTrue(s.feed(challenge.take(5)).writes.isEmpty())
        val r1 = s.feed(challenge.drop(5))
        assertEquals(listOf(CoboSpp.authReply(challenge)), r1.writes)
        val r2 = s.feed(authOk)
        assertEquals(Cobo.frames(0x70, Cobo.initPayload), r2.writes)
        assertTrue(CoboSppSession.Event.Authenticated in r2.events)

        // Every frame is acknowledged with F0 <length> 00, then answered.
        val f70 = hex("C0 44 51 00 70 0A 00 01 01 02 01 01 03 02 00 00 1A")
        assertEquals(listOf(CoboSpp.ack(f70.size)) + Cobo.frames(0x01), s.feed(f70).writes)
        assertEquals(listOf(CoboSpp.ack(7)) + Cobo.frames(0x06), s.feed(hex("C0 44 51 00 02 00 97")).writes)
        val r4 = s.feed(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        assertEquals(
            listOf(CoboSpp.ack(13)) + Cobo.frames(0x23, hex("01 00 02 00 03 00")) + Cobo.frames(0x28, Cobo.structuredSubscribePayload),
            r4.writes,
        )
        assertTrue(CoboSppSession.Event.Ready in r4.events)
        assertEquals(CoboSppSession.Stage.READY, s.stage)
    }

    @Test
    fun `without engine data the 0x28 subscription is not sent`() {
        val s = CoboSppSession(engineData = false)
        s.feed(challenge + authOk)
        val r = s.feed(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        assertEquals(listOf(CoboSpp.ack(13)) + Cobo.frames(0x23, hex("01 00 02 00 03 00")), r.writes)
    }

    @Test
    fun `a rejected auth stops the session`() {
        val s = CoboSppSession()
        s.feed(challenge)
        val r = s.feed(List(16) { 0x01 })
        assertTrue(r.events.single() is CoboSppSession.Event.AuthRejected)
        assertEquals(CoboSppSession.Stage.FAILED, s.stage)
    }

    @Test
    fun `engine data frames are cut from the stream and decoded`() {
        val can = hex(
            "C0 80 76 34 01 01 00 00 00 00 00 16 05 11 39 C3 79 00 00 00 27 3E 4D 4A 00 00 00 00 00 00 00 01 00 00 01 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 02 00 00 00 00 01 01 01 00 00 00 00 00 00 00 00",
        )
        val s = CoboSppSession()
        s.feed(challenge + authOk)
        // Two frames plus a scooter ack, split at awkward places.
        val stream = can + hex("F0 11 00") + can
        val events = (stream.chunked(10).flatMap { s.feed(it).events })
        val data = events.filterIsInstance<CoboSppSession.Event.CanData>()
        assertEquals(2, data.size)
        assertEquals(31171, CoboTelemetry.parse(data[0].bytes)?.odometerKm)
    }

    @Test
    fun `multi-frame packets are reassembled and garbage is skipped`() {
        val r = CoboFrameReader()
        val frames = Cobo.frames(0x28, Cobo.structuredSubscribePayload)
        val out = r.feed(hex("12 34") + frames.flatten() + hex("C0 44 51 00 01 00 96"))
        assertEquals(frames + listOf(hex("C0 44 51 00 01 00 96")), out)
        assertEquals(2, r.skipped)
    }
}
