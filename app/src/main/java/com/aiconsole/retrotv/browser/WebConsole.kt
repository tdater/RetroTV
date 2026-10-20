package com.aiconsole.retrotv.browser

import android.util.Log
import android.webkit.ConsoleMessage
import com.aiconsole.retrotv.data.ProblemReport

/**
 * Page errors and warnings go to logcat (tag `WebConsole`), which "Report a problem" includes:
 * that's how a game that stops on one TV's WebView can be diagnosed. Repeats and floods are cut.
 */
class WebConsole(private val source: String) {
    private var last = ""
    private var count = 0

    fun log(message: ConsoleMessage): Boolean {
        val level = message.messageLevel()
        if (level != ConsoleMessage.MessageLevel.ERROR && level != ConsoleMessage.MessageLevel.WARNING) return true
        val line = "$source ${level.name.lowercase()}: ${message.message()} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})"
        if (line == last || count >= MAX) return true
        last = line
        count++
        Log.w(TAG, ProblemReport.redact(line.take(600)))
        return true
    }

    /** A new page: count again. */
    fun reset() {
        count = 0
        last = ""
    }

    private companion object {
        const val TAG = "WebConsole"
        const val MAX = 150
    }
}
