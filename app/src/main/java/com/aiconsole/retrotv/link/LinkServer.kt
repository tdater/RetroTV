package com.aiconsole.retrotv.link

import android.content.Context
import android.util.Log
import com.aiconsole.retrotv.data.ReportShareRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The phone link: a small HTTP + WebSocket server on the TV's Wi-Fi address. It answers only
 * requests that start with the random token from the QR code (the phone) or carry the 6-digit
 * sync code shown on screen (another RetroTV); anything else gets 404. It runs until stopped or
 * until nothing has used it for 30 minutes. Handlers live in [LinkRoutes].
 */
class LinkServer(context: Context) {

    private val appContext = context.applicationContext
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "retrotv-link").apply { isDaemon = true } }
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    @Volatile private var lastUse = 0L

    @Volatile var token = ""
        private set
    @Volatile var syncCode = ""
        private set

    private val _url = MutableStateFlow<String?>(null)
    /** The phone's address (`http://ip:port/token/`) while running, else null. */
    val url: StateFlow<String?> = _url.asStateFlow()

    /** Origin (`http://ip:port`) other RetroTVs use with the sync code. */
    @Volatile var origin: String? = null
        private set

    val routes = LinkRoutes(appContext, this)

    suspend fun start(): String? = withContext(Dispatchers.IO) {
        _url.value?.let { return@withContext it }
        val address = localAddress() ?: return@withContext null
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't open the phone link", e)
            return@withContext null
        }
        token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        syncCode = (100_000 + random.nextInt(900_000)).toString()
        server = socket
        lastUse = System.currentTimeMillis()
        origin = "http://$address:${socket.localPort}"
        pool.execute { acceptLoop(socket) }
        pool.execute { idleWatch(socket) }
        "$origin/$token/".also { _url.value = it }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        origin = null
        _url.value = null
        routes.closeAll()
    }

    fun touch() {
        lastUse = System.currentTimeMillis()
    }

    private fun idleWatch(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                return
            }
            if (System.currentTimeMillis() - lastUse > IDLE_MS && !routes.hasSockets()) {
                Log.i(TAG, "Phone link idle for 30 minutes: stopping")
                stop()
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val head = readHead(input) ?: return client.close()
            val req = LinkProtocol.parseRequestHead(head) ?: return client.close()
            if (!LinkProtocol.authorized(req, token, syncCode)) {
                Exchange(req, req.path, input, out).text("Not found", 404)
                return client.close()
            }
            touch()
            val rel = req.path.removePrefix("/$token").ifEmpty { "/" }
            if (req.headers["upgrade"].equals("websocket", ignoreCase = true)) {
                val key = req.headers["sec-websocket-key"] ?: return client.close()
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${LinkProtocol.wsAccept(key)}\r\n\r\n").toByteArray()
                )
                out.flush()
                client.soTimeout = 0
                val ws = LinkSocket(out) { client.close() }
                routes.socket(rel, ws)
                wsLoop(input, ws)
                return
            }
            routes.handle(Exchange(req, rel, input, out))
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "Phone link request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun wsLoop(input: InputStream, ws: LinkSocket) {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                var bytes = buffer.toByteArray()
                while (true) {
                    val frame = LinkProtocol.wsDecode(bytes) ?: break
                    bytes = bytes.copyOfRange(frame.length, bytes.size)
                    when (frame.opcode) {
                        1 -> { touch(); ws.onMessage(String(frame.payload)) }
                        8 -> return
                    }
                }
                buffer.reset()
                buffer.write(bytes)
            }
        } catch (_: IOException) {
        } finally {
            ws.onClose()
        }
    }

    /** Reads the request line and headers (ISO-8859-1) up to the blank line; null on EOF or an oversized head. */
    private fun readHead(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > 16_384) return null
            when (b) {
                '\r'.code -> Unit
                '\n'.code -> {
                    if (line.isEmpty()) return lines
                    lines += line.toString()
                    line.setLength(0)
                }
                else -> line.append(b.toChar())
            }
        }
    }

    private fun localAddress(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to (it.hostAddress ?: "") } }
        ReportShareRules.pickAddress(candidates)
    } catch (e: SocketException) {
        null
    }

    private companion object {
        const val TAG = "LinkServer"
        const val IDLE_MS = 30 * 60_000L
    }
}

/** One HTTP request/response on the phone link. */
class Exchange(val request: HttpRequest, val rel: String, private val input: InputStream, private val out: OutputStream) {

    val contentLength: Long = request.headers["content-length"]?.toLongOrNull() ?: -1L

    /** The request body, limited to Content-Length. */
    fun body(): InputStream = object : FilterInputStream(input) {
        var left = contentLength.coerceAtLeast(0)
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    fun send(status: Int, type: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
        head(status, type, bytes.size.toLong(), extra)
        out.write(bytes)
    }

    fun text(s: String, status: Int = 200) = send(status, "text/plain; charset=utf-8", s.toByteArray())

    fun json(s: String, status: Int = 200) = send(status, "application/json; charset=utf-8", s.toByteArray())

    /** A response whose length isn't known up front (the connection closes after it). */
    fun stream(type: String, fileName: String?, write: (OutputStream) -> Unit) {
        head(200, type, -1, fileName?.let { mapOf("Content-Disposition" to "attachment; filename=\"$it\"") }.orEmpty())
        write(out)
    }

    fun file(file: java.io.File, type: String) {
        head(200, type, file.length(), emptyMap())
        file.inputStream().use { it.copyTo(out) }
    }

    private fun head(status: Int, type: String, length: Long, extra: Map<String, String>) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Too Large"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }
}

/** A phone's open WebSocket. */
class LinkSocket(private val out: OutputStream, private val closer: () -> Unit) {
    @Volatile var onMessage: (String) -> Unit = {}
    @Volatile var onClose: () -> Unit = {}

    fun send(text: String) {
        runCatching { synchronized(out) { out.write(LinkProtocol.wsTextFrame(text)); out.flush() } }
    }

    fun close() = runCatching { closer() }
}
