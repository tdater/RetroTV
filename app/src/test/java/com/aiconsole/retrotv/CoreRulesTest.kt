package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.Prefs
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.player.Systems
import com.aiconsole.retrotv.downloads.Emulators
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.Gamepad
import com.aiconsole.retrotv.web.Surrogates
import com.aiconsole.retrotv.web.UserAgents
import com.aiconsole.retrotv.web.Userscripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlInputTest {

    @Test
    fun addressesAndSearches() {
        assertEquals("https://example.com", UrlInput.toUrl("example.com"))
        assertEquals("https://retrogames.cc/nes", UrlInput.toUrl(" retrogames.cc/nes "))
        assertEquals("http://x.org/a", UrlInput.toUrl("http://x.org/a"))
        assertEquals("https://192.168.1.4:8080", UrlInput.toUrl("192.168.1.4:8080"))
        assertEquals("https://www.google.com/search?q=super+mario+3", UrlInput.toUrl("super mario 3"))
        assertEquals("https://duckduckgo.com/?q=zelda", UrlInput.toUrl("zelda", UrlInput.SearchEngine.DuckDuckGo))
        assertEquals("", UrlInput.toUrl("   "))
    }

    @Test
    fun hostsAndSameSite() {
        assertEquals("retrogames.cc", UrlInput.host("https://www.retrogames.cc/nes-games/x.html"))
        assertNull(UrlInput.host("intent://foo"))
        assertTrue(UrlInput.sameSite("https://archive.org/details/x", "https://ia800.us.archive.org/file"))
        assertTrue(UrlInput.sameSite("https://www.bbc.co.uk/a", "https://news.bbc.co.uk/b"))
        assertFalse(UrlInput.sameSite("https://retrogames.cc/", "https://ads.example.net/"))
    }

    @Test
    fun fileNames() {
        assertEquals("Lemuroid.apk", UrlInput.guessFileName("https://x.io/dl/Lemuroid.apk?raw=1", null, null))
        assertEquals("game (USA).zip", UrlInput.guessFileName("https://x.io/get?id=3", "attachment; filename=\"game (USA).zip\"", null))
        assertEquals("app.apk", UrlInput.guessFileName("https://x.io/app", null, "application/vnd.android.package-archive"))
        assertTrue(UrlInput.isApk("RetroArch.APK", null))
    }
}

class AdBlockTest {

    @Test
    fun blocksAdNetworksAndSubdomains() {
        assertTrue(AdBlock.isBlocked("doubleclick.net"))
        assertTrue(AdBlock.isBlocked("securepubads.g.doubleclick.net"))
        assertTrue(AdBlock.isBlocked("a.exoclick.com"))
        assertFalse(AdBlock.isBlocked("retrogames.cc"))
        assertFalse(AdBlock.isBlocked(null))
    }

    @Test
    fun gameHostsAreNeverBlocked() {
        assertFalse(AdBlock.isBlocked("archive.org"))
        assertFalse(AdBlock.isBlocked("cdn.emulatorjs.org"))
        assertFalse(AdBlock.isBlocked("cdn.jsdelivr.net"))
    }

    @Test
    fun customRulesParseHostsFilesAndAdblockSyntax() {
        val parsed = AdBlock.parseHosts("# comment\n0.0.0.0 bad.example.com\n||ads.foo.net^\n*.track.io\nlocalhost\n")
        assertEquals(setOf("bad.example.com", "ads.foo.net", "track.io"), parsed)
        AdBlock.setCustom("bad.example.com")
        assertTrue(AdBlock.isBlocked("x.bad.example.com"))
        AdBlock.setCustom("")
        assertFalse(AdBlock.isBlocked("x.bad.example.com"))
    }
}

class UserscriptsTest {

    private val source = """
        // ==UserScript==
        // @name        Big Buttons
        // @namespace   retro
        // @version     1.2
        // @match       https://*.retrogames.cc/*
        // @exclude     https://www.retrogames.cc/login*
        // @run-at      document-start
        // @noframes
        // ==/UserScript==
        console.log('hi');
    """.trimIndent()

    @Test
    fun parsesMetadata() {
        val s = Userscripts.parse(source)
        assertNotNull(s)
        s!!
        assertEquals("Big Buttons", s.name)
        assertEquals("1.2", s.version)
        assertEquals(listOf("https://*.retrogames.cc/*"), s.matches)
        assertTrue(s.noFrames)
        assertEquals("retro-big-buttons", s.id)
    }

    @Test
    fun matchesUrls() {
        val s = Userscripts.parse(source)!!
        assertTrue(Userscripts.appliesTo(s, "https://www.retrogames.cc/nes-games/mario.html"))
        assertTrue(Userscripts.appliesTo(s, "https://retrogames.cc/"))
        assertFalse(Userscripts.appliesTo(s, "https://www.retrogames.cc/login.php"))
        assertFalse(Userscripts.appliesTo(s, "https://example.com/"))
    }

    @Test
    fun rejectsNonScripts() {
        assertNull(Userscripts.parse("console.log(1)"))
    }

    @Test
    fun wrapperGuardsAndShims() {
        val js = Userscripts.wrap(Userscripts.parse(source)!!)
        assertTrue(js.contains("GM_xmlhttpRequest"))
        assertTrue(js.contains("window.top !== window.self"))
        assertTrue(js.contains("console.log('hi')"))
    }
}

class GamepadTest {

    @Test
    fun mappingRoundTrips() {
        val custom = Gamepad.DEFAULT + (Gamepad.BUTTON_A to 62)
        assertEquals(custom, Gamepad.decode(Gamepad.encode(custom)))
        assertEquals(Gamepad.DEFAULT, Gamepad.decode(""))
        assertEquals(Gamepad.DEFAULT, Gamepad.decode("garbage"))
    }

