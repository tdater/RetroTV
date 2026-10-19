# RetroTV Phase 3: Player Extras Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Items 8–11 from the approved list: choosing which controller is which player (plus a shortcut to per-game button layouts), CRT and sharp-pixel display filters, rewind and quick save/load hotkeys, and cheats. Also fixes Phase 1's missing save thumbnails.

**Architecture:** The rules are pure Kotlin objects (`PadSlots`, `Hotkeys`, `CheatRules`, `Filters`). `PlayerActivity` applies them, and `player.js` talks to EmulatorJS 4.2.3 through `window.RetroPlayer`. Per-game choices (filter, cheats) are stored natively in prefs keyed by `GameRequest.key`, so clearing site data can't lose them.

**Tech Stack:** Kotlin, Compose, EmulatorJS 4.2.3 (`assets/player/`), JUnit 4.

**Spec:** items 8–11 of the 2026-10-19 list; roadmap `docs/superpowers/plans/2026-10-19-retrotv-improvements.md`.

## Global Constraints

The roadmap's constraints apply. EmulatorJS facts these tasks rely on (from `cdn.emulatorjs.org/4.2.3/data/src`, read 2026-10-19):
- **Filters:** setting `shader`, set with `EJS_defaultOptions.shader` at start or live with `emu.changeSettingOption('shader', name)`. Built-in names include `crt-easymode.glslp`, `crt-geom.glslp`, `crt-lottes`, `crt-zfast`, `crt-aperture.glslp`, and `disabled`.
- **Rewind:** works only when `EJS_defaultOptions.rewindEnabled = 'enabled'` at start; then `gameManager.functions.toggleRewind(1|0)`.
- **Cheats:** `gameManager.resetCheat()` and `gameManager.setCheat(index, 1|0, code)`.
- **Screenshot:** `gameManager.screenshot()` returns a **Promise** of PNG bytes.
- **Button layouts:** EmulatorJS saves them per game in localStorage. `emu.controlMenu.style.display = ''` opens its layout screen.

## Review Focus

- **Hotkeys mustn't steal game input.** A press of L1, R1, L2 or R2 without Select held goes to the game, and Select tapped alone still reaches the game. Owner: Task 2.
- **Bulk-pasted cheats:** blank lines, comments (`#`), Windows line endings, `Name: CODE`, `CODE Name` and bare codes all parse; lines that aren't codes are skipped, not turned into broken cheats. Owner: Task 4.
- **Swapping players while a button is held** doesn't leave that button stuck down on either player. Owner: Task 1.
- **Rewind turned on for a weak TV:** it's off by default, and its setting says it uses more memory. Owner: Task 3.
- **A save made on a game that never produces a screenshot** still saves, just without a thumbnail. Owner: Task 3.

---

### Task 1: Players and button layout (item 8)

