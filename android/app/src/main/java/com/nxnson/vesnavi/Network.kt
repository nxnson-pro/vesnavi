package com.nxnson.vesnavi

import com.nxnson.vesnavi.navcore.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection on the IO dispatcher; every request has timeouts and identifies the app. */
object Network {
    private const val USER_AGENT = "VesNavi/${BuildConfig.VERSION_NAME} (Android; personal navigation app)"

    /** Body of a 2xx response; on 4xx the body too (GraphHopper/OSRM put the error message there). */
    val fetcher = HttpFetcher { url -> get(url, acceptErrorBody = true) }

    suspend fun get(url: String, timeoutMs: Int = 15_000, acceptErrorBody: Boolean = false): ByteArray =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                when {
                    code in 200..299 -> c.inputStream.use { it.readBytes() }
                    acceptErrorBody && code in 400..499 -> c.errorStream?.use { it.readBytes() } ?: throw IOException("HTTP $code")
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
}
