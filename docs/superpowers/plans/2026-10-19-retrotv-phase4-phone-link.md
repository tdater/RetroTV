# RetroTV Phase 4: Phone Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Items 12–14 from the approved list: the phone as a controller, sending ROMs from the phone, and save sync (TV to TV over the home Wi-Fi, plus backup download and restore from the phone).

**Architecture:** `LinkServer` is a small HTTP and WebSocket server on the TV's Wi-Fi address. It runs only while the Phone link or Sync saves screen is open, stops after 30 idle minutes, and only answers requests carrying a random 16-byte token (in the QR code link) or a 6-digit sync code shown on screen. The phone page is a static asset (`assets/phone/index.html`). Phone input goes to whichever `VirtualPads` is active (`PhonePads`) as virtual controllers. TV-to-TV discovery uses Android NSD (`_retrotv._tcp`). The protocol pieces are pure Kotlin and unit-tested.

**Tech Stack:** Kotlin, `ServerSocket`, `NsdManager`, Compose, plain HTML/JS on the phone, JUnit 4. No new dependencies.

**Spec:** items 12–14 of the 2026-10-19 list (save sync revised to TV-to-TV and phone, no Google Drive); roadmap `docs/superpowers/plans/2026-10-19-retrotv-improvements.md`.

## Global Constraints

- The roadmap's constraints apply.
- **The server is for the local network only.** It binds to the private Wi-Fi address picked by `ReportShareRules.pickAddress` and refuses to start without one.
- **Every route needs the token or the sync code.** A wrong one gets 404, not 401, so the server reveals nothing.
- Uploads: at most 700 MB (`GameFiles.MAX_BLOB`), streamed to disk as `.part` then renamed, and only ROM types (`Systems.isRom`) or `.zip` backups.

## Review Focus

- **A phone that disconnects mid-press** (Wi-Fi drop, screen lock): its buttons are released and its pad removed, so nothing stays held in the game. Owner: Task 3.
- **Two phones at once:** each becomes its own player, up to the 4-pad limit; a fifth is told the pads are full. Owner: Task 3.
- **An interrupted upload** leaves no half-written file in the game folder. Owner: Task 4.
- **Sync with clock skew or identical saves:** same size and time within 2 s counts as identical, so nothing is copied; "newer" uses the save time; no file is ever deleted by sync. Owner: Task 5.
- **Leaving the screen** stops the server and removes the NSD registration, so nothing keeps listening. Owner: Task 2.

---

### Task 1: Protocol pieces (pure)

**Files:**
- Create: `link/LinkProtocol.kt`
- Test: `app/src/test/java/com/aiconsole/retrotv/LinkProtocolTest.kt`

