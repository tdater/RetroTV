# RetroTV Phase 2: Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Items 6, 16, 7 and 18 from the approved list: a game library with box art, site cards that preview your last game and show which sites hand games to RetroTV's player, search across every site, and a site health check.

**Architecture:** `GameLibrary` grows from a recent list into a library (play time, play count, favourites). Box art comes from libretro's thumbnail server: RetroTV downloads a console's list of names once, fuzzy-matches the game title against it (pure `ArtNames`), and caches the matched image. Search and health are pure rule objects (`SiteSearch`, `SiteHealth`) behind thin UI.

**Tech Stack:** Kotlin, Compose (TV focus helpers in `ui/Components.kt`), `HttpURLConnection` (no new dependencies), JUnit 4.

**Spec:** items 6, 7, 16 and 18 of the 2026-10-19 improvement list; roadmap in `docs/superpowers/plans/2026-10-19-retrotv-improvements.md`.

## Global Constraints

Same as the roadmap plan's Global Constraints (CI-only builds, minSdk 24, JVM tests without `org.json`/Android classes, TV focus, no DI, ask before `v*` tags). In addition:
- Art source: `https://thumbnails.libretro.com/<System>/Named_Boxarts/<Name>.png`. Folder listings are HTML pages of `href="<url-encoded name>.png"` (checked 2026-10-19).
- No new libraries (no Coil): images decode with `BitmapFactory`, downsampled to at most 512 px.

## Review Focus

