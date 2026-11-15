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

  // YouTube sizes its whole layout from the window size, on load and on each resize
  // event: 12px text on a 960 px wide window, 16px on 1280. The web view reports a
  // larger window when anything in the page is wider than the screen. Measured on a
  // Hisense TV (web view 157): one 1280 px wide element turned a 960 px window into
  // 1280, and on the next resize event YouTube switched to 16px. That layout is itself
  // 1280 px wide, so it stayed 1.33 times too wide with the right third off screen.
  // A Fire TV (web view 138) showed the same 1.33 in photos. Whether it got there the
  // same way is not confirmed. In the Hisense test, minimum-scale=1 stopped the window
  // growing past the screen. YouTube's TV page has no viewport tag of its own.
  // This script can run before the page has a <head>, so wait for one. If the tag
  // lands after the window has already grown, the web view applies it only at the
  // next layout and YouTube keeps its text size. Force a layout, then tell YouTube
  // the window changed.
  try {
    var pinViewport = function () {
      if (document.querySelector('meta[name="viewport"][data-cleantube]')) return true;
      if (!document.head) return false;
      var widthBefore = window.innerWidth;
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      meta.setAttribute('data-cleantube', '');
      meta.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1';
      document.head.appendChild(meta);
      if (document.body) {
        var probe = document.createElement('div');
        document.body.appendChild(probe);
        var widthAfter = window.innerWidth;
        document.body.removeChild(probe);
        if (widthAfter !== widthBefore) window.dispatchEvent(new Event('resize'));
      }
      return true;
    };
    if (location.pathname.indexOf('/tv') === 0 && !pinViewport()) {
      var headWatch = new MutationObserver(function () {
        if (pinViewport()) headWatch.disconnect();
      });
      headWatch.observe(document, { childList: true, subtree: true });
    }
  } catch (e) {}

  // When the web view has no hardware decoder for a format, it decodes on the CPU.
  // Measured on a Fire TV (Fire OS 7, web view 138): AV1 at 1080p took about 2.5 of
  // 4 cores and playback hung, while the TV's own VP9 decoder played the same video at
  // under half a core. The TV lists a hardware AV1 decoder, but the web view does not
  // use it, so only the web view can answer this. Ask it about AV1 and VP9 at 1080p,
  // 1440p and 4K ("power efficient" means hardware), and refuse what it would decode
  // on the CPU. H.264 is never refused, so there is always something to play.
  var HW_KEY = '__ctHwDecode';
  var HW_SIZES = [[1920, 1080], [2560, 1440], [3840, 2160]];
  var HW_PROBES = {
    av1: ['video/mp4; codecs="av01.0.08M.08"', 'video/mp4; codecs="av01.0.12M.08"',
      'video/mp4; codecs="av01.0.12M.08"'],
    vp9: ['video/webm; codecs="vp09.00.40.08"', 'video/webm; codecs="vp09.00.50.08"',
      'video/webm; codecs="vp09.00.50.08"']
  };
  // Tallest frame each codec decodes in hardware. YouTube can check formats before the
  // answers arrive, so they are kept in localStorage. Until the first answers: no AV1,
  // which costs one launch without it on TVs that have it, and VP9 at any size.
  var hwMax = { av1: 0, vp9: 2160 };
  try {
    var savedHw = JSON.parse(localStorage.getItem(HW_KEY) || 'null');
    if (savedHw && savedHw.av1 >= 0 && savedHw.vp9 >= 0) hwMax = savedHw;
  } catch (e) {}
  var softwareOnly = function (type, width, height) {
    var codec = /av01/i.test(type) ? 'av1' : /vp0?9/i.test(type) ? 'vp9' : '';
    if (!codec) return false;
    // A decoder's limit applies to both sides, so a tall video needs the larger size.
    var side = Math.max(width || 0, (height || 0) * 16 / 9);
    return hwMax[codec] < (side > 2560 ? 2160 : side > 1920 ? 1440 : 1080);
  };
  if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
    try {
      var hwFound = { av1: 0, vp9: 0 }, hwWaiting = 0, hwFailed = false;
      var probeHw = function (codec, i) {
        hwWaiting++;
        navigator.mediaCapabilities.decodingInfo({
          type: 'media-source',
          video: {
            contentType: HW_PROBES[codec][i], width: HW_SIZES[i][0], height: HW_SIZES[i][1],
            bitrate: 4000000, framerate: 30
          }
        }).then(function (r) {
          if (r.supported && r.powerEfficient) hwFound[codec] = Math.max(hwFound[codec], HW_SIZES[i][1]);
        }, function () {
          hwFailed = true;
        }).then(function () {
          // A question that failed is not an answer: keep what we had.
          if (--hwWaiting || hwFailed) return;
          hwMax = hwFound;
          try { localStorage.setItem(HW_KEY, JSON.stringify(hwFound)); } catch (e) {}
        });
      };
      for (var hwCodec in HW_PROBES) {
        for (var hwSize = 0; hwSize < HW_SIZES.length; hwSize++) probeHw(hwCodec, hwSize);
      }
    } catch (e) {}
  }

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
        var size = {};
        for (var i = 1; i < parts.length; i++) {
          var eq = parts[i].indexOf('=');
          var k = parts[i].slice(0, eq < 0 ? undefined : eq).trim().toLowerCase();
          var v = eq < 0 ? '' : parts[i].slice(eq + 1).trim().replace(/"/g, '').toLowerCase();
          if (TYPE_LIMITS.hasOwnProperty(k)) {
            var n = Number(v);
            if (!(n >= 0) || n > TYPE_LIMITS[k]) return false;
            size[k] = n;
          } else if (TYPE_VALUES.hasOwnProperty(k)) {
            if (TYPE_VALUES[k].indexOf(v) < 0) return false;
          } else {
            plain.push(parts[i]);
          }
        }
        // 10-bit codecs are YouTube's HDR streams; they stutter badly in the web view.
        if (/vp09\.0[23]\.|vp09\.\d\d\.\d\d\.1[02]|av01\.\d\.\d\d[MH]\.1[02]/i.test(type)) return false;
        if (softwareOnly(String(type), size.width, size.height)) return false;
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
      if (v && softwareOnly(String(v.contentType || ''), v.width, v.height)) {
        return Promise.resolve({ supported: false, smooth: false, powerEfficient: false, configuration: config });
      }
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

  // ---------- 4. Phone remote ----------
  // The app's phone page plays videos here by changing the address, the same way
  // YouTube's own TV app opens a video. When a video ends, the app plays the next one.
  var watchId = function () {
    var m = /^#\/watch.*?[?&]v=([\w-]{11})/.exec(location.hash);
    return m ? m[1] : '';
  };
  var mainVideo = function () {
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) if (vs[i].currentSrc) return vs[i];
    return vs[0] || null;
  };
  window.__ctRemote = {
    play: function (id) { location.hash = '#/watch?v=' + id; },
    pause: function () { var v = mainVideo(); if (v) v.pause(); },
    resume: function () { var v = mainVideo(); if (v) v.play(); },
    seek: function (t) {
      var v = mainVideo();
      if (!v || !isFinite(t)) return;
      var end = isFinite(v.duration) ? v.duration - 0.5 : t;
      v.currentTime = Math.max(0, Math.min(t, end));
    }
  };
  if (Native && Native.videoEnded) {
    // When a queued video ends, open the next one in the same moment. Stopping the
    // event here keeps YouTube from showing its home screen in between. replace()
    // keeps Back from stepping through every song of the queue.
    document.addEventListener('ended', function (e) {
      if (!(e.target instanceof HTMLVideoElement)) return;
      var id = watchId(), next = '';
      if (!id) return;
      try { next = String(Native.videoEnded(id) || ''); } catch (err) {}
      if (!/^[\w-]{11}$/.test(next)) return;
      e.stopImmediatePropagation();
      location.replace('#/watch?v=' + next);
    }, true);
    // If YouTube's autoplay replaced a queued song at its end, the app answers with
    // the next queued song, and we open that instead.
    window.addEventListener('hashchange', function () {
      var next = '';
      try { next = String(Native.watching(watchId()) || ''); } catch (err) {}
      if (/^[\w-]{11}$/.test(next)) location.replace('#/watch?v=' + next);
    });
    // Tell the app where the video is, only when that changes course. Phones work out
    // the position in between, so nothing runs on every frame or every second.
    var report = function (e) {
      var v = e.target;
      if (!(v instanceof HTMLVideoElement) || !watchId()) return;
      try {
        Native.progress(v.currentTime || 0, isFinite(v.duration) ? v.duration : 0,
          v.paused, !v.paused && v.readyState > 2, v.playbackRate || 1);
      } catch (err) {}
    };
    ['playing', 'pause', 'seeked', 'waiting', 'durationchange', 'ratechange'].forEach(function (t) {
      document.addEventListener(t, report, true);
    });
  }

  // "Exit YouTube?" -> Yes closes the app.
  window.close = function () {
    try { if (Native) Native.exitApp(); } catch (e) {}
  };
})();
