# RetroTV Phase 5: Big-Screen UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Items 15, 17 and 19 from the approved list: an attract mode, a first-run setup, and big-screen readability.

**Architecture:** Pure rule objects (`AttractRules`, `ButtonNames`, `Readability`) with thin Compose UI. `ArtStore` gains a second art kind (libretro `Named_Snaps`, in-game screenshots) for the attract mode. The readability settings feed `RetroTheme`, so every screen follows them.

**Tech Stack:** Kotlin, Compose, JUnit 4.

**Spec:** items 15, 17, 19 of the 2026-10-19 list (attract mode uses stills: no source of gameplay video); roadmap plan.

## Global Constraints

The roadmap's constraints apply. Attract mode never starts while a dialog or menu is open, and any key ends it without that key doing anything else.

## Review Focus

- **The first key that ends the attract mode must not also open a site.** It's swallowed. Owner: Task 1.
- **An empty library:** the attract mode shows the built-in sites' cards instead of nothing. Owner: Task 1.
- **First-run setup on a TV with no controller:** every step can be completed or skipped with the remote. Owner: Task 2.
- **Extra-large text** mustn't cut off the home header buttons or site names (they wrap or ellipsize). Owner: Task 3.
- **Upgrading users** don't get the first-run setup; it's offered once from Settings instead. Owner: Task 2.

---

### Task 1: Attract mode (item 15)

**Files:** Create `ui/AttractMode.kt`, `ui/AttractRules.kt` (pure). Modify `player/ArtStore.kt` (`art(core, title, kind: ArtKind = ArtKind.Box)`, `enum class ArtKind(folder: String) { Box("Named_Boxarts"), Snap("Named_Snaps") }`), `ui/ArtImage.kt` (`kind` parameter), `ui/HomeScreen.kt` (idle timer; any key → resets), `data/Settings.kt` (`attractAfterMin: Int = 2`, 0 = off), `ui/SettingsScreen.kt`. Test: `AttractRulesTest.kt`.

**Interfaces:** `object AttractRules { fun due(lastInputMs: Long, nowMs: Long, afterMin: Int): Boolean; fun slides(games: List<RecentGame>, sites: List<Site>, max: Int = 30): List<Slide> }`, where `sealed interface Slide { data class Game(val game: RecentGame); data class SiteCard(val site: Site) }`. `due` is false when `afterMin == 0`. `slides` puts favourites first, then by play time, at most `max`; it falls back to the sites when there are no games. Each slide shows for 8 s: the box art plus a screenshot, title, console and play time, with a slow pan.

- [ ] **Step 1: Failing test:** `due(0, 119_999, 2)` false; `due(0, 120_000, 2)` true; `due(0, 10^9, 0)` false. `slides(empty, sites)` is all `SiteCard`s; `slides(games, sites)` has the favourite first and no sites.
- [ ] **Step 2: Watch it fail on CI. Step 3: Implement. Step 4: Verify on CI** and commit `Attract mode: idle slideshow of your games' art`.

### Task 2: First-run setup (item 17)

**Files:** Create `ui/SetupScreen.kt`, `ui/ButtonNames.kt` (pure). Modify `data/Settings.kt` (`setupDone: Boolean`, set to true for existing installs with any favourite, history or library entry), `ui/MainActivity.kt` (`Screen.Setup`; shown first when `!setupDone`), `ui/SettingsScreen.kt` ("Run the setup again"). Test: `ButtonNamesTest.kt`.

**Interfaces:** `object ButtonNames { fun label(keyCode: Int): String? }`: BUTTON_A → "A", B, X, Y, L1, R1, L2, R2, SELECT → "Select", START → "Start", D-pad → "▲▼◀▶", THUMBL/THUMBR → "L3"/"R3"; others null. The setup steps are:
1. Welcome: press any button (detects a controller or the remote);
2. Controller test: the pressed buttons light up, with "Looks good" or "Skip";
3. Favourite sites (toggle chips);
4. Remote tour (pointer, hold OK = menu, Back, CH±, Play/Pause);
5. Done.

- [ ] **Step 1: Failing test** for every mapping above, plus `label(KEYCODE_VOLUME_UP)` null.
- [ ] **Step 2: Watch it fail on CI. Step 3: Implement. Step 4: Verify on CI** and commit `First-run setup: controller test, favourites, remote tour`.

### Task 3: Big-screen readability (item 19)

**Files:** Create `ui/Readability.kt` (pure). Modify `data/Settings.kt` (`textSize: Int = 0` with 0/1/2 = Normal/Large/Extra large, `boldFocus: Boolean = false`), `ui/Theme.kt` / `RetroTheme` (`LocalDensity` fontScale × `Readability.fontScale`), `ui/Components.kt` (`TvClickable` focus border `Readability.focusBorder(boldFocus)` dp; `LocalBoldFocus`), `ui/SettingsScreen.kt` (Look section). Test: `ReadabilityTest.kt`.

**Interfaces:** `object Readability { fun fontScale(level: Int): Float; fun focusBorder(bold: Boolean): Int; val LABELS: List<String> }`: 0 → 1.0, 1 → 1.15, 2 → 1.3, out of range → 1.0; focusBorder false → 3, true → 6; LABELS = Normal, Large, Extra large.

- [ ] **Step 1: Failing test** with those values.
- [ ] **Step 2: Watch it fail on CI. Step 3: Implement. Step 4: Verify on CI** and commit `Readability: larger text and bold focus options`.

### Task 4: Phase 5 wrap-up

- [ ] Review, CI, merge, `CLAUDE.md`, the roadmap marked complete, APK.
