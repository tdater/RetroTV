package com.aiconsole.retrotv.downloads

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.aiconsole.retrotv.data.UrlInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val bytes: Long,
    val total: Long,
    val mimeType: String?,
    val reason: Int
) {
    val isApk: Boolean get() = UrlInput.isApk(title, mimeType)
    val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
    val running: Boolean get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
    val statusLabel: String
        get() = when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> "Done"
            DownloadManager.STATUS_FAILED -> "Failed ($reason)"
            DownloadManager.STATUS_PAUSED -> "Paused"
            DownloadManager.STATUS_PENDING -> "Waiting"
            else -> if (total > 0) "${bytes * 100 / total}%" else "Downloading"
        }
}

/** A file saved straight from the page (blob:/data: URLs), outside DownloadManager. */
data class SavedFile(val name: String, val uri: Uri, val mimeType: String)

/**
 * Downloads through the system DownloadManager into Downloads/RetroTV, so ROMs and APKs are
 * visible to emulator apps and file managers. APKs can be installed straight from here.
 */
class Downloads(private val context: Context) {

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    private val _completed = MutableSharedFlow<DownloadItem>(extraBufferCapacity = 8)
    /** Each finished download (success or failure) as it completes. */
    val completed: SharedFlow<DownloadItem> = _completed.asSharedFlow()

    private val _saved = MutableSharedFlow<SavedFile>(extraBufferCapacity = 8)
    val saved: SharedFlow<SavedFile> = _saved.asSharedFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id in ids()) item(id)?.let { _completed.tryEmit(it) }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun ids(): Set<Long> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun setIds(ids: Set<Long>) = prefs.edit { putStringSet("ids", ids.map { it.toString() }.toSet()) }

    /** Starts a download; returns the file name it saves as. Throws on a bad URL. */
    fun enqueue(url: String, userAgent: String?, contentDisposition: String? = null, mimeType: String? = null, referer: String? = null): String {
        val name = UrlInput.guessFileName(url, contentDisposition, mimeType)
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle(name)
            setDescription("RetroTV")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            mime?.let { setMimeType(it) }
            userAgent?.let { addRequestHeader("User-Agent", it) }
            referer?.let { addRequestHeader("Referer", it) }
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RetroTV/$name")
            } else {
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            }
        }
        val id = dm.enqueue(request)
        setIds(ids() + id)
        return name
    }

    fun list(): List<DownloadItem> {
        val ids = ids()
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<DownloadItem>()
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val bytesCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) {
                out += DownloadItem(
                    c.getLong(idCol), c.getString(titleCol).orEmpty(), c.getInt(statusCol), c.getLong(bytesCol),
                    c.getLong(totalCol), c.getString(mimeCol), c.getInt(reasonCol)
                )
            }
        }
        // Forget ids the system no longer knows (cleared by the user elsewhere).
        val live = out.map { it.id }.toSet()
        if (live.size != ids.size) setIds(live)
        return out.sortedByDescending { it.id }
    }

    fun item(id: Long): DownloadItem? = list().firstOrNull { it.id == id }

    fun remove(id: Long) {
        dm.remove(id)
        setIds(ids() - id)
    }

    fun clearFinished() {
        list().filter { !it.running }.forEach { remove(it.id) }
    }

    fun uriFor(item: DownloadItem): Uri? = runCatching { dm.getUriForDownloadedFile(item.id) }.getOrNull()

    /** Opens a finished download: installs an APK, otherwise offers the apps that can open it. */
    fun open(activity: Activity, item: DownloadItem) {
        val uri = dm.getUriForDownloadedFile(item.id) ?: return toast(activity, "File is missing")
        if (item.isApk) installApk(activity, uri) else openWith(activity, uri, item.mimeType, item.title)
    }

    fun openSaved(activity: Activity, file: SavedFile) {
        if (UrlInput.isApk(file.name, file.mimeType)) installApk(activity, file.uri) else openWith(activity, file.uri, file.mimeType, file.name)
    }

    private fun openWith(activity: Activity, uri: Uri, mimeType: String?, title: String) {
        val type = mimeType?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(title.substringAfterLast('.', "").lowercase())
            ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(view, "Open $title with"))
        } catch (_: ActivityNotFoundException) {
            toast(activity, "No installed app can open $title. Install an emulator from Emulators.")
        }
    }

    /** Sends an APK to the system installer, first asking for "install unknown apps" if needed. */
    fun installApk(activity: Activity, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "Allow RetroTV to install apps, then press Install again")
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
            try {
                activity.startActivity(settings)
            } catch (_: ActivityNotFoundException) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    toast(activity, "Enable \"Unknown sources\" for RetroTV in the TV's security settings")
                }
            }
            return
        }
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(install)
        } catch (_: ActivityNotFoundException) {
            toast(activity, "This TV has no package installer")
        }
    }

    /** Saves bytes a page produced (blob: / data: downloads, e.g. emulator save states). */
    fun saveBytes(name: String, mimeType: String, bytes: ByteArray): SavedFile = saveStream(name, mimeType) { it.write(bytes) }

    /** Like [saveBytes], but [write] streams the content, so large files never sit in memory. */
    fun saveStream(name: String, mimeType: String, write: (java.io.OutputStream) -> Unit): SavedFile {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
        val mime = mimeType.ifBlank {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RetroTV")
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create $safe in Downloads")
            resolver.openOutputStream(target)?.use(write) ?: throw IllegalStateException("Couldn't write $safe")
            target
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
            dir.mkdirs()
            val file = File(dir, safe)
            file.outputStream().use(write)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val saved = SavedFile(safe, uri, mime)
        _saved.tryEmit(saved)
        return saved
    }

    /** Where downloads land, for display. */
    val folderLabel: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads/RetroTV"
        else "Android/data/${context.packageName}/files/Download"

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
