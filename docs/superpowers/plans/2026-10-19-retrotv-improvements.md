# RetroTV Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the 19 improvements the owner approved on 2026-10-19 (stability, library, player, phone link, UI) without changing what RetroTV is: a TV browser over in-browser retro game sites with a built-in EmulatorJS player.

**Architecture:** Five phases, each its own `claude/<phase>` branch, CI build, merge to `main`, and an APK for the owner to test on the TV. Phase 1 is planned task by task below; phases 2–5 get the same treatment in their own plan files when Phase 1 has merged, because they build on its storage and server pieces.

**Tech Stack:** Kotlin, Jetpack Compose (TV focus helpers in `ui/Components.kt`), AndroidX WebKit, EmulatorJS 4.2.3 (`assets/player/`), JUnit 4 JVM tests (`app/src/test`).

**Spec:** the improvement list in the 2026-10-19 conversation, numbered 1–19; numbers below refer to it.

## Global Constraints

- minSdk 24, target/compile 37; single `:app` module, package `com.aiconsole.retrotv`.
- Build and test on GitHub Actions only (`gh workflow run android.yml --ref <branch>`); the laptop has no JDK/SDK. Every task's verify step is `testDebugUnitTest lintDebug assembleDebug` green on CI.
- JVM tests can't use `org.json` or Android classes: put logic in plain Kotlin objects.
- Never rewrite sources with PowerShell `Get-Content`/`Set-Content`.
- No DI: new singletons go on `RetroApp` as `by lazy`.
- Network/IO on `Dispatchers.IO`; rethrow `CancellationException` before broad catches.
- TV-first: every new control is reachable with the D-pad (`TvButton`/`TvClickable`/`ChoiceRow`), clicks on OK release.
- Built-in content stays legal: libretro thumbnails for art, homebrew/legal sources only.
- Ask the owner before any `v*` tag.

## Roadmap

Status 2026-10-19: phases 1-4 merged to main (each CI-green); phase 5 written on `claude/tv-ui` but not yet built: GitHub Actions stopped starting jobs (account billing/spending limit).

| Phase | Branch | Items | Depends on |
|---|---|---|---|
| 1. Stability | `claude/stability` | 1 saves as files + backup, 2 crash/freeze reports, 3 offline cores, 4 split `BrowserActivity`, 5 tests | none |
| 2. Library | `claude/library` | 6 game library + box art, 16 card previews/badges, 7 universal search, 18 site health | 1 (save thumbnails, `GameLibrary`) |
| 3. Player | `claude/player-plus` | 8 player assignment + per-game layouts, 9 CRT/scaling shaders, 10 rewind + quick save, 11 cheats | 1 (`SaveStore`) |
| 4. Phone link | `claude/phone-link` | 12 phone controller, 13 ROM upload, 14 save sync | 1 (`ReportShare` server + QR), 3 |
| 5. Big-screen UI | `claude/tv-ui` | 15 attract mode, 17 first-run setup, 19 readability | 2 (library art) |

Scope decisions (where the idea had to change to be buildable):
- **14 save sync:** Google Drive needs an OAuth app registered to the owner, so instead: TV-to-TV sync over the home network (one RetroTV finds another via NSD and swaps saves newer-wins), plus save export/import through the phone link and the backup zip.
- **15 attract mode:** no source of gameplay clips exists, so it cycles box art and in-game screenshots (libretro `Named_Boxarts`/`Named_Snaps`).
- **3 offline cores:** EmulatorJS files are already disk-cached, but in `cacheDir` (Android may purge it) and "Clear cache" deletes them. The work is moving them to `filesDir`, keeping them on clear, and prefetching a system's files on request.

## Review Focus