**Interfaces:**
- Produces: `data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)` (header names lower-cased) and `object LinkProtocol`:
  - `fun parseRequestHead(lines: List<String>): HttpRequest?` (null when the request line is malformed);
  - `fun wsAccept(key: String): String` (RFC 6455: base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
  - `fun wsTextFrame(text: String): ByteArray` (unmasked server frame; payload sizes up to 65535);
  - `fun wsDecode(bytes: ByteArray): WsFrame?`, where `data class WsFrame(val opcode: Int, val payload: ByteArray, val length: Int)` and `length` is the total bytes consumed (null when incomplete; client masks applied);
  - `fun authorized(req: HttpRequest, token: String, code: String): Boolean` (path segment 1 == token, or header `x-retrotv-code` == code with code non-empty).

- [ ] **Step 1: Failing test** `LinkProtocolTest`:
  - `wsAccept("dGhlIHNhbXBsZSBub25jZQ==")` == `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`;
  - `parseRequestHead(listOf("GET /abc/pad?x=1 HTTP/1.1", "Upgrade: websocket"))` gives method GET, path `/abc/pad`, query `{x=1}`, header `upgrade`;
  - a masked client frame for "hi" (`81 82 01 02 03 04` + masked bytes) decodes to opcode 1, "hi", length 8; a truncated one gives null;
  - `wsTextFrame("hi")` == `81 02 'h' 'i'`; a 200-byte text uses the 126 extended length;
  - `authorized` is true for `/TOKEN/x` and for header code `123456`, and false for `/other/x` and an empty code.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Phone link protocol: HTTP head parsing, WebSocket handshake and frames`.

### Task 2: Link server and the Phone link screen (item 12)

**Files:**
- Create: `link/LinkServer.kt` (accept loop, route table, WebSocket upgrade, 30-min idle stop), `ui/PhoneLinkScreen.kt` (QR + link, status, received files), `assets/phone/index.html` (tabs: Controller, Send a game, Saves)
- Modify: `RetroApp.kt` (`val link by lazy { LinkServer(this) }`), `ui/MainActivity.kt` (`Screen.Phone`), `ui/HomeScreen.kt` (header "Phone link")

**Interfaces:**
- Produces: `class LinkServer(context)`:
  - `suspend fun start(): String?` (the phone URL `http://<ip>:<port>/<token>/`, or null without Wi-Fi);
  - `fun stop()`; `val running: StateFlow<Boolean>`;
  - `val syncCode: String` (6 digits, new each start);
  - `fun route(method: String, prefix: String, handler: (HttpRequest, Exchange) -> Unit)`, where `Exchange` writes a response (`text`, `json`, `file`, `stream`) or reads the body (`body(): InputStream`, `contentLength: Long`);
  - `fun onSocket(prefix: String, handler: (LinkSocket) -> Unit)`, with `LinkSocket.send(text)`, `onMessage`, `onClose`.
- `GET /<token>/` serves `assets/phone/index.html`.

- [ ] **Step 1: Build.** Lint, assemble and Task 1's tests on CI. Leaving the screen stops the server (a `DisposableEffect`).
- [ ] **Step 2: Commit** `Phone link: local server and QR screen`.

### Task 3: Phone as a controller (item 12)

**Files:**
- Create: `link/PhonePads.kt`, `link/PhoneInput.kt` (pure)
- Modify: `browser/VirtualPads.kt` (`virtualKey(id: Int, name: String, button: Int, value: Float)`, `virtualAxes(id: Int, x: Float, y: Float)`, `forgetVirtual(id: Int)`), `browser/BrowserActivity.kt` + `player/PlayerActivity.kt` (set `PhonePads.target` in `onResume`, clear it in `onPause`; `PhonePads.onMenu` opens the menu), `assets/phone/index.html` (touch pad: D-pad, A/B/X/Y, L/R, Select/Start, Menu; multi-touch; vibrates on press)
- Test: `app/src/test/java/com/aiconsole/retrotv/PhoneInputTest.kt`

**Interfaces:**
- Produces: `sealed interface PhoneMsg { data class Button(val index: Int, val down: Boolean); data class Stick(val x: Float, val y: Float); data object Menu; data class Hello(val name: String) }` and `object PhoneInput { fun parse(text: String): PhoneMsg? }`:
  - `"b,0,1"` → Button(0, true); `"b,16,0"` → Button(16, false);
  - `"a,-0.5,1"` → Stick(-0.5, 1.0), clamped to −1..1;
  - `"m"` → Menu; `"h,Pixel 8"` → Hello("Pixel 8"), name capped at 24 chars;
  - `"b,17,1"` (out of range) and garbage → null.
- Virtual device ids are negative (−100 − connection number); a closed socket calls `forgetVirtual`, which releases the pad.

- [ ] **Step 1: Failing test** `PhoneInputTest` with the cases above.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Phone as a controller over the home Wi-Fi`.

### Task 4: Send a ROM from the phone (item 13)

**Files:**
- Create: `link/UploadRules.kt` (pure)
- Modify: `link/LinkServer.kt` (`POST /<token>/upload?name=` streams the body), `player/GameFiles.kt` (`fun receiveRom(input: InputStream, name: String, size: Long): String`, returning the `file:` value), `ui/PhoneLinkScreen.kt` ("Received" list with Play, using the existing `SystemPicker` when the type is ambiguous), `assets/phone/index.html` (file picker with a progress bar, via `fetch` and `XMLHttpRequest` upload progress)
- Test: `app/src/test/java/com/aiconsole/retrotv/UploadRulesTest.kt`

**Interfaces:**
- Produces: `object UploadRules { fun check(name: String, size: Long): String?; fun safeName(name: String): String }`. `check` returns null when OK, else the reason: "Empty file", "Too large (max 700 MB)", "Not a game file". `safeName` drops paths and `..`, keeps the extension, max 120 chars, matching `GameFiles.safeName`.

- [ ] **Step 1: Failing test** `UploadRulesTest`:
  - `check("mario.nes", 40_000)` null; `check("x.exe", 10)` "Not a game file";
  - `check("big.iso", 800_000_000)` "Too large (max 700 MB)"; `check("a.zip", 0)` "Empty file";
  - `safeName("../../etc/x.sfc")` == `"x.sfc"`.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Send a game from the phone`.

### Task 5: Save sync, TV to TV and phone (item 14)

**Files:**
- Create: `link/SyncRules.kt` (pure), `link/SaveSync.kt` (NSD register/discover, manifest exchange, pull/push), `ui/SyncScreen.kt`
- Modify: `link/LinkServer.kt` routes:
  - `GET /saves/manifest`, `GET /saves/file?p=`, `POST /saves/file?p=&t=` (token or code);
  - `GET /<token>/backup.zip` (streams `BackupArchive` with `Backup.export`);
  - `POST /<token>/restore` (`BackupArchive.read`).
- Modify: `ui/SettingsScreen.kt` ("Sync saves with another TV"), `assets/phone/index.html` (Saves tab: download backup, restore backup)
- Test: `app/src/test/java/com/aiconsole/retrotv/SyncRulesTest.kt`

**Interfaces:**
- Produces: `data class SaveFileInfo(val path: String, val modified: Long, val size: Long)` (path relative to the saves root, e.g. `ab12…/slot1.state`) and `object SyncRules`:
  - `fun manifest(lines: String): List<SaveFileInfo>` and `fun encode(list: List<SaveFileInfo>): String` (`path\tmodified\tsize` lines);
  - `fun plan(local: List<SaveFileInfo>, remote: List<SaveFileInfo>): Pair<List<String>, List<String>>` = (paths to pull, paths to push). A file only on one side goes to the other. On both sides, the newer one wins unless identical (same size and within 2 s). Paths that aren't `<24-hex>/slot<1-4>.(state|png)` or `<24-hex>/game.txt` are ignored.
- `SaveSync`: registers `_retrotv._tcp` with the TV name while the screen is open, lists other RetroTVs, and on "Sync with <TV>" asks for that TV's 6-digit code, then pulls and pushes per `plan`. Received files are written `.part` then renamed, keeping the sender's modified time.

- [ ] **Step 1: Failing test** `SyncRulesTest`:
  - local-only → push; remote-only → pull;
  - remote newer by 1 h → pull; local newer → push;
  - same size within 1.5 s → neither;
  - `../x` and `abc/slot9.state` are ignored;
  - `manifest(encode(list))` == list.
- [ ] **Step 2: Run on CI**; expect it to fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify on CI** and commit `Save sync between RetroTVs and backup to the phone`.

### Task 6: Phase 4 wrap-up

- [ ] Whole-branch review, CI green, merge, `CLAUDE.md`, APK with the on-TV checks (they need a phone and, for sync, a second TV or the same APK on an Android phone).
