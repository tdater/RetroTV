# CLAUDE.md

Guidance for Claude Code in this repository.

## How to work on this project

The owner's personal project; they vibe code it. Deliver complete, working changes with little back-and-forth: decide routine things yourself, ask only when blocked or before anything risky or irreversible. Once a change has been discussed, carry it through: implement, commit, push a `claude/<topic>` branch, run CI, and merge to `main` when it passes. Ask before creating `v*` tags.

- **Look before changing:** read the relevant code, `gradle/libs.versions.toml` and `AndroidManifest.xml` first. If this file and the code disagree, the code wins.
- **Edit sources, not generated output** (`app/build/`, `.gradle/`, `.kotlin/`).
- **Verify before calling it done:** `testDebugUnitTest lintDebug assembleDebug` must pass (CI). A passing build is not proof of on-TV behaviour; say what the owner should test.
- **Don't rewrite source files with PowerShell `Get-Content`/`Set-Content`** (ANSI corruption of `·`, `…`, `Я`). Use the editor tools.

## Build (CI mode)

The owner's laptop has no Android Studio/JDK/SDK; don't install them. Build on GitHub Actions:

```powershell
git push -u origin claude/my-change
gh workflow run android.yml --ref claude/my-change
gh run list --workflow android.yml --branch claude/my-change --limit 1
gh run watch <run-id> --exit-status
gh run view <run-id> --log-failed
gh run download <run-id> -n app-debug -D "$env:USERPROFILE\Downloads\RetroTV-<run-id>"
```

Toolchain: AGP 9.x with built-in Kotlin, Compose BOM, androidx.webkit. minSdk 24, target/compile 37. Single `:app` module, package `com.aiconsole.retrotv`.

## What this is

An Android TV app that replaces installing emulators: a streamlined TV browser over in-browser retro game sites.

