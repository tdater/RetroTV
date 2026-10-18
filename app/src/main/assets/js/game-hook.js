/*
 * RetroTV game hook, injected at document start in every frame.
 *
 * Sites that run games with EmulatorJS set window.EJS_core / EJS_gameUrl (and friends) and then
 * add EmulatorJS's loader.js. This hook watches for that, and when RetroTV's own player is on for
 * the site it hands the game to the app (RetroTVGame.launch) and stops the site's copy of the
 * emulator: the loader's read of EJS_gameUrl throws, so it never starts (and never downloads a
 * core or waits for an ad). The site's page is left as it was, so Back returns to it.
 */
(function () {
  if (window.__retrotvGameHook) return;
  window.__retrotvGameHook = true;
  var B = window.RetroTVGame;
  if (!B || location.hostname === 'appassets.androidplatform.net') return;

  var KEYS = ['core', 'gameUrl', 'biosUrl', 'gameParentUrl', 'gamePatchUrl', 'gameName', 'gameTitle', 'loadStateURL'];
  var values = {};
  var taken = false;   // this attempt was handed to RetroTV
  var passive = false; // RetroTV's player is off here: never interfere
  var loaderSeen = false;
  var LOADER = /(^|\/)(loader|emulator(\.min)?)\.js([?#]|$)/i;
  var START = /(^|\/)loader\.js([?#]|$)/i;
  var takenAt = 0;

  function hasLoader() {
    if (loaderSeen) return true;
    var s = document.getElementsByTagName('script');
    for (var i = 0; i < s.length; i++) if (LOADER.test(s[i].src || '')) return (loaderSeen = true);
    return false;
  }

  function stop() {
    var e = new Error('RetroTV is playing this game in its own player');
    e.name = 'RetroTVTakeover';
    throw e;
  }

  // Accessors for the EJS_ globals: remember the value, and stop the site's loader once taken.
  KEYS.forEach(function (k) {
    var name = 'EJS_' + k;
    var initial = window[name];
    if (initial !== undefined) values[k] = initial;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        enumerable: true,
        get: function () {
          if (k === 'gameUrl' && !passive) {
            if (taken) stop();
            if (hasLoader() && trigger('read')) stop();
          }
          return values[k];
        },
        set: function (v) { values[k] = v; }
      });
    } catch (e) {}
  });

  function abs(u) {
    if (typeof u !== 'string' || !u) return null;
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  function isBlob(v) {
    return (typeof Blob !== 'undefined' && v instanceof Blob) || (typeof v === 'string' && v.indexOf('blob:') === 0);
  }

  /** Sends a Blob / blob: URL to the app in chunks; resolves to its token. */
  function transfer(v, name) {
    var get = typeof v === 'string' ? fetch(v).then(function (r) { return r.blob(); }) : Promise.resolve(v);
    return get.then(function (blob) {
      var token = B.blobOpen(name || (blob.name) || 'game', blob.size);
      if (!token) throw new Error('refused');
      var CHUNK = 1024 * 1024, offset = 0;
      function next() {
        if (offset >= blob.size) { return B.blobClose(token) ? token : Promise.reject(new Error('incomplete')); }
        var part = blob.slice(offset, offset + CHUNK);
        offset += CHUNK;
        return new Promise(function (resolve, reject) {
          var r = new FileReader();
          r.onload = function () {
            var s = String(r.result);
            B.blobWrite(token, s.substring(s.indexOf(',') + 1));
            resolve();
          };
          r.onerror = function () { reject(r.error); };
          r.readAsDataURL(part);
        }).then(next);
      }
      return next();
    });
  }

  /** Decides on this attempt. Returns true when RetroTV took the game. */
  function trigger(via) {
    if (taken || passive) return taken;
    var core = values.core, game = values.gameUrl;
    if (!core || !game) return false;
    var on = false;
    try { on = !!B.enabled(String(core)); } catch (e) {}
    if (!on) { passive = true; return false; }
    taken = true;
    takenAt = Date.now();
    var info = {
      via: via,
      core: String(core),
      gameName: values.gameName ? String(values.gameName) : '',
      title: values.gameTitle ? String(values.gameTitle) : (document.title || ''),
      page: location.href,
      top: window.top === window
    };
    var files = { game: game, bios: values.biosUrl, parent: values.gameParentUrl, patch: values.gamePatchUrl, state: values.loadStateURL };
    var jobs = Object.keys(files).map(function (k) {
      var v = files[k];
      if (!v) return Promise.resolve();
      if (isBlob(v)) {
        var n = (typeof v !== 'string' && v.name) || (k === 'game' && info.gameName) || k;
        return transfer(v, n).then(function (t) { info[k] = 'blob:' + t; });
      }
      info[k] = abs(String(v));
      return Promise.resolve();
    });
    Promise.all(jobs).then(function () {
      if (!info.game) throw new Error('no game file');
      B.launch(JSON.stringify(info));
    }).catch(function (e) {
      try { B.failed(String(e && e.message || e)); } catch (x) {}
    });
    return true;
  }

  // A loader added to the page (most sites add it when Play is pressed): decide now, before it runs.
  try {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.tagName === 'SCRIPT' && LOADER.test(n.src || '')) {
            loaderSeen = true;
            // Play pressed again on a single-page site (back from the player): a new attempt.
            if (taken && START.test(n.src) && Date.now() - takenAt > 3000) taken = false;
            trigger('loader');
          }
        }
      }
    }).observe(document, { childList: true, subtree: true });
  } catch (e) {}
})();
