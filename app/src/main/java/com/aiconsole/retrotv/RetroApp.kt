package com.aiconsole.retrotv

import android.app.Application
import android.content.Context
import com.aiconsole.retrotv.data.FreezeWatchdog
import com.aiconsole.retrotv.data.History
import com.aiconsole.retrotv.data.ProblemReport
import com.aiconsole.retrotv.data.Settings
import com.aiconsole.retrotv.data.SiteStore
import com.aiconsole.retrotv.downloads.Downloads
import com.aiconsole.retrotv.player.GameFiles
import com.aiconsole.retrotv.player.GameLibrary
import com.aiconsole.retrotv.player.SaveStore
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.UserscriptStore

/** App-wide singletons (no DI framework): `RetroApp.from(context).settings` etc. */
class RetroApp : Application() {

    val settings by lazy { Settings(this) }
    val sites by lazy { SiteStore(this) }
    val history by lazy { History(this) }
    val userscripts by lazy { UserscriptStore(this) }
    val downloads by lazy { Downloads(this) }
    val games by lazy { GameFiles(this) }
    val library by lazy { GameLibrary(this) }
    val saves by lazy { SaveStore(this) }

    override fun onCreate() {
        super.onCreate()
        ProblemReport.installCrashHandler(this)
        FreezeWatchdog.start(this)
        AdBlock.setCustom(settings.value.customBlockList)
    }

    companion object {
        fun from(context: Context): RetroApp = context.applicationContext as RetroApp
    }
}