- **Huge save states** (N64/PSP states run to tens of MB): saving goes through the bridge in chunks without an OOM, and a save larger than 64 MB fails with a visible message rather than a crash. Owner: Task 1.
- **Storage full or the app killed mid-save:** the previous contents of that slot survive (write `.part`, then rename). Owner: Task 1.
- **Existing saves in IndexedDB:** the first launch of each game after the update moves them to files; none are lost, and an empty native slot never overwrites an IndexedDB one. Owner: Task 1.
- **Old `retrotv-backup.json` files** still restore after the backup becomes a zip. Owner: Task 2.
- **A crash inside the crash handler, or a crash during start-up:** the default handler still runs, so the app doesn't hang, and the report never contains cookies, tokens or URL query strings. Owner: Task 3.

---

## Phase 1: Stability

### Task 1: Saves stored as files (items 1, 5)

**Files:**
- Create: `app/src/main/java/com/aiconsole/retrotv/player/SaveStore.kt` (file layout + IO)
- Create: `app/src/main/java/com/aiconsole/retrotv/player/SaveNames.kt` (pure naming rules)
- Modify: `app/src/main/java/com/aiconsole/retrotv/player/GameFiles.kt` (serve `/save/...`)
- Modify: `app/src/main/java/com/aiconsole/retrotv/player/PlayerActivity.kt` (bridge `RetroTVSaves`)
- Modify: `app/src/main/assets/player/player.js` (save/load/slots via native; IndexedDB migration)
- Modify: `app/src/main/java/com/aiconsole/retrotv/player/PlayerUi.kt` (slot thumbnails + dates)
- Modify: `app/src/main/java/com/aiconsole/retrotv/RetroApp.kt` (`val saves by lazy { SaveStore(this) }`)
- Test: `app/src/test/java/com/aiconsole/retrotv/SaveRulesTest.kt`

**Interfaces:**
- Produces: `object SaveNames { fun dirName(gameKey: String): String; fun slotOf(fileName: String): Int?; const val MAX_STATE = 64L * 1024 * 1024; const val SLOTS = 4 }`. `dirName` = first 24 hex of SHA-1 of the key; `slotOf("slot3.state") == 3`, anything else is null.
- Produces: `class SaveStore(context)` rooted at `filesDir/saves/<dirName>/`, with files `slot<N>.state`, `slot<N>.png` and `game.txt` (the raw key, for backups):
  `fun open(gameKey: String, slot: Int, size: Long): String?` (token; null if size > MAX_STATE or slot ∉ 1..4), `fun write(token: String, base64: String): Boolean`, `fun close(token: String, pngBase64: String?): Boolean` (renames `.part` → final only when byte count == size), `fun file(gameKey: String, slot: Int): File?`, `fun slots(gameKey: String): Map<Int, Long>` (slot → lastModified), `fun root(): File`.
- Produces: GameFiles serves `GET https://appassets.androidplatform.net/save/<dirName>/slot<N>.state|.png` from `SaveStore.root()` (404 when missing; reject `..`).
- JS bridge name `RetroTVSaves` with `open(key, slot, size)`, `write(token, b64)`, `close(token, pngB64)`, `slots(key)` → JSON `{"1":1697000000000}`.

- [ ] **Step 1: Write the failing test** `SaveRulesTest`: `dirName("nes|https://x/a.zip")` is 24 lowercase hex chars and stable across calls; two different keys give different names; `slotOf("slot1.state") == 1`, `slotOf("slot4.png") == null`, `slotOf("slot9.state") == null`, `slotOf("../slot1.state") == null`.
- [ ] **Step 2: Run it** on CI (push the branch, `gh workflow run`). Expected: compile failure, `SaveNames` unresolved.
- [ ] **Step 3: Implement `SaveNames`**, then `SaveStore` using the same `.part`-then-rename pattern as `GameFiles.blobWrite`/`blobClose`; tokens in a `ConcurrentHashMap`; `close` writes the PNG only when the state was committed.
- [ ] **Step 4: Wire the player.** In `player.js`, `save(slot)` gets the state (`gameManager.getState()`) and a screenshot (`gameManager.screenshot()` when present, else none), opens a token, sends 512 KB base64 chunks, then closes. `load(slot)` does `fetch('/save/<dir>/slot<N>.state')` → `loadState(new Uint8Array(buf))`. `slots()` asks the bridge. The page receives `dir` as a new query parameter set by `PlayerActivity.loadGame()` from `SaveNames.dirName(key)`. Show messages for "too large", "storage full" (close returned false) and "empty slot".
- [ ] **Step 5: Migration in `player.js`:** after the game starts, read every IndexedDB `states` entry for this key; for each slot the native side doesn't have, upload it the same way; delete IndexedDB entries only once their upload's `close` succeeds.
- [ ] **Step 6: `PlayerUi` slot picker** shows each slot's PNG (loaded from the save URL via `BitmapFactory` on IO) and its date, or "Empty".
- [ ] **Step 7: Verify on CI:** tests green, lint clean, APK builds.
- [ ] **Step 8: Commit** `Saves as files with screenshots; move IndexedDB saves over`.

