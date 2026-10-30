package com.nxnson.vesnavi.navcore

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugReportTest {
    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        k to URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
    }

    @Test
    fun `addresses and secrets are redacted`() {
        val log = """
            Device connected to the server: 4C:11:AE:00:12:FF
            TX 00 01 AA BB 01 02 03 04 05 06
            Pairing: key=AABB010203040506
            Found: Lan's phone RSSI -60
            Found: BT-ROUTER RSSI -50
        """.trimIndent()
        val key = listOf(0xAA, 0xBB, 1, 2, 3, 4, 5, 6)
        val out = BugReport.redact(log, BugReport.hexVariants(key) + listOf("Lan's phone", "ab"))
        assertFalse("4C:11:AE" in out)
        assertTrue("XX:XX:XX:XX:XX:XX" in out)
        assertFalse("AA BB 01 02" in out)
        assertFalse("AABB0102" in out)
        assertFalse("Lan's phone" in out)
        assertTrue("BT-ROUTER" in out, "the scooter's own name stays")
        assertTrue("TX 00 01 <redacted>" in out)
        assertTrue(BugReport.hexVariants(null).isEmpty())
    }

    @Test
    fun `a short report keeps the whole log`() {
        val url = BugReport.issueURL("[Android] Không kết nối", "C++ & 50%", "1.0.8 (40)", "Pixel", "line 1\nline 2")
        assertTrue(url.startsWith("https://github.com/nxnson-pro/vesnavi/issues/new?template=bug_report.yml&title="))
        assertTrue("description=C%2B%2B%20%26%2050%25" in url, "+, & and % must not be mangled")
        assertTrue("log=line%201%0Aline%202" in url)
        assertEquals("[Android] Không kết nối", query(url)["title"])
    }

    @Test
    fun `a long log is trimmed from the oldest lines`() {
        val log = (1..3000).joinToString("\n") { "line $it C0 80 76 34 01 01 00 00" }
        val url = BugReport.issueURL("t", "d", "v", "x", log)
        assertTrue(url.length <= BugReport.MAX_URL_LENGTH)
        val value = query(url).getValue("log")
        assertTrue(value.startsWith("[… "), "says how much was cut")
        assertTrue(value.endsWith("line 3000 C0 80 76 34 01 01 00 00"), "keeps the newest lines")
        assertFalse("line 1 C0" in value)
    }
}
