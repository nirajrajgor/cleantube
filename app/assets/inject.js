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

  // YouTube's TV player tests the format check with impossible values (width=99999,
  // framerate=9999, eotf=catavision, ...). A real TV app says no to those. The web view
  // ignores the extra values and says yes to everything, so YouTube stops trusting it
  // and hides 60fps and most HD formats. Answer like a TV app: check each value, then
  // ask the web view about the plain type.
  var TYPE_LIMITS = { width: 3840, height: 2160, framerate: 60, bitrate: 60000000, channels: 2 };
  var TYPE_VALUES = {
    eotf: ['bt709'],                       // SDR only
    cryptoblockformat: ['subsample'],
    'decode-to-texture': ['true', 'false'],
    experimental: ['allowed'],
    tunnelmode: ['false']
  };
  if (window.MediaSource && MediaSource.isTypeSupported) {
    var origIsType = MediaSource.isTypeSupported;
    MediaSource.isTypeSupported = function (type) {
      try {
        var parts = String(type).split(';');
        var plain = [parts[0]];
        for (var i = 1; i < parts.length; i++) {
          var eq = parts[i].indexOf('=');
          var k = parts[i].slice(0, eq < 0 ? undefined : eq).trim().toLowerCase();
          var v = eq < 0 ? '' : parts[i].slice(eq + 1).trim().replace(/"/g, '').toLowerCase();
          if (TYPE_LIMITS.hasOwnProperty(k)) {
            var n = Number(v);
            if (!(n >= 0) || n > TYPE_LIMITS[k]) return false;
          } else if (TYPE_VALUES.hasOwnProperty(k)) {
            if (TYPE_VALUES[k].indexOf(v) < 0) return false;
          } else {
            plain.push(parts[i]);
          }
        }
        // 10-bit codecs are YouTube's HDR streams; they stutter badly in the web view.
        if (/vp09\.0[23]\.|vp09\.\d\d\.\d\d\.1[02]|av01\.\d\.\d\d[MH]\.1[02]/i.test(type)) return false;
        return origIsType.call(this, plain.join(';'));
      } catch (e) {
        return origIsType.apply(this, arguments);
      }
    };
  }

  // Measured on this TV: 4K at 24-30 fps and 1080p at 60 fps play smoothly; 1440p60
  // drops ~6% of frames and 4K60 ~40%. Mark heavier formats "not smooth" (still
  // supported) so Auto quality skips them. Saying "not supported" instead makes
  // YouTube drop every 60 fps format.
  if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
    var mediaCaps = navigator.mediaCapabilities;
    var origDecodingInfo = mediaCaps.decodingInfo;
    mediaCaps.decodingInfo = function (config) {
      var v = config && config.video;
      var heavy = v && (v.framerate > 30 ? v.height > 1080 : v.height > 2160);
      return origDecodingInfo.apply(this, arguments).then(function (r) {
        if (!heavy || !r.supported) return r;
        return { supported: true, smooth: false, powerEfficient: false, configuration: r.configuration };
      });
    };
  }

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

  // Frame-rate matching. Measure the playing video's frame rate from the gaps between
  // its frames, then let the app switch the TV to a matching refresh rate (24 fps -> 24 Hz).
  // Only on the watch page, so the short previews on the home screen never switch it.
  if (Native && Native.matchFrameRate) {
    var onWatchPage = function () { return /^#\/watch/.test(location.hash); };
    var measureRun = 0;
    var measureFps = function (video) {
      if (!video.requestVideoFrameCallback) return;   // older web view: leave the TV alone
      var run = ++measureRun, last = -1, gaps = [];
      var onFrame = function (now, frame) {
        if (run !== measureRun) return;
        var gap = frame.mediaTime - last;
        last = frame.mediaTime;
        if (gap > 1 / 121 && gap < 1 / 11) gaps.push(gap);   // skip seeks and long stalls
        if (gaps.length < 30) { video.requestVideoFrameCallback(onFrame); return; }
        gaps.sort(function (a, b) { return a - b; });
        // Middle value, so a few dropped frames don't change the answer.
        if (onWatchPage()) { try { Native.matchFrameRate(1 / gaps[15]); } catch (e) {} }
      };
      video.requestVideoFrameCallback(onFrame);
    };
    document.addEventListener('playing', function (e) {
      if (e.target instanceof HTMLVideoElement && onWatchPage()) measureFps(e.target);
    }, true);
    window.addEventListener('hashchange', function () {
      if (onWatchPage()) return;
      measureRun++;
      try { Native.matchFrameRate(0); } catch (e) {}
    });
  }

  // "Exit YouTube?" -> Yes closes the app.
  window.close = function () {
    try { if (Native) Native.exitApp(); } catch (e) {}
  };
})();
