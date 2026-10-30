package com.nxnson.vesnavi.navcore

/**
 * Bug reports to GitHub without any token in the app: the app opens a pre-filled "new issue" form
 * (`.github/ISSUE_TEMPLATE/bug_report.yml`) in the browser and the rider submits it from their own
 * GitHub account. Issues are public, so logs are redacted first.
 */
object BugReport {
    const val REPOSITORY = "nxnson-pro/vesnavi"
    const val TEMPLATE = "bug_report.yml"
    /** GitHub refuses "new issue" URLs much past 8 KB; stay below with some margin. */
    const val MAX_URL_LENGTH = 7_500
    private const val MAX_DESCRIPTION_LENGTH = 1_500

    private val macAddress = Regex("""\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

    /**
     * Removes Bluetooth addresses and the given secrets (pairing key, auth answer, phone and nearby
     * device names). Longer secrets go first so a short one can't leave half of a long one behind.
     */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        secrets.toSet().filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "<redacted>") }
        return macAddress.replace(out, "XX:XX:XX:XX:XX:XX")
    }

    /** The ways a byte secret is spelled in the log: "AA BB CC" and "AABBCC". */
    fun hexVariants(bytes: Bytes?): List<String> =
        if (bytes.isNullOrEmpty()) emptyList() else listOf(Hex.encode(bytes), Hex.encode(bytes, ""))

    /** Pre-filled issue form. `log` is trimmed from its oldest lines until the URL fits. */
    fun issueURL(title: String, description: String, version: String, device: String, log: String?): String {
        val base = listOf(
            "template" to TEMPLATE,
            "title" to title,
            "version" to version,
            "device" to device,
            "description" to description.take(MAX_DESCRIPTION_LENGTH),
        )
        fun build(log: String?): String {
            val items = base + listOfNotNull(log?.let { "log" to it })
            return "https://github.com/$REPOSITORY/issues/new?" + items.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
        }
        if (log == null) return build(null)
        var lines = log.split("\n")
        var trimmed = 0
        while (true) {
            val marker = if (trimmed > 0) listOf("[… $trimmed older lines trimmed]") else emptyList()
            val url = build((marker + lines).joinToString("\n"))
            if (url.length <= MAX_URL_LENGTH || lines.isEmpty()) return url
            val drop = minOf(lines.size, maxOf(1, lines.size / 10))
            lines = lines.drop(drop)
            trimmed += drop
        }
    }

    /** Everything but RFC 3986 unreserved characters, so "+" and spaces survive GitHub's query parsing. */
    internal fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") append(c) else append("%%%02X".format(b.toInt() and 0xFF))
        }
    }
}
