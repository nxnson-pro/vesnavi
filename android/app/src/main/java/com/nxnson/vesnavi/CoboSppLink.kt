package com.nxnson.vesnavi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import com.nxnson.vesnavi.navcore.Bytes
import com.nxnson.vesnavi.navcore.CoboSpp
import com.nxnson.vesnavi.navcore.CoboSppSession
import com.nxnson.vesnavi.navcore.Hex
import com.nxnson.vesnavi.navcore.toByteArray
import com.nxnson.vesnavi.navcore.toBytes
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Socket side of the PMP2 (BT-ROUTER) link over classic Bluetooth SPP: one blocking reader thread,
 * one writer thread, everything else on the main thread through [CoboSppSession]. Needs the
 * scooter paired in Android's Bluetooth settings first ("BT ROUTER", PIN 0000).
 */
@SuppressLint("MissingPermission") // VespaBLE checks the Bluetooth permissions before creating it
class CoboSppLink(engineData: Boolean, private val listener: Listener) {
    interface Listener {
        fun sppLog(text: String)
        fun sppConnected()
        fun sppReady()
        fun sppCanData(frame: Bytes)
        /** The link is gone (not called after [close]). */
        fun sppClosed(reason: String, wasReady: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val session = CoboSppSession(engineData)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    private var reportedSkips = 0

    var isReady = false
        private set
    val stage get() = session.stage

    fun connect(adapter: BluetoothAdapter, device: BluetoothDevice) {
        thread(name = "vesnavi-spp") {
            try {
                // An ongoing inquiry slows RFCOMM connects down a lot.
                runCatching { adapter.cancelDiscovery() }
                val s = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(CoboSpp.SPP_UUID))
                socket = s
                s.connect()
                if (closed) {
                    runCatching { s.close() }
                    return@thread
                }
                main.post { if (!closed) listener.sppConnected() }
                val input = s.inputStream
                val buf = ByteArray(1024)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n).toBytes()
                    main.post { received(chunk) }
                }
                main.post { finish(L("xe đóng kết nối", "the scooter closed the link")) }
            } catch (e: IOException) {
                main.post { finish(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun received(chunk: Bytes) {
        if (closed) return
        if (!isReady) listener.sppLog("RX ${Hex.encode(chunk)}")
        val out = session.feed(chunk)
        for (e in out.events) {
            when (e) {
                is CoboSppSession.Event.Challenge -> listener.sppLog(L("🔑 SPP: xe gửi thử thách — trả lời", "🔑 SPP: challenge — answering"))
                CoboSppSession.Event.Authenticated -> listener.sppLog(L("✅ SPP: xe chấp nhận xác thực, bắt đầu khởi tạo", "✅ SPP: authenticated, starting init"))
                is CoboSppSession.Event.AuthRejected -> {
                    listener.sppLog(L("⚠️ SPP: xe từ chối xác thực ${Hex.encode(e.bytes)}", "⚠️ SPP: authentication rejected ${Hex.encode(e.bytes)}"))
                    finish(L("xác thực thất bại", "authentication failed"))
                    return
                }
                is CoboSppSession.Event.Command -> listener.sppLog(L("Xe → lệnh DQ", "Scooter → DQ command") + " 0x%02X [%s]".format(e.cmd, Hex.encode(e.payload)))
                is CoboSppSession.Event.CanData -> listener.sppCanData(e.bytes)
                CoboSppSession.Event.Ready -> {
                    isReady = true
                    listener.sppReady()
                }
                is CoboSppSession.Event.Unhandled -> listener.sppLog(L("(chưa xử lý) ${Hex.encode(e.bytes)}", "(unhandled) ${Hex.encode(e.bytes)}"))
            }
        }
        if (session.skippedBytes > reportedSkips) {
            listener.sppLog(L("⚠️ SPP: bỏ qua ${session.skippedBytes - reportedSkips} byte không nhận ra", "⚠️ SPP: skipped ${session.skippedBytes - reportedSkips} unrecognised bytes"))
            reportedSkips = session.skippedBytes
        }
        for (w in out.writes) {
            // Acks and the handshake are logged; the dash stream logs its own packets.
            if (!isReady || w.firstOrNull() != 0xF0) listener.sppLog("TX ${Hex.encode(w)}")
            write(w)
        }
    }

    fun write(bytes: Bytes) {
        val s = socket
        if (closed || s == null) return
        runCatching {
            writer.execute {
                try {
                    s.outputStream.write(bytes.toByteArray())
                    s.outputStream.flush()
                } catch (e: IOException) {
                    main.post { finish(e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }

    private fun finish(reason: String) {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
        listener.sppClosed(reason, isReady)
    }

    /** Closes without calling back (the rider disconnected, or a new link replaces this one). */
    fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
    }
}