### Task 2: Backup zip with saves (items 1, 5)

**Files:**
- Create: `app/src/main/java/com/aiconsole/retrotv/data/BackupArchive.kt`
- Modify: `app/src/main/java/com/aiconsole/retrotv/ui/SettingsScreen.kt:200-225` (export `.zip`, import `.zip` or `.json`)
- Test: `app/src/test/java/com/aiconsole/retrotv/BackupArchiveTest.kt`

**Interfaces:**
- Consumes: `SaveStore.root()`, `Backup.export/import` (unchanged JSON).
- Produces: `object BackupArchive { fun write(out: OutputStream, json: String, savesRoot: File); fun read(input: InputStream, savesRoot: File): String }`. The zip holds `backup.json` plus `saves/<dir>/<file>`. `read` restores saves (an existing file wins when it's newer than the zip entry's time), skips entries with `..` or outside `saves/`, and returns the JSON. `fun isZip(head: ByteArray): Boolean` (starts with `PK\u0003\u0004`).

- [ ] **Step 1: Failing test** `BackupArchiveTest`, using `java.util.zip` and temp dirs only:
  - write and then read round-trips the JSON string and the bytes of `saves/abc/slot1.state`;
  - an entry `saves/../evil` isn't written;
  - `isZip` is true for the archive bytes and false for `"{".toByteArray()`.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement `BackupArchive`.** Export saves `retrotv-backup.zip` via `downloads.saveBytes(name, "application/zip", bytes)`. Import sniffs the head with `isZip`: a zip goes through `read`, anything else is treated as the old JSON.
- [ ] **Step 4: Verify on CI** and commit `Backups include saves (zip); old JSON backups still restore`.

### Task 3: Crash and freeze reports (item 2)