- **Home (`ui/MainActivity`, `HomeScreen`)**: site grid (`data/Sites.kt` `BuiltInSites` + user sites in `SiteStore`), tag filters, favourites, "Continue playing" (`History`), per-site long-press menu. Other screens: `DownloadsScreen` (emulator APKs, game sources, downloads, direct link), `ScriptsPanel`, `SettingsScreen`, `HistoryScreen`.
- **Browser (`browser/BrowserActivity`)**: one WebView in a FrameLayout with a Compose overlay (`BrowserUi.kt`: toolbar, quick menu, panels) and a pointer (`Cursor.kt`).
  - Remote: D-pad moves the pointer, OK taps (DOWN+UP on release; drag if moved while held), hold OK / Menu = quick menu, Back = back, CH± scroll, Play/Pause toggles pointer ↔ D-pad-keys mode. Touches are dispatched to the root view, so the pointer clicks the overlay toolbar too.
  - Controllers (`VirtualPads`): events never reach the page natively. `POLYFILL` replaces `navigator.getGamepads()` in every frame (document-start script) and reads state through the `RetroTVPad` JS bridge (`state()` marks "page reads pads", `peek()` doesn't). Controller modes: **Auto** (default: gamepad while the page polls pads, mapped keys otherwise, decided per button press), Gamepad API, Keyboard keys (`web/Gamepad.kt`, user-editable), Both. Select+Start / Mode = menu. "Controller moves pointer" lets a controller browse.
  - Ad blocking (`web/AdBlock.kt`): sub-resource host blocking in `shouldInterceptRequest`, never the main frame; an allow-list protects game/emulator hosts. Blocked scripts get a stand-in from `web/Surrogates.kt` (AdSense incl. `adBreak`, gtag/GTM, analytics, GPT, AdinPlay) or an empty script, because game pages wait on ad callbacks. Pop-ups and gesture-less cross-site redirects are held as "blocked pop-up". Per-site off switch.
  - Per-site options (`Settings.site(host)`): view (desktop UA + 1280 px layout / mobile UA + 640 px layout, set by `PageScripts.VIEWPORT` via `RetroTVView`), ad block, game player, controller mode, text size.
  - Full screen: windowed (toolbar) / full screen, HTML5 `onShowCustomView`, and "Focus game" (`PageScripts.FOCUS_GAME`, CSS that blows the largest canvas/iframe up; no user gesture needed).
  - Downloads (`downloads/Downloads.kt`): DownloadManager to Downloads/RetroTV; APKs install via the system installer (asks for unknown-sources); blob:/data: downloads saved via `RetroTVFiles` bridge with one-time tokens.
  - Userscripts (`web/Userscripts.kt`, `UserscriptStore`): document-start in all frames, GM_* shims; `.user.js` links prompt to install; Greasy Fork search (`GreasyFork`).
- **Built-in game player (`player/`)**: `assets/js/game-hook.js` runs at document start in every frame. When a page sets `EJS_core`/`EJS_gameUrl` and adds EmulatorJS's `loader.js`, it asks `RetroTVGame.enabled()`; if RetroTV's player is on for the site it sends the game (`launch(json)`, blob: files in base64 chunks) and makes the site loader's read of `EJS_gameUrl` throw so the site's emulator never starts. `BrowserActivity.launchGame` builds a `GameRequest` and starts `PlayerActivity` for a result ("Play on the site instead" → `sitePlayer` for the visit).
  - `PlayerActivity`: full-screen WebView on `https://appassets.androidplatform.net/player/index.html` (`assets/player/`), EmulatorJS **4.2.3** from `cdn.emulatorjs.org`. `GameFiles` serves the page, `/rom/<token>/<name>` (site files fetched natively with cookies + referrer, since their servers' CORS only allows the site; finished downloads cached in `filesDir/games`, LRU 1.5 GB) and the EmulatorJS files (disk cache, CORS + CORP headers). Threaded cores (PSP, DOS) load with COOP/COEP (`coi=1`).
  - Input: `VirtualPads` + the remote as player 1 when no controller (`onRemoteKey`). Back / Menu / Select+Start = game menu (`PlayerUi.kt`: save/load 4 slots in the page's IndexedDB, restart, fast-forward, mute, EmulatorJS menu with the pointer, play on site, exit). A start failure retries once with legacy (WebGL 1) cores, then offers the site's player.
  - `GameLibrary`: recent games (home row). "Play a ROM file" / Downloads ▸ Play copy the file into the game folder; `Systems` picks the console from the extension or asks.
- **Site apps (`apps/SiteApps.kt`)**: each built-in site has a disabled `activity-alias` (own label + `drawable-xhdpi/banner_<id>.png`) → `SiteAppActivity` trampoline → standalone browser task (`FLAG_ACTIVITY_NEW_DOCUMENT`) with its own WebView profile (`sandboxApps`, MULTI_PROFILE). Other sites use pinned shortcuts. **Adding a built-in site needs a matching alias, string and banner** (banners are generated PNGs).
- **Emulators (`downloads/Emulators.kt`)**: APK sources resolved at tap time (GitHub latest release with `pickApk`, scrape, direct, or website).

## Conventions

- No DI: `RetroApp.from(context).settings / sites / history / userscripts / downloads / games / library`.
- Testing site behaviour without a TV: drive the installed Edge with `playwright-core` (`channel: 'msedge'`) from the scratchpad, mimicking `AdBlock`/`Surrogates`, and inject `game-hook.js` with a fake `RetroTVGame`; the player page can be loaded with `appassets` routed to `assets/player/`.
- TV focus: use `TvClickable`/`TvButton`/`ChoiceRow` (`ui/Components.kt`); clicks fire on OK release only when the press started on that control.
- JVM unit tests (`CoreRulesTest.kt`) can't use `org.json` or Android classes; keep rules in plain Kotlin objects.
- Threading: network/IO on `Dispatchers.IO`; rethrow `CancellationException` before broad catches.
