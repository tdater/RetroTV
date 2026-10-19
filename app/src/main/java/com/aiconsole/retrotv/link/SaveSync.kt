package com.aiconsole.retrotv.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.aiconsole.retrotv.RetroApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Another RetroTV found on the home network. */
data class SyncPeer(val name: String, val host: String, val port: Int)

/**
 * TV-to-TV save sync: advertises this RetroTV on the home network (NSD `_retrotv._tcp`) while the
 * Sync screen is open, lists the others, and swaps saves with one using the 6-digit code that TV
 * shows ([SyncRules.plan]: newer copy wins, nothing deleted).
 */
class SaveSync(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _peers = MutableStateFlow(emptyList<SyncPeer>())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var myName = ""
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val tvName: String
        get() = android.provider.Settings.Global.getString(appContext.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun start(port: Int) {
        stop()
        myName = "RetroTV · $tvName"
        val info = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = TYPE
            setPort(port)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { myName = s.serviceName }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD register failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) = Unit
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "NSD discovery failed $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != myName) resolve(s) }
            override fun onServiceLost(s: NsdServiceInfo) { _peers.value = _peers.value.filter { it.name != s.serviceName } }
        }.also { runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        _peers.value = emptyList()
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    // NsdManager resolves one service at a time.
    private fun resolve(s: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(s)
            if (resolving) return
            resolving = true
        }
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val s = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() ?: run { resolving = false; null } } ?: return
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = next()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null && info.serviceName != myName) {
                        _peers.value = (_peers.value.filter { it.name != info.serviceName } + SyncPeer(info.serviceName, host, info.port)).sortedBy { it.name }
                    }
                    next()
                }
            })
        }.onFailure { next() }
    }

    data class Result(val pulled: Int, val pushed: Int)

    /** Swaps saves with [peer], authorised by the [code] it shows. Call from a coroutine. */
    suspend fun syncWith(peer: SyncPeer, code: String): Result = withContext(Dispatchers.IO) {
        val base = "http://${peer.host}:${peer.port}"
        val root = RetroApp.from(appContext).saves.root()
        val remote = SyncRules.manifest(request("$base/saves/manifest", code).inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        val (pull, push) = SyncRules.plan(localManifest(root), remote)
        val byPath = remote.associateBy { it.path }
        pull.forEach { path ->
            val conn = request("$base/saves/file?p=${enc(path)}", code)
            writeFile(root, path, byPath[path]?.modified ?: System.currentTimeMillis()) { out -> conn.inputStream.use { it.copyTo(out) } }
        }
        push.forEach { path ->
            val file = File(root, path)
            val conn = request("$base/saves/file?p=${enc(path)}&t=${file.lastModified()}", code, file)
            conn.inputStream.use { it.readBytes() }
        }
        Result(pull.size, push.size)
    }

    private fun request(url: String, code: String, upload: File? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 30_000
            setRequestProperty("X-RetroTV-Code", code)
            if (upload != null) {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(upload.length())
                outputStream.use { out -> upload.inputStream().use { it.copyTo(out) } }
            }
        }
        if (conn.responseCode == 404) throw IllegalStateException("That TV didn't accept the code")
        if (conn.responseCode != 200) throw IllegalStateException("That TV answered ${conn.responseCode}")
        return conn
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "SaveSync"
        private const val TYPE = "_retrotv._tcp."

        /** Every syncable save under [root] ([SyncRules.isValid]). */
        fun localManifest(root: File): List<SaveFileInfo> = root.walkTopDown().filter { it.isFile }
            .map { SaveFileInfo(it.relativeTo(root).invariantSeparatorsPath, it.lastModified(), it.length()) }
            .filter { SyncRules.isValid(it.path) }.toList()

        /** Writes a synced file as `.part`, then renames it and keeps the sender's time. */
        fun writeFile(root: File, path: String, modified: Long, write: (FileOutputStream) -> Unit) {
            require(SyncRules.isValid(path)) { "Not a save path" }
            val target = File(root, path)
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            FileOutputStream(part).use(write)
            if (!part.renameTo(target)) {
                part.delete()
                throw IllegalStateException("Couldn't save $path")
            }
            target.setLastModified(modified)
        }
    }
}