**Files:**
- Create: `app/src/main/java/com/aiconsole/retrotv/data/ProblemReport.kt`, `FreezeWatchdog.kt`, `ReportShare.kt`; `browser/RendererWatch.kt`, `browser/RendererRules.kt`. Port these from `../KITT-Launcher.2/app/src/main/java/com/aiconsole/kittlauncher2/` (same file names), changing the package and dropping KITT-only report sections (OBD connection log, vehicle settings).
- Modify: `RetroApp.kt` (install the handler and watchdog in `onCreate`), `BrowserActivity.kt` + `PlayerActivity.kt` (`onRenderProcessGone` → `RendererWatch`, last page shown → watchdog), `SettingsScreen.kt` ("Report a problem": share or QR), `gradle/libs.versions.toml` + `app/build.gradle.kts` (ZXing `core`, same version as KITT's catalog).
- Test: `app/src/test/java/com/aiconsole/retrotv/ReportRulesTest.kt`

**Interfaces:**
- Produces: `object ProblemReport { fun install(app: Application); fun build(context: Context): String; fun redact(text: String): String }`. `redact` strips URL query strings and fragments and any `token=`, `key=` or `cookie` values. `RetroApp.lastPage: String?`, set by both activities.
- Produces: `ReportShare` starts the same 10-minute, random-token, one-file HTTP server and QR code as KITT. Phase 4 reuses its server class: keep it as `class OneShotServer(port: Int = 0)` with `fun serve(file: File): String` (the URL).

- [ ] **Step 1: Failing test** `ReportRulesTest`: `redact("https://a.com/x?token=abc#f")` == `"https://a.com/x"`; `redact("cookie: sid=1")` doesn't contain `sid=1`; `RendererRules` cases copied from KITT's tests (unresponsive 20 s → kill; UI stall 3 s → kill).
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Port the files.** The handler chains to the previous default handler and wraps its own body in `runCatching`, so a failing report can't swallow the crash.
- [ ] **Step 4: Verify on CI** and commit `Crash and freeze reports, hung-page recovery (ported from KITT)`.

### Task 4: Offline emulator cores (item 3)

**Files:**
- Create: `app/src/main/java/com/aiconsole/retrotv/player/CoreFiles.kt` (pure: which EmulatorJS files a core needs)
- Modify: `GameFiles.kt` (`ejsDir` → `filesDir/emulatorjs`, migrating an existing `cacheDir/emulatorjs` once; `clearCache()` keeps cores; new `clearEmulators()`; `fun prefetch(core: String, onProgress: (Int, Int) -> Unit): Boolean`)
- Modify: `ui/SettingsScreen.kt` (per system: "Make offline" / "Ready offline" / size; "Delete emulator files")
- Test: `app/src/test/java/com/aiconsole/retrotv/CoreFilesTest.kt`

**Interfaces:**
- Produces: `object CoreFiles { fun forCore(core: String): List<String> }`, paths relative to `$EJS_VERSION/data/`: `loader.js`, `emulator.min.js`, `emulator.min.css`, `cores/reports/<core>.json`, `cores/<core>-wasm.data`, `cores/<core>-legacy-wasm.data`, plus `cores/<core>-thread-wasm.data` for cores in `Systems`' threaded set. Before relying on the list, check it against the files the player actually requests on CI's debug build, or the CDN listing at `https://cdn.emulatorjs.org/4.2.3/data/`.

- [ ] **Step 1: Failing test** `CoreFilesTest`: `forCore("nes")` contains `cores/nes-wasm.data` and `loader.js`, has no duplicates, and no entry contains `..`; a threaded core (PSP's core name from `Systems`) includes its `-thread-wasm.data`.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.** `prefetch` downloads each missing file through the existing `download` path (so it uses the same `.part`-then-rename), reports progress, and returns false on the first failure, leaving finished files in place.
- [ ] **Step 4: Verify on CI** and commit `Emulator files kept for offline play; per-system Make offline`.

### Task 5: Split `BrowserActivity` (item 4)

**Files:**
- Create in `browser/`: `BrowserInput.kt` (keys, pointer, pad routing), `BrowserClient.kt` (the `WebViewClient`/`WebChromeClient`: intercepts, pop-up holding, `onRenderProcessGone`), `GameHandoff.kt` (the `RetroTVGame` bridge, `launchGame`, the `PlayerActivity` result), `BrowserBridges.kt` (`RetroTVFiles`, `RetroTVView` and the other JS interfaces).
- Modify: `browser/BrowserActivity.kt`: the lifecycle and wiring stay here, target under 400 lines.

**Interfaces:**
- Behavior-preserving: no user-visible change and no new settings. Each extracted class takes the `BrowserActivity` (or a narrow interface it implements) in its constructor; the JS interface names and the method signatures pages call stay exactly as they are.

- [ ] **Step 1: Extract one class at a time**, rebuilding on CI after each move; the existing 23 tests must stay green.
- [ ] **Step 2: Check:** `git diff --stat` shows `BrowserActivity.kt` under 400 lines, and `grep -rn "addJavascriptInterface"` lists the same interface names as before.
- [ ] **Step 3: Commit** `Split BrowserActivity by responsibility (no behaviour change)`.

### Task 6: Phase 1 wrap-up

- [ ] Whole-branch review (`git diff origin/main...`), CI green, merge to `main` (fast-forward), push.
- [ ] Update `CLAUDE.md` (saves, reports, offline cores, the new browser files) and the features page draft. Don't push the page until the release is tagged.
- [ ] Hand the owner the APK with the on-TV checks: an old save survives, save/load with thumbnails, Report a problem QR, offline game start with Wi-Fi off.
