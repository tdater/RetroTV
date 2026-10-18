/*
 * RetroTV's built-in player: EmulatorJS with the game RetroTV prepared (see GameFiles.kt).
 * Query: core, game, bios, parent, patch, state, name, key, color, data (EmulatorJS folder),
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
  window.EJS_Buttons = { netplay: false };
  if (q.get('legacy') === '1') window.EJS_forceLegacyCores = true;
  window.EJS_threads = q.get('threads') === '1' && typeof SharedArrayBuffer === 'function' && self.crossOriginIsolated === true;
  window.EJS_ready = function () { tell('ready'); };
  window.EJS_onGameStart = function () { tell('started'); };

  // EmulatorJS shows errors in its own text box; pass them on so the app can offer a fallback.
  var watch = setInterval(function () {
    var e = window.EJS_emulator;
    if (!e) return;
    if (e.failedToStart) {
      clearInterval(watch);
      var msg = '';
      try { msg = (e.textElem && e.textElem.innerText) || ''; } catch (x) {}
      tell('failed', msg || 'The game failed to start');
    } else if (e.started) {
      clearInterval(watch);
    }
  }, 400);

  function start() {
    var s = document.createElement('script');
    s.src = window.EJS_pathtodata + 'loader.js';
    s.onerror = function () { tell('failed', 'Could not download the emulator. Check the internet connection.'); };
    document.body.appendChild(s);
  }
  // Wait briefly for the app's gamepad bridge (document-start script) so pads are seen at once.
  var tries = 0;
  (function wait() {
    if (window.__retrotvPad || tries++ > 15) start(); else setTimeout(wait, 100);
  })();

  // ---- Save slots, kept in this page's IndexedDB ----

  var key = q.get('key') || (window.EJS_core + '|' + window.EJS_gameUrl);
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
  function emu() {
    var e = window.EJS_emulator;
    if (!e || !e.started || !e.gameManager) throw new Error('The game is still starting');
    return e;
  }

  window.RetroPlayer = {
    save: function (slot) {
      var data;
      try { data = emu().gameManager.getState(); } catch (e) { tell('saved', slot, false, String(e.message || e)); return; }
      store('readwrite', function (s) { return s.put({ data: data, at: Date.now() }, key + '#' + slot); })
        .then(function () { show('Saved to slot ' + slot); tell('saved', slot, true, ''); })
        .catch(function (e) { tell('saved', slot, false, String(e && e.message || e)); });
    },
    load: function (slot) {
      var e;
      try { e = emu(); } catch (x) { tell('loaded', slot, false, String(x.message)); return; }
      store('readonly', function (s) { return s.get(key + '#' + slot); })
        .then(function (v) {
          if (!v || !v.data) { tell('loaded', slot, false, 'Slot ' + slot + ' is empty'); return; }
          e.gameManager.loadState(new Uint8Array(v.data));
          show('Loaded slot ' + slot);
          tell('loaded', slot, true, '');
        })
        .catch(function (x) { tell('loaded', slot, false, String(x && x.message || x)); });
    },
    slots: function () {
      store('readonly', function (s) { return s.getAllKeys(); }).then(function (keys) {
        var out = {};
        (keys || []).forEach(function (k) {
          var p = String(k).split('#');
          if (p.slice(0, -1).join('#') === key) out[p[p.length - 1]] = true;
        });
        tell('slots', JSON.stringify(out));
      }).catch(function () { tell('slots', '{}'); });
    },
    restart: function () { try { emu().gameManager.restart(); show('Restarted'); } catch (e) {} },
    pause: function (on) { try { var e = emu(); if (on) e.pause(); else e.play(); } catch (x) {} },
    fast: function (on) { try { var e = emu(); e.isFastForward = !!on; e.gameManager.toggleFastForward(on ? 1 : 0); } catch (x) {} },
    mute: function (on) { try { var e = emu(); e.muted = !!on; e.setVolume(on ? 0 : e.volume); } catch (x) {} },
    flush: function () { try { emu().gameManager.saveSaveFiles(); } catch (e) {} },
    menu: function () { try { emu().menu.open(true); } catch (e) {} },
    hideMenu: function () { try { emu().menu.close(); } catch (e) {} },
    note: show
  };
})();
