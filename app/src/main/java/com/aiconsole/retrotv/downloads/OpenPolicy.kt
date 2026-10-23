package com.aiconsole.retrotv.downloads

import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.player.Systems

enum class OpenAction { PlayInRetroTV, InstallApk, OpenWith }

/** What to do with a finished download (pure, unit-tested): game files never go to another app such as RetroArch. */
object OpenPolicy {
    fun decide(fileName: String, mime: String?): OpenAction = when {
        UrlInput.isApk(fileName, mime) -> OpenAction.InstallApk
        Systems.isRom(fileName) -> OpenAction.PlayInRetroTV
        else -> OpenAction.OpenWith
    }
}
