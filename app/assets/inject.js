/*
 * CleanTube - runs inside YouTube's TV page before any of YouTube's own code.
 *
 * How ad removal works (same idea uBlock Origin's "json-prune" rule uses):
 * YouTube sends the player a JSON description of each video. Ads are listed in
 * a few fields of that JSON (adPlacements, adSlots, playerAds). We wrap the
 * browser's JSON parser and empty those fields before YouTube's code sees them,
 * so the player has no ads to play. Ad tiles in the home/search feeds are
 * removed the same way.
 */
(function () {
  'use strict';
  if (window.__cleanTube) return;
  window.__cleanTube = true;

  var Native = window.CleanTubeNative || null;

  // ---------- 1. Strip ads from YouTube's data ----------

  // Feed items that are ads or premium upsells.
  var AD_ITEM_KEYS = ['adSlotRenderer', 'promoShelfRenderer'];

  function isAdItem(item) {
    if (!item || typeof item !== 'object') return false;
    for (var i = 0; i < AD_ITEM_KEYS.length; i++) {
      if (item[AD_ITEM_KEYS[i]]) return true;
    }
    // Shorts ads
    var reel = item.command && item.command.reelWatchEndpoint;
    if (reel && reel.adClientParams && reel.adClientParams.isAd) return true;
    return false;
  }

  function stripPlayerAds(o) {
    if (!o || typeof o !== 'object') return;
    if (o.adPlacements) o.adPlacements = [];
    if (o.adSlots) o.adSlots = [];
    // Remove the field entirely: that's exactly how a video with no ads looks
    // (and what uBlock Origin's rule does), so nothing downstream sees a new type.
    if ('playerAds' in o) delete o.playerAds;
  }

  // Walk the whole response once and drop ad items from any list.
  function walk(node, depth) {
    if (!node || typeof node !== 'object' || depth > 40) return;
    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i])) node.splice(i, 1);
        else walk(node[i], depth + 1);
      }
      return;
    }
    stripPlayerAds(node);
    for (var k in node) {
      if (Object.prototype.hasOwnProperty.call(node, k)) {
        var v = node[k];
        if (v && typeof v === 'object') walk(v, depth + 1);
      }
    }
  }

  var cleaned = new WeakSet();
  function clean(obj) {
    if (!obj || typeof obj !== 'object' || cleaned.has(obj)) return obj;
    try { walk(obj, 0); } catch (e) { /* never break YouTube */ }
    cleaned.add(obj);
    return obj;
  }

  // JSON.parse
  var origParse = JSON.parse;
  var patchedParse = function parse(text, reviver) {
    var r = origParse.apply(this, arguments);
    return (r && typeof r === 'object') ? clean(r) : r;
  };
  JSON.parse = patchedParse;

  // fetch(...).json()
  if (window.Response && Response.prototype.json) {
    var origJson = Response.prototype.json;
    Response.prototype.json = function () {
      return origJson.apply(this, arguments).then(clean);
    };
  }

  // XMLHttpRequest with responseType = 'json'
  try {
    var respDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');
    if (respDesc && respDesc.get) {
      Object.defineProperty(XMLHttpRequest.prototype, 'response', {
        configurable: true,
        enumerable: respDesc.enumerable,
        get: function () {
          var v = respDesc.get.call(this);
          return (this.responseType === 'json') ? clean(v) : v;
        }
      });
    }
  } catch (e) {}

  // Data the page assigns directly (e.g. `ytInitialPlayerResponse = {...}`) rather
  // than parsing: catch it on the way in.
  ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
    try {
      var current = clean(window[name]);
      Object.defineProperty(window, name, {
        configurable: true,
        enumerable: true,
        get: function () { return current; },
        set: function (v) { current = clean(v); }
      });
    } catch (e) {}
  });

  // Tell YouTube's servers this playback has no ads (TizenTube does the same
  // to avoid the player stalling while it waits for an ad that never comes).
  var origStringify = JSON.stringify;
  JSON.stringify = function (value) {
    try {
      var ctx = value && value.playbackContext && value.playbackContext.contentPlaybackContext;
      if (ctx && !ctx.isInlinePlaybackNoAd) {
        var copy = origParse(origStringify(value));
        copy.playbackContext.contentPlaybackContext.isInlinePlaybackNoAd = true;
        arguments[0] = copy;
      }
    } catch (e) {}
    return origStringify.apply(this, arguments);
  };

  // YouTube TV keeps its own references to JSON in window._yttv; point them at ours.
  function patchYttv() {
    try {
      var y = window._yttv;
      if (!y) return;
      for (var key in y) {
        var o = y[key];
        if (o && o.JSON && o.JSON.parse && o.JSON.parse !== patchedParse) {
          o.JSON.parse = patchedParse;
        }
      }
    } catch (e) {}
  }
  var yttvTimer = setInterval(patchYttv, 500);
  setTimeout(function () { clearInterval(yttvTimer); }, 60000);

  // ---------- 2. Look like a TV app, not a phone browser ----------
  try {
    var uaData = {
      brands: [{ brand: 'CleanTube', version: '27' }],
      mobile: false,
      platform: 'Starboard',
      getHighEntropyValues: function () {
        return Promise.resolve({
          brands: this.brands, mobile: false, platform: 'Starboard',
          platformVersion: '', architecture: 'arm', bitness: '64', model: '',
          fullVersionList: [{ brand: 'CleanTube', version: '27.0.0' }],
          uaFullVersion: '27.0.0', wow64: false
        });
      },
      toJSON: function () { return { brands: this.brands, mobile: false, platform: 'Starboard' }; }
    };
    Object.defineProperty(Navigator.prototype, 'userAgentData', {
      configurable: true, get: function () { return uaData; }
    });
  } catch (e) {}

  // No service worker, so every page load goes through the app (and this script).
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.register = function () {
        return Promise.reject(new Error('disabled'));
      };
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) { r.unregister(); });
      }).catch(function () {});
    }
  } catch (e) {}

  // ---------- 3. Small TV niceties ----------

  // Keep the screen on only while a video is actually playing.
  if (Native) {
    var setPlaying = function (on) { try { Native.setPlaying(on); } catch (e) {} };
    document.addEventListener('playing', function () { setPlaying(true); }, true);
    ['pause', 'ended', 'emptied', 'error'].forEach(function (t) {
      document.addEventListener(t, function () { setPlaying(false); }, true);
    });
  }

  // "Exit YouTube?" -> Yes closes the app.
  window.close = function () {
    try { if (Native) Native.exitApp(); } catch (e) {}
  };
})();