**Files:**
- Create: `browser/PadSlots.kt` (pure)
- Modify: `browser/VirtualPads.kt` (`players()`, `claim(slot)`: the next controller to press Start becomes that player)
- Modify: `player/PlayerUi.kt` + `player/PlayerActivity.kt` (a "Players" panel, and a "Button layout" tile that opens EmulatorJS's layout screen via `RetroPlayer.controls()`)
- Modify: `assets/player/player.js` (`controls()`)
- Test: `app/src/test/java/com/aiconsole/retrotv/PadSlotsTest.kt`

**Interfaces:**
- Produces: `object PadSlots { fun claim(slots: Map<Int, Int>, device: Int, slot: Int): Map<Int, Int> }`, where the map is deviceId → slot (0–3). The device takes `slot`; whoever held it moves to the device's old slot, or is unassigned when the device had none.
- Produces: `VirtualPads.players(): List<String?>` (4 names, null = empty), `VirtualPads.claim(slot: Int)`, which arms "next Start press" (`onKey` with BUTTON_START while armed moves that device via `PadSlots.claim`, releases every button on both pads, consumes the press and disarms), and `VirtualPads.claiming: Int?`.

- [ ] **Step 1: Failing test** `PadSlotsTest`:
  - `claim({10:0, 11:1}, 11, 0)` == `{11:0, 10:1}` (swap);
  - `claim({10:0}, 12, 0)` == `{12:0}` (the newcomer takes it, the old holder is unassigned);
  - `claim({10:0}, 10, 2)` == `{10:2}` (move to an empty slot);
  - `claim(m, d, 7)` == `m` (invalid slot).
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.** The Players panel lists Player 1–4 with controller names. OK on a row → `claim(n)` with the toast "Press Start on the controller for player n".
- [ ] **Step 4: Verify on CI** and commit `Player menu: choose which controller is which player; button layout shortcut`.

### Task 2: Hotkeys for quick save, quick load, rewind and fast-forward (item 10)

**Files:**
- Create: `player/Hotkeys.kt` (pure)
- Modify: `player/PlayerActivity.kt` (key handling; `selectHeld` already exists for Select+Start)
- Test: `app/src/test/java/com/aiconsole/retrotv/HotkeysTest.kt`

**Interfaces:**
- Produces: `enum class HotkeyAction { QuickSave, QuickLoad, RewindOn, RewindOff, FastForward }` and `object Hotkeys { fun action(selectHeld: Boolean, keyCode: Int, down: Boolean): HotkeyAction? }`:
  - with Select held: L1 down → QuickSave; R1 down → QuickLoad; L2 down → RewindOn; L2 up → RewindOff; R2 down → FastForward;
  - otherwise null.
  - Remote, whether or not a controller is connected: `KEYCODE_MEDIA_REWIND` held → RewindOn/RewindOff only when rewind is enabled (the caller checks; `Hotkeys` maps it unconditionally).
- Quick save/load use the current slot and show the normal toasts.

- [ ] **Step 1: Failing test** `HotkeysTest`: every mapping above; `action(false, L1, true)` null; `action(true, BUTTON_A, true)` null; `action(true, L1, false)` null.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.** The menu's help line lists the hotkeys.
- [ ] **Step 4: Verify on CI** and commit `Hotkeys: Select+L1/R1 quick save/load, Select+L2 rewind, Select+R2 fast-forward`.

### Task 3: Display filters, rewind setting and save thumbnails (items 9, 10)

**Files:**
- Create: `player/Filters.kt` (pure: the filter list)
- Modify: `data/Settings.kt` (`filter: String = "disabled"`, `sharpPixels: Boolean = false`, `rewind: Boolean = false`)
- Modify: `player/PlayerActivity.kt` (query `shader`, `sharp`, `rewind`; per-game filter in prefs `game_prefs` as `filter_<key>`), `player/PlayerUi.kt` ("Filter: <name>" tile cycling live), `assets/player/player.js` (defaultOptions; `filter(name)`; `.sharp` class; await the screenshot Promise), `assets/player/player.css` (`.sharp canvas { image-rendering: pixelated; }`), `ui/SettingsScreen.kt` (Games: default filter, sharp pixels, rewind)
- Test: `app/src/test/java/com/aiconsole/retrotv/FiltersTest.kt`

**Interfaces:**
- Produces: `object Filters { val ALL: List<Pair<String, String>>; fun label(id: String): String; fun next(id: String): String }`, where ALL = `disabled` "Off", `crt-easymode.glslp` "CRT", `crt-geom.glslp` "CRT curved", `crt-lottes` "CRT bright", `crt-zfast` "CRT light (fast)", `crt-aperture.glslp` "Aperture grille". `next` wraps; an unknown id → "Off" label and `next` → the first CRT.

- [ ] **Step 1: Failing test** `FiltersTest`: `label("crt-geom.glslp")` == "CRT curved"; `next("disabled")` == `crt-easymode.glslp`; `next(last)` == `disabled`; `label("nope")` == "Off".
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.** `save()` in `player.js` does `Promise.resolve(gameManager.screenshot()).catch(()=>null)` with a 2 s timeout before `upload`.
- [ ] **Step 4: Verify on CI** and commit `Display filters (CRT, sharp pixels), optional rewind, save thumbnails fixed`.

### Task 4: Cheats (item 11)

**Files:**
- Create: `player/CheatRules.kt` (pure: `Cheat`, parse, encode/decode)
- Create: `player/CheatsPanel.kt` (Compose panel: list with on/off toggles, Add, Paste a list, Delete)
- Modify: `player/PlayerActivity.kt` + `player/PlayerUi.kt` ("Cheats (n on)" tile), `assets/player/player.js` (`cheats(json)`: `resetCheat()` then `setCheat(i, 1, code)` for each enabled one; applied after start and on every change)
- Test: `app/src/test/java/com/aiconsole/retrotv/CheatRulesTest.kt`

**Interfaces:**
- Produces: `data class Cheat(val name: String, val code: String, val on: Boolean = false)` and `object CheatRules { fun parse(text: String): List<Cheat>; fun isCode(s: String): Boolean; fun encode(list: List<Cheat>): String; fun decode(s: String): List<Cheat> }`:
  - `isCode`: after trimming, 4–200 chars of `[0-9A-Za-z:+\-. ]` that either contain a digit or are a Game Genie letter code (6 or 8 of `APZLGITYEOXUKSVN`, any case);
  - `parse`: lines separated by `\n` or `\r\n`; `#` lines and blanks skipped; `Name: CODE` / `Name = CODE` (separator followed by a space) / tab-separated → name, code; `CODE Name` when the first token is a code; a bare code gets the name "Cheat n"; lines with no code are skipped;
  - `encode`/`decode`: a line-based `name\tcode\t1|0` format, so no `org.json` is needed and it's testable.
- Stored per game in prefs `game_prefs` as `cheats_<key>`.

- [ ] **Step 1: Failing test** `CheatRulesTest`:
  - `parse("Infinite lives: 00C-5D2-6FB\r\n# comment\n\nSXIOPO Moon jump\nAPZLGG\nnot a code")` == `[Cheat("Infinite lives","00C-5D2-6FB"), Cheat("Moon jump","SXIOPO"), Cheat("Cheat 3","APZLGG")]`;
  - `isCode("hello")` false; `isCode("8100-1234")` true;
  - `decode(encode(list))` == `list`, including names with `:` and `on = true`.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Cheats: per-game list, paste in bulk, toggle while playing`.

### Task 5: Phase 3 wrap-up

- [ ] Whole-branch review, CI green, fast-forward merge to `main`, push; update `CLAUDE.md`; APK with the on-TV checks.
