package com.aiconsole.retrotv.player

import android.content.Context
import android.net.Uri
import com.aiconsole.retrotv.RetroApp

/** Starts RetroTV's own player for a ROM file (shared by "Play a ROM file", Downloads and the browser's download panels). */
object RomLauncher {

    fun start(context: Context, value: String, name: String, core: String) {
        val title = name.substringBeforeLast('.')
        context.startActivity(PlayerActivity.intent(context, GameRequest(core = core, game = value, gameName = title, title = title)))
    }

    /** Copies the file into the game folder (blocking: call off the main thread); returns the game value for [start]. */
    fun import(context: Context, uri: Uri, name: String): String = RetroApp.from(context).games.importRom(uri, name)
}