- **A title with no good match** (homebrew, a site's odd title): shows the console-coloured placeholder, never another game's cover. `ArtNames.best` returns null below its score threshold. Owner: Task 2.
- **No internet, or the art server is down:** the library still opens instantly with placeholders, and nothing retries in a tight loop (a failed lookup is remembered for 24 h). Owner: Task 2.
- **Sites behind Cloudflare** answering 403/429/503 to a plain request: counted as up, not "Down". Owner: Task 5.
- **Search text with `&`, `#`, spaces or non-ASCII:** URL-encoded in every template. Owner: Task 4.
- **Libraries recorded before this update** (no play time): load with zeros, not dropped. Owner: Task 1.

---

### Task 1: Library data (item 6)

**Files:**
- Modify: `player/GameRequest.kt` (`RecentGame`, `GameLibrary`)
- Create: `player/LibraryRules.kt`
- Modify: `player/PlayerActivity.kt` (time played between `onResume` and `onPause`)
- Test: `app/src/test/java/com/aiconsole/retrotv/LibraryRulesTest.kt`

**Interfaces:**
- Produces: `data class RecentGame(val request: GameRequest, val playedAt: Long, val playMs: Long = 0, val plays: Int = 1, val favorite: Boolean = false)`. The JSON keys `ms`, `n` and `fav` are optional on load.
- Produces: `GameLibrary.addPlayTime(key: String, ms: Long)`, `GameLibrary.setFavorite(key: String, on: Boolean)`. `record` increments `plays` and keeps `playMs`/`favorite`; `MAX` goes from 30 to 300.
- Produces: `enum class LibrarySort { Recent, Name, MostPlayed }` and `object LibraryRules { fun sort(games: List<RecentGame>, by: LibrarySort): List<RecentGame>; fun filter(games: List<RecentGame>, system: String?, query: String): List<RecentGame>; fun systems(games: List<RecentGame>): List<String>; fun playTime(ms: Long): String }`. `filter` matches the system by `Systems.of(core)?.id` and the query case-insensitively against `displayTitle`. `playTime(0)` = `""`, `playTime(90_000)` = `"1 min"`, `playTime(5_400_000)` = `"1 h 30 min"`. Favourites sort first in every order.

- [ ] **Step 1: Failing test** `LibraryRulesTest`: the three sorts (favourite first, then Recent by `playedAt` desc, Name A–Z ignoring case, MostPlayed by `playMs` desc); `filter` by system id and by query; `systems` distinct and in first-seen order; the `playTime` strings above.
- [ ] **Step 2: Run on CI.** Expect a compile failure: `LibraryRules` unresolved.
- [ ] **Step 3: Implement.** `PlayerActivity` notes `SystemClock.elapsedRealtime()` in `onResume` and adds the difference in `onPause` via `app.library.addPlayTime(request.key, ms)`, ignoring spans under 5 s.
- [ ] **Step 4: Verify on CI** and commit `Library: play time, play count, favourites`.

### Task 2: Box art (item 6)

**Files:**
- Create: `player/ArtNames.kt` (pure: system folders, title normalising, best match)
- Create: `player/ArtStore.kt` (listing + image download and cache)
- Create: `ui/ArtImage.kt` (composable)
- Modify: `RetroApp.kt` (`val art by lazy { ArtStore(this) }`)
- Test: `app/src/test/java/com/aiconsole/retrotv/ArtNamesTest.kt`

**Interfaces:**
- Produces: `object ArtNames { fun folder(core: String): String?; fun normalize(title: String): String; fun best(title: String, names: List<String>): String?; fun parseListing(html: String): List<String> }`.
  - `folder` maps `Systems.of(core)?.id` to the libretro folder: nes → `Nintendo - Nintendo Entertainment System`, snes → `Nintendo - Super Nintendo Entertainment System`, n64 → `Nintendo - Nintendo 64`, gb → `Nintendo - Game Boy`, gba → `Nintendo - Game Boy Advance`, nds → `Nintendo - Nintendo DS`, vb → `Nintendo - Virtual Boy`, segaMD → `Sega - Mega Drive - Genesis`, segaMS → `Sega - Master System - Mark III`, segaGG → `Sega - Game Gear`, segaCD → `Sega - Mega-CD - Sega CD`, sega32x → `Sega - 32X`, segaSaturn → `Sega - Saturn`, psx → `Sony - PlayStation`, psp → `Sony - PlayStation Portable`, arcade → `FBNeo - Arcade Games`, mame → `MAME`, atari2600 → `Atari - 2600`, atari5200 → `Atari - 5200`, atari7800 → `Atari - 7800`, lynx → `Atari - Lynx`, jaguar → `Atari - Jaguar`, pce → `NEC - PC Engine - TurboGrafx 16`, pcfx → `NEC - PC-FX`, ngp → `SNK - Neo Geo Pocket Color`, ws → `Bandai - WonderSwan Color`, coleco → `Coleco - ColecoVision`, 3do → `The 3DO Company - 3DO`, amiga → `Commodore - Amiga`, c64 → `Commodore - 64`, dos → `DOS`. Anything else → null.
  - `normalize`: lowercase; drop `(...)` and `[...]` groups; `&` → `and`; a trailing `, the` → a leading `the `; drop a leading `the `; drop file extensions; keep `[a-z0-9]` and single spaces only.
  - `best`: the candidate whose normalised name equals the normalised title, preferring `(USA)`, then `(World)`, then `(Europe)`, then the shortest; otherwise the candidate with the highest token overlap (Jaccard ≥ 0.75), ties broken the same way; otherwise null.
  - `parseListing`: URL-decoded names (without `.png`) from `href="....png"`.
- Produces: `class ArtStore(context)`: `suspend fun art(core: String, title: String): File?` (IO; cached under `filesDir/art/`, listings in `filesDir/art/lists/<folder>.txt` refreshed after 30 days, misses remembered 24 h in prefs `art`); `fun cached(core: String, title: String): File?` (no network).
- Produces: `@Composable fun ArtImage(core: String, title: String, modifier: Modifier, fallbackColor: Color)`: shows the cached or fetched art, else a placeholder tile with the console label.

- [ ] **Step 1: Failing test** `ArtNamesTest`:
  - `folder("nes")` and `folder("fceumm")` are the NES folder; `folder("unknown")` is null.
  - `normalize("Legend of Zelda, The (USA) [!].nes")` == `"legend of zelda"`; `normalize("Ghosts 'n Goblins")` == `"ghosts n goblins"`.
  - `best("Super Mario Bros 3", listOf("Super Mario Bros. 3 (Europe)", "Super Mario Bros. 3 (USA)", "Super Mario Bros. (World)"))` == `"Super Mario Bros. 3 (USA)"`.
  - `best("Contra", listOf("Super Contra (USA)", "Contra (USA)"))` == `"Contra (USA)"`.
  - `best("My Homebrew Thing", listOf("Contra (USA)"))` is null.
  - `parseListing("""<a href="3%20Ninjas%20Kick%20Back%20(USA).png">x</a>""")` == `listOf("3 Ninjas Kick Back (USA)")`.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement**, re-encoding the name for the image URL with `URLEncoder` and `+` → `%20`. Decode with `inSampleSize` down to 512 px or less.
- [ ] **Step 4: Verify on CI** and commit `Box art from libretro thumbnails, matched and cached`.

### Task 3: Library screen and home row (items 6, 16)

**Files:**
- Create: `ui/LibraryScreen.kt`
- Modify: `ui/MainActivity.kt` (`Screen.Library`), `ui/HomeScreen.kt` (header "My games" button; recent row shows art), `ui/GamesUi.kt` (`RecentGamesRow` tiles with `ArtImage`)

**Interfaces:**
- Consumes: `LibraryRules`, `ArtImage`, `GameLibrary.setFavorite`, `playRecent`.
- The library is a grid of art tiles (title, console, play time, last played). The top row has system filter chips, sort `ChoiceRow` and a search field. Long-press opens a menu: Play, Favourite, Remove, Open the site page.

- [ ] **Step 1: Build it.** It's UI-only and covered by Task 1's rules. Verify on CI: lint, assemble and the existing tests.
- [ ] **Step 2: Commit** `My games: library screen with box art; art on the recent row`.

### Task 4: Universal search and site-card badges (items 7, 16)

**Files:**
- Create: `data/SiteSearch.kt`, `ui/SearchScreen.kt`
- Create: `data/SiteBadges.kt`
- Modify: `browser/GameHandoff.kt` (`launchGame` records the page host in `Settings.playerHosts`), `data/Settings.kt` (`playerHosts: Set<String>`, persisted), `ui/HomeScreen.kt` (header "Search all sites"; `SiteCard` shows the last game and a "Player ✓" pill), `ui/MainActivity.kt` (`Screen.Search`)
- Test: `app/src/test/java/com/aiconsole/retrotv/SearchAndBadgesTest.kt`

**Interfaces:**
- Produces: `object SiteSearch { fun url(site: Site, query: String, engine: UrlInput.SearchEngine): String }`, with templates checked on 2026-10-19:
  - retrogames.cc `https://www.retrogames.cc/search?q=Q`; playretrogames.com `https://www.playretrogames.com/search.php?q=Q`;
  - rec0ded88.com, free80sarcade.com, emulatorgamer.com, retrogamesnexus.com, playclassic.games: `https://<host>/?s=Q`;
  - archive.org collections (`/details/<c>`): `https://archive.org/details/<c>?query=Q`;
  - anything else: `UrlInput.toUrl("site:<host> <query>", engine)`.
  
  Q is `URLEncoder.encode(query, "UTF-8")`.
- Produces: `object SiteBadges { fun lastGame(host: String, games: List<RecentGame>): RecentGame?; fun usesPlayer(host: String, playerHosts: Set<String>): Boolean }`. Hosts are compared without `www.`.
- The search screen has a query field plus "Your games" (library matches, grouped by console) and one tile per visible site ("Search Contra on RetroGames.cc"), opening `SiteSearch.url` in the browser.

- [ ] **Step 1: Failing test** `SearchAndBadgesTest`:
  - `url(retrogames_cc, "mario & luigi")` == `https://www.retrogames.cc/search?q=mario+%26+luigi`;
  - `url(internetarcade, "pac-man")` == `https://archive.org/details/internetarcade?query=pac-man`;
  - `url(webrcade, "zelda", Google)` == `UrlInput.toUrl("site:play.webrcade.com zelda", Google)`;
  - `lastGame("retrogames.cc", …)` returns the newest game whose page host is `www.retrogames.cc`;
  - `usesPlayer("www.x.com", setOf("x.com"))` is true.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Search across all sites; site cards show your last game and player support`.

### Task 5: Site health (item 18)

**Files:**
- Create: `data/SiteHealth.kt` (pure rules + checker)
- Modify: `ui/HomeScreen.kt` (check when home opens, at most every 6 h; Slow/Down pill on cards), `ui/SettingsScreen.kt` ("Check sites now")
- Test: `app/src/test/java/com/aiconsole/retrotv/SiteHealthTest.kt`

**Interfaces:**
- Produces: `enum class Health { Up, Slow, Down, Unknown }` and `object SiteHealth { fun classify(code: Int?, ms: Long): Health; fun due(lastCheck: Long, now: Long): Boolean; const val SLOW_MS = 4_000L; const val INTERVAL_MS = 6 * 3_600_000L }`. `code == null` (no connection or timeout) → Down; 200–399 → Up, or Slow when `ms >= SLOW_MS`; 401/403/429/503 → Up (the server answered, often a Cloudflare challenge); other 4xx/5xx → Down.
- Produces: `class SiteChecker(context)`: `val results: StateFlow<Map<String, Health>>` (keyed by host); `suspend fun checkAll(sites: List<Site>)` (GET with the WebView user agent, 8 s timeout, 4 at a time, read at most 1 byte of the body); results persisted in prefs `health`.

- [ ] **Step 1: Failing test** `SiteHealthTest`: `classify(200, 300)` Up, `classify(200, 5_000)` Slow, `classify(301, 100)` Up, `classify(403, 100)` Up, `classify(503, 100)` Up, `classify(404, 100)` Down, `classify(500, 100)` Down, `classify(null, 8_000)` Down; `due(0, INTERVAL_MS)` true, `due(1, 2)` false.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.** The card shows a pill only for Slow (amber, "Slow") and Down (red, "Down?").
- [ ] **Step 4: Verify on CI** and commit `Site health check: slow and down sites marked on the home screen`.

### Task 6: Phase 2 wrap-up

- [ ] Whole-branch review, CI green, fast-forward merge to `main`, push.
- [ ] Update `CLAUDE.md` (library, art, search, health) and hand the owner the APK with the on-TV checks.
