/*
 * RetroTV's built-in player: EmulatorJS with the game RetroTV prepared (see GameFiles.kt).
 * Query: core, game, bios, parent, patch, state, name, key, dir (save folder), color, data (EmulatorJS folder),
 * legacy=1 (WebGL 1 cores), threads=1. The app drives it through window.RetroPlayer and
 * hears back through the RetroTVPlayer bridge.
 */
(function () {
  var q = new URLSearchParams(location.search);
  var P = window.RetroTVPlayer || {};
  function tell(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    try { if (typeof P[name] === 'function') P[name].apply(P, args); } catch (e) {}
  }

  var note = document.getElementById('note');
  var noteTimer = 0;
  function show(text) {
    note.textContent = text;
    note.hidden = false;
    clearTimeout(noteTimer);
    noteTimer = setTimeout(function () { note.hidden = true; }, 2500);
  }

  window.EJS_player = '#game';
  window.EJS_core = q.get('core');
  window.EJS_gameUrl = q.get('game');
  if (q.get('bios')) window.EJS_biosUrl = q.get('bios');
  if (q.get('parent')) window.EJS_gameParentUrl = q.get('parent');
  if (q.get('patch')) window.EJS_gamePatchUrl = q.get('patch');
  if (q.get('state')) window.EJS_loadStateURL = q.get('state');
  if (q.get('name')) window.EJS_gameName = q.get('name');
  window.EJS_pathtodata = q.get('data');
  window.EJS_startOnLoaded = true;
  window.EJS_color = q.get('color') || '#9c6bff';
  window.EJS_backgroundColor = '#000000';
  window.EJS_language = 'en-US';
  window.EJS_disableAutoLang = false;
  window.EJS_defaultOptions = { 'virtual-gamepad': 'disabled' };
  if (q.get('rewind') === '1') window.EJS_defaultOptions.rewindEnabled = 'enabled';
  if (q.get('shader')) window.EJS_defaultOptions.shader = q.get('shader');
  if (q.get('sharp') === '1') document.documentElement.classList.add('sharp');
  window.EJS_Buttons = { netplay: false };
  // The button layout for this game or its console (the app resolves game > console); EmulatorJS's own
  // saved layout for the game still wins over these defaults.
  try {
    var layout = typeof P.profile === 'function' ? P.profile() : '';
    if (layout) window.EJS_defaultControls = JSON.parse(layout);
  } catch (e) {}
  if (q.get('legacy') === '1') window.EJS_forceLegacyCores = true;
  window.EJS_threads = q.get('threads') === '1' && typeof SharedArrayBuffer === 'function' && self.crossOriginIsolated === true;
  window.EJS_ready = function () { tell('ready'); };
  if (q.get('vsync')) window.EJS_defaultOptions.vsync = q.get('vsync'); // 'enabled' | 'disabled' (frame sync)
  window.EJS_onGameStart = function () {
    window.__retrotvStarted = true;
    tell('started');
    startPerf();
    setTimeout(function () { window.RetroPlayer.migrate(); }, 1500);
  };

  // EmulatorJS shows errors in its own text box; pass them on so the app can offer a fallback.
  // It depends on EmulatorJS internals (failedToStart, textElem), so a 90 s watchdog backs it up:
  // no start, an unchanged status text (download percentages count) and no frames drawn is a failure.
  // A ROM can unpack for a long time with the same text, hence the long limit.
  tell('isolated', self.crossOriginIsolated === true);
  var STALL_MS = 90000;
  var began = Date.now(), lastText = '', lastChange = began, begun = false;
  function fail(msg) {
    if (begun) return;
    begun = true;
    clearInterval(watch);
    tell('failed', msg || 'The game failed to start');
  }
  var watch = setInterval(function () {
    if (begun) return;
    var e = window.EJS_emulator, now = Date.now();
    if (!e) {
      if (now - began > STALL_MS) fail('The emulator did not load in time. Check the internet connection.');
      return;
    }
    var text = '';
    try { text = (e.textElem && e.textElem.innerText) || ''; } catch (x) {}
    // Progress = new status text, or the core's game manager appearing (the ROM is loaded and running).
    var sig = text + '|' + (e.gameManager ? 1 : 0);
    if (sig !== lastText) { lastText = sig; lastChange = now; }
    if (e.failedToStart) {
      fail(text);
    } else if (e.started || window.__retrotvStarted) {
      begun = true;
      clearInterval(watch);
    } else if (now - lastChange > STALL_MS) {
      fail('The game did not start within 90 seconds' + (text ? ' (' + text + ')' : '') + '.');
    }
  }, 400);

  function start() {
    var s = document.createElement('script');
    s.src = window.EJS_pathtodata + 'loader.js';
    s.onerror = function () { fail('Could not download the emulator. Check the internet connection.'); };
    document.body.appendChild(s);
  }
  // Pre-flight: report what this WebView can do and let the app decide (RetroPlayer.go / halt) before
  // any emulator file is downloaded. Without the app's answer within 5 s the game starts anyway.
  function capabilities() {
    var gl2 = false, threads = false;
    try { gl2 = !!document.createElement('canvas').getContext('webgl2'); } catch (e) {}
    try { threads = typeof WebAssembly === 'object' && !!new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true }).buffer; } catch (e) {}
    return {
      webgl2: gl2,
      sab: typeof SharedArrayBuffer === 'function',
      isolated: self.crossOriginIsolated === true,
      threads: threads
    };
  }
  var gate = null;
  function preflight() {
    if (typeof P.caps !== 'function') { start(); return; }
    var fallback = setTimeout(function () { if (gate) gate(false); }, 5000);
    gate = function (legacy) {
      if (!gate) return;
      gate = null;
      clearTimeout(fallback);
      if (legacy) window.EJS_forceLegacyCores = true;
      start();
    };
    tell('caps', JSON.stringify(capabilities()));
  }
  // Wait briefly for the app's gamepad bridge (document-start script) so pads are seen at once.
  var tries = 0;
  (function wait() {
    if (window.__retrotvPad || tries++ > 15) preflight(); else setTimeout(wait, 100);
  })();

  // ---- Save slots: files in the app (RetroTVSaves bridge, read back from /save/<dir>/) ----
  // Saves used to live in this page's IndexedDB; migrate() moves those over once.

  var key = q.get('key') || (window.EJS_core + '|' + window.EJS_gameUrl);
  var dir = q.get('dir') || '';
  var SAVES = window.RetroTVSaves || null;
  var CHUNK = 384 * 1024; // bytes per bridge call (512 KB of base64)
  var MAX_STATE = 64 * 1024 * 1024;

  function b64(u8) {
    var s = '';
    for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  }
  function bytes(data) { return data instanceof Uint8Array ? data : new Uint8Array(data); }

  /** Sends one state to the app; returns '' when saved, else the reason. */
  function upload(slot, data, png) {
    if (!SAVES) return 'Saving isn\'t available';
    var u8 = bytes(data);
    if (u8.length > MAX_STATE) return 'This game\'s save is too large (' + Math.round(u8.length / 1048576) + ' MB)';
    var t = SAVES.open(key, slot, u8.length);
    if (!t) return 'Couldn\'t start the save';
    for (var i = 0; i < u8.length; i += CHUNK) {
      if (!SAVES.write(t, b64(u8.subarray(i, i + CHUNK)))) { SAVES.close(t, ''); return 'The TV is out of storage'; }
    }
    return SAVES.close(t, png ? b64(bytes(png)) : '') ? '' : 'The TV is out of storage';
  }
  function filled() {
    try { return JSON.parse(SAVES ? SAVES.slots(key) : '{}') || {}; } catch (e) { return {}; }
  }
  /** The slot thumbnail: EmulatorJS resolves a Promise of PNG bytes; give up after 2 s rather than hold up the save. */
  function screenshot(e) {
    return new Promise(function (resolve) {
      var done = false;
      function finish(p) { if (!done) { done = true; resolve(p && p.length ? p : null); } }
      setTimeout(function () { finish(null); }, 2000);
      try { Promise.resolve(e.gameManager.screenshot()).then(finish, function () { finish(null); }); } catch (x) { finish(null); }
    });
  }

  function db() {
    return new Promise(function (resolve, reject) {
      var r = indexedDB.open('retrotv', 1);
      r.onupgradeneeded = function () { r.result.createObjectStore('states'); };
      r.onsuccess = function () { resolve(r.result); };
      r.onerror = function () { reject(r.error); };
    });
  }
  function store(mode, fn) {
    return db().then(function (d) {
      return new Promise(function (resolve, reject) {
        var t = d.transaction('states', mode);
        var req = fn(t.objectStore('states'));
        t.oncomplete = function () { resolve(req && req.result); };
        t.onerror = function () { reject(t.error); };
      });
    });
  }
  /** Moves this game's IndexedDB saves into empty app slots; an entry is deleted only once its upload succeeded. */
  function migrate() {
    if (!SAVES || !window.indexedDB) return;
    var have = filled();
    store('readonly', function (s) { return s.getAllKeys(); }).then(function (keys) {
      (keys || []).forEach(function (k) {
        var p = String(k).split('#');
        var slot = p[p.length - 1];
        if (p.slice(0, -1).join('#') !== key || have[slot]) return;
        store('readonly', function (s) { return s.get(k); }).then(function (v) {
          if (v && v.data && upload(Number(slot), v.data, null) === '') store('readwrite', function (s) { return s.delete(k); });
        });
      });
    }).catch(function () {});
  }
  function emu() {
    var e = window.EJS_emulator;
    if (!e || !e.started || !e.gameManager) throw new Error('The game is still starting');
    return e;
  }

  // ---- Speed info: screen refreshes and emulated frames per second (a game should show ~60) ----

  // Once a second the app hears the emulated and screen frame rates (it decides about stutter, see StutterRules,
  // and keeps a line for "Report a problem"). Cheap: one counter per animation frame and one bridge call a second.
  var perfTimer = 0;
  function startPerf() {
    if (perfTimer || typeof P.perf !== 'function') return;
    var frames = 0, last = performance.now(), lastFrame = null;
    (function count() { frames++; requestAnimationFrame(count); })();
    perfTimer = setInterval(function () {
      var now = performance.now(), dt = (now - last) / 1000, e = window.EJS_emulator, emu = -1;
      try {
        var n = e.gameManager.getFrameNum();
        if (lastFrame !== null && dt > 0) emu = (n - lastFrame) / dt;
        lastFrame = n;
      } catch (x) { lastFrame = null; }
      var gl = e && e.supportsWebgl2 && e.webgl2Enabled ? 2 : 1;
      tell('perf', emu, dt > 0 ? frames / dt : 0, gl);
      frames = 0;
      last = now;
    }, 1000);
  }

  var statsEl = null, statsTimer = 0;
  function stats(on, info) {
    clearInterval(statsTimer);
    if (statsEl) { statsEl.remove(); statsEl = null; }
    if (!on) return;
    statsEl = document.createElement('div');
    statsEl.id = 'stats';
    statsEl.textContent = 'measuring…';
    document.body.appendChild(statsEl);
    var frames = 0, last = performance.now(), lastFrame = null;
    (function count() { if (!statsEl) return; frames++; requestAnimationFrame(count); })();
    statsTimer = setInterval(function () {
      var now = performance.now(), dt = (now - last) / 1000, e = window.EJS_emulator, game = '';
      try {
        var n = e.gameManager.getFrameNum();
        if (lastFrame !== null) game = ' · game ' + ((n - lastFrame) / dt).toFixed(1) + ' fps';
        lastFrame = n;
      } catch (x) {}
      var gl = e && e.supportsWebgl2 && e.webgl2Enabled ? 'WebGL 2' : 'WebGL 1';
      statsEl.textContent = 'screen ' + (frames / dt).toFixed(0) + ' fps' + game + ' · ' + gl + ' · ' + (info || '');
      frames = 0;
      last = now;
    }, 1000);
  }

  window.RetroPlayer = {
    save: function (slot) {
      var e, data;
      try { e = emu(); data = e.gameManager.getState(); } catch (x) { tell('saved', slot, false, String(x.message || x)); return; }
      screenshot(e).then(function (png) {
        var err = upload(slot, data, png);
        if (err) { tell('saved', slot, false, err); return; }
        show('Saved to slot ' + slot);
        tell('saved', slot, true, '');
      });
    },
    load: function (slot) {
      var e;
      try { e = emu(); } catch (x) { tell('loaded', slot, false, String(x.message)); return; }
      fetch('/save/' + dir + '/slot' + slot + '.state', { cache: 'no-store' })
        .then(function (r) { return r.ok ? r.arrayBuffer() : null; })
        .then(function (buf) {
          // A save too large to move out of IndexedDB (see migrate) still loads from there.
          if (buf && buf.byteLength) return buf;
          if (!window.indexedDB) return null;
          return store('readonly', function (s) { return s.get(key + '#' + slot); })
            .then(function (v) { return v && v.data ? bytes(v.data) : null; }, function () { return null; });
        })
        .then(function (buf) {
          if (!buf || !buf.byteLength) { tell('loaded', slot, false, 'Slot ' + slot + ' is empty'); return; }
          e.gameManager.loadState(new Uint8Array(buf));
          show('Loaded slot ' + slot);
          tell('loaded', slot, true, '');
        })
        .catch(function (x) { tell('loaded', slot, false, String(x && x.message || x)); });
    },
    slots: function () { tell('slots', JSON.stringify(filled())); },
    restart: function () { try { emu().gameManager.restart(); show('Restarted'); } catch (e) {} },
    pause: function (on) { try { var e = emu(); if (on) e.pause(); else e.play(); } catch (x) {} },
    fast: function (on) { try { var e = emu(); e.isFastForward = !!on; e.gameManager.toggleFastForward(on ? 1 : 0); } catch (x) {} },
    cheats: function (list) {
      try {
        var gm = emu().gameManager;
        gm.resetCheat();
        (list || []).forEach(function (c, i) { if (c.on) gm.setCheat(i, 1, c.code); });
      } catch (x) {}
    },
    filter: function (name) { try { emu().changeSettingOption('shader', name); } catch (x) {} },
    // Frame sync on/off ('enabled' | 'disabled'), applied live.
    vsync: function (v) { try { emu().changeSettingOption('vsync', v); } catch (x) {} },
    rewind: function (on) {
      try { var e = emu(); if (e.rewindEnabled) e.gameManager.functions.toggleRewind(on ? 1 : 0); } catch (x) {}
    },
    mute: function (on) { try { var e = emu(); e.muted = !!on; e.setVolume(on ? 0 : e.volume); } catch (x) {} },
    flush: function () { try { emu().gameManager.saveSaveFiles(); } catch (e) {} },
    menu: function () { try { emu().menu.open(true); } catch (e) {} },
    // EmulatorJS's own button-layout screen; it saves the layout per game.
    controls: function () { try { emu().controlMenu.style.display = ''; } catch (e) {} },
    // Reports the current button layout as JSON; purpose says what the app does with it ('game' or 'system').
    snapshot: function (purpose) {
      try { tell('snapshot', purpose, JSON.stringify(emu().controls)); } catch (e) { tell('snapshot', purpose, ''); }
    },
    hideMenu: function () { try { emu().menu.close(); } catch (e) {} },
    migrate: migrate,
    // Pre-flight answers: go(legacy) starts the emulator; halt() stops the page for good (the app shows why).
    go: function (legacy) { if (gate) gate(!!legacy); },
    halt: function () {
      gate = null;
      begun = true;
      clearInterval(watch);
    },
    stats: stats,
    note: show
  };
})();
