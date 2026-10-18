package com.aiconsole.retrotv.apps

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.browser.BrowserActivity
import com.aiconsole.retrotv.data.Site

/**
 * "Install as app": each built-in site has a disabled `activity-alias` in the manifest (its own
 * name and TV banner, in the launcher's app row once enabled). Other sites become pinned
 * shortcuts where the launcher supports them.
 */
object SiteApps {

    private fun alias(context: Context, id: String) = ComponentName(context.packageName, "${context.packageName}.site.$id")

    /** True when this built-in site has a launcher alias in the manifest. */
    fun hasAlias(context: Context, site: Site): Boolean = site.builtIn && runCatching {
        context.packageManager.getActivityInfo(alias(context, site.id), PackageManager.MATCH_DISABLED_COMPONENTS)
    }.isSuccess

    fun isInstalled(context: Context, site: Site): Boolean {
        if (!hasAlias(context, site)) return false
        return context.packageManager.getComponentEnabledSetting(alias(context, site.id)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setInstalled(context: Context, site: Site, installed: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context, site.id),
            if (installed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    fun canPinShortcut(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut that opens [url] as its own app window. */
    fun pinShortcut(context: Context, name: String, url: String, color: Long): Boolean {
        if (!canPinShortcut(context)) return false
        val intent = Intent(context, SiteAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(url.toUri())
            .putExtra(SiteAppActivity.EXTRA_NAME, name)
        val shortcut = ShortcutInfoCompat.Builder(context, "url:" + url.hashCode())
            .setShortLabel(name.take(24))
            .setLongLabel(name)
            .setIcon(IconCompat.createWithBitmap(letterIcon(name, color)))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    private fun letterIcon(name: String, color: Long): Bitmap {
        val size = 192
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color.toInt()
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 40f, 40f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 96f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    /** Intent that opens a site in its own task (its own entry in recents), like a separate app. */
    fun appIntent(context: Context, url: String, name: String, siteId: String?): Intent =
        Intent(context, BrowserActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(url.toUri())
            .putExtra(BrowserActivity.EXTRA_SITE_ID, siteId)
            .putExtra(BrowserActivity.EXTRA_TITLE, name)
            .putExtra(BrowserActivity.EXTRA_STANDALONE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Trampoline behind the site aliases and pinned shortcuts: works out which site was launched
 * and opens it in a standalone browser task. Has no UI of its own.
 */
class SiteAppActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siteId = runCatching {
            packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA).metaData?.getString(META_SITE_ID)
        }.getOrNull()
        val site = RetroApp.from(this).sites.byId(siteId)
        val url = site?.url ?: intent?.dataString
        if (url != null) {
            startActivity(SiteApps.appIntent(this, url, site?.name ?: intent.getStringExtra(EXTRA_NAME) ?: url, site?.id))
        }
        finish()
    }

    companion object {
        const val META_SITE_ID = "com.aiconsole.retrotv.SITE_ID"
        const val EXTRA_NAME = "name"
    }
}