    @Test
    fun keyLabels() {
        assertEquals("X", Gamepad.keyLabel(52))
        assertEquals("Enter", Gamepad.keyLabel(66))
        assertEquals("F1", Gamepad.keyLabel(131))
        assertEquals("5", Gamepad.keyLabel(12))
    }

    @Test
    fun stickDeadZone() {
        assertEquals(emptySet<Int>(), Gamepad.stickDirections(0.2f, -0.3f))
        assertEquals(setOf(Gamepad.DPAD_LEFT, Gamepad.DPAD_UP), Gamepad.stickDirections(-0.9f, -0.6f))
    }
}

class EmulatorsTest {

    private val armsx2 = listOf(
        "ARMSX2-2.8.4-a11-armv8.2-sdk30.apk" to "u30",
        "ARMSX2-2.8.4-a13-armv8.2-sdk33.apk" to "u33",
        "ARMSX2-2.8.4-a15-armv8.2-sdk35.apk" to "u35",
        "source.zip" to "zip"
    )

    @Test
    fun picksHighestSdkTheDeviceRuns() {
        assertEquals("u33", Emulators.pickApk(armsx2, null, null, 34, true))
        assertEquals("u35", Emulators.pickApk(armsx2, null, null, 36, true))
        assertEquals("u30", Emulators.pickApk(armsx2, null, null, 28, true))
    }

    @Test
    fun prefersVariantAndAbi() {
        val azahar = listOf("azahar-android-googleplay-2126.2.apk" to "gp", "azahar-android-vanilla-2126.2.apk" to "van")
        assertEquals("van", Emulators.pickApk(azahar, "vanilla", null, 34, true))
        val abis = listOf("app-armeabi-v7a.apk" to "v7", "app-arm64-v8a.apk" to "v8", "app-x86_64.apk" to "x86")
        assertEquals("v8", Emulators.pickApk(abis, null, null, 34, true))
        assertEquals("v7", Emulators.pickApk(abis, null, null, 34, false))
        assertNull(Emulators.pickApk(listOf("notes.txt" to "t"), null, null, 34, true))
    }
}

class UserAgentsTest {

    private val tv = "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K VH2 Build/STT1.211025.001.Z4; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"

    @Test
    fun desktopKeepsChromeVersion() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            UserAgents.desktop(tv)
        )
    }

    @Test
    fun mobileDropsWebViewMarkers() {
        val ua = UserAgents.mobile(tv)
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
        assertTrue(ua.contains("Chrome/131"))
    }
}

class SystemsTest {

    @Test
    fun systemsFromCoreNamesAndIds() {
        assertEquals("Genesis / Mega Drive", Systems.label("segaMD"))
        assertEquals("Genesis / Mega Drive", Systems.label("genesis_plus_gx"))
        assertEquals("NES / Famicom", Systems.label("fceumm"))
        assertEquals("unknowncore", Systems.label("unknowncore"))
        assertTrue(Systems.needsThreads("psp"))
        assertTrue(Systems.needsThreads("dosbox_pure"))
        assertFalse(Systems.needsThreads("gba"))
    }

    @Test
    fun ownRomFiles() {
        assertEquals("segaMD", Systems.forFile("Sonic The Hedgehog (USA).md")?.id)
        assertEquals("snes", Systems.forFile("/x/Super Metroid.SFC")?.id)
        assertEquals("gba", Systems.forFile("pokemon.gba")?.id)
        assertNull(Systems.forFile("arcade-game.zip"))
        assertNull(Systems.forFile("disc.chd"))
        assertNull(Systems.forFile("readme"))
        assertTrue(Systems.isRom("game.zip"))
        assertTrue(Systems.isRom("game.nes"))
        assertFalse(Systems.isRom("RetroArch.apk"))
        // Every extension belongs to one system only.
        val all = Systems.ALL.flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }
}

class SurrogatesTest {

    @Test
    fun adLibrariesGetStandIns() {
        assertNotNull(Surrogates.find("pagead2.googlesyndication.com", "/pagead/js/adsbygoogle.js"))
        assertNotNull(Surrogates.find("www.googletagmanager.com", "/gtag/js"))
        assertNotNull(Surrogates.find("www.google-analytics.com", "/analytics.js"))
        assertNotNull(Surrogates.find("securepubads.g.doubleclick.net", "/tag/js/gpt.js"))
        assertNotNull(Surrogates.find("api.adinplay.com", "/libs/aiptag/pub/CCG/playclassic.games/tag.min.js"))
        assertNull(Surrogates.find("popads.net", "/pop.js"))
    }

    @Test
    fun scriptsAreRecognised() {
        assertTrue(Surrogates.looksLikeScript("/pop.js", null))
        assertTrue(Surrogates.looksLikeScript("/x/tag.min.js?v=2", null))
        assertFalse(Surrogates.looksLikeScript("/pixel.gif", "image/webp,*/*"))
    }

    @Test
    fun standInsKeepTheApi() {
        val ads = Surrogates.find("pagead2.googlesyndication.com", "/pagead/js/adsbygoogle.js")!!
        assertTrue(ads.contains("adBreakDone"))
        val aip = Surrogates.find("api.adinplay.com", "/tag.min.js")!!
        assertTrue(aip.contains("AIP_COMPLETE"))
    }
}

class PrefsDefaultsTest {

    @Test
    fun newDefaults() {
        assertEquals(ControllerMode.Auto, Prefs().controllerMode)
        assertTrue(Prefs().builtInPlayer)
        assertTrue(ViewMode.Desktop.viewportWidth > ViewMode.Mobile.viewportWidth)
    }
}
