package com.cleantube.tv;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CleanTube: YouTube's own TV interface (youtube.com/tv) in a full-screen web view,
 * with a small script that removes ads. Nothing else.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.youtube.com/tv";
    private static final int MIC_REQUEST = 1;

    WebView web;
    String script;
    String userAgent;
    PermissionRequest pendingMic;
    /** True once YouTube's TV page has loaded (not offline/error/loading). */
    volatile boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        script = readAsset("inject.js");
        userAgent = buildUserAgent();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(userAgent);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(this), "CleanTubeNative");
        web.setWebViewClient(new Client(this));
        web.setWebChromeClient(new Chrome(this));

        if (state == null || web.restoreState(state) == null) web.loadUrl(HOME);
        web.requestFocus();
    }

    // Pretend to be a Cobalt TV app, the same way VacuumTube does on desktop.
    private static String buildUserAgent() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        String chip = abi.startsWith("arm64") ? "arm64" : abi.startsWith("x86_64") ? "x8664"
                : abi.startsWith("x86") ? "x86" : "arm";
        String os = abi.startsWith("x86") ? "X11; Linux x86_64" : "X11; Linux aarch64";
        int year = Calendar.getInstance().get(Calendar.YEAR);
        return "Mozilla/5.0 (" + os + ") Cobalt/27.lts.3.1040976-gold (unlike Gecko) "
                + "v8/13.8.258.54-jit gles Starboard/18, CleanTube_DESKTOP_" + chip + "_" + year
                + "/1.0 (CleanTube, Linux) com.cleantube.tv/1.0";
    }

    // ---------- Remote control ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Fallback 1: YouTube isn't showing (offline page, still loading) -> Back closes the app.
            if (!pageReady) {
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            }
            // Fallback 2: holding Back for 1.5 seconds always closes the app.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getEventTime() - e.getDownTime() >= 1500) {
                finish();
                return true;
            }
            // Normal case: YouTube's TV page treats Escape as "Back". Its own menu handles exiting.
            KeyEvent esc = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_ESCAPE, e.getRepeatCount(), e.getMetaState());
            if (!web.hasFocus()) web.requestFocus();
            web.dispatchKeyEvent(esc);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------- Bridge used by inject.js ----------

    static final class Bridge {
        final MainActivity a;
        Bridge(MainActivity a) { this.a = a; }

        @JavascriptInterface
        public void setPlaying(final boolean playing) {
            a.runOnUiThread(() -> {
                if (playing) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void exitApp() {
            a.runOnUiThread(a::finish);
        }

        /** fps > 0: match the TV to this frame rate. fps <= 0: back to the TV's own setting. */
        @JavascriptInterface
        public void matchFrameRate(final float fps) {
            a.runOnUiThread(() -> a.matchFrameRate(fps));
        }
    }

    // ---------- Frame-rate matching ----------
    // A 24 fps video on a 60 Hz screen shows frames for uneven times (3 refreshes, then 2),
    // so slow pans judder. Switching the TV to a rate the video divides into evenly fixes it.
    // Uses Display.Mode (Android 6+), so it works on every device this app supports.

    void matchFrameRate(float fps) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        int want = fps > 0 ? pickMode(fps, lp.preferredDisplayModeId) : 0;
        if (lp.preferredDisplayModeId == want) return;
        lp.preferredDisplayModeId = want;   // 0 = no preference, the TV's normal mode
        getWindow().setAttributes(lp);
    }

    /** Returns the display mode to ask for; current = the mode we asked for last (0 = none). */
    private int pickMode(float fps, int current) {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode[] modes = display.getSupportedModes();
        Display.Mode active = display.getMode();
        for (Display.Mode m : modes) if (m.getModeId() == current) active = m;

        // Already a good fit (e.g. 30 fps on 60 Hz): don't switch, a switch blanks the screen.
        if (frameError(active.getRefreshRate(), fps) < 0.0015f) return current;

        int best = 0;
        float bestError = 0.0015f;   // 0.15%: treats 23.976 and 24 as the same rate
        for (Display.Mode m : modes) {
            if (m.getPhysicalWidth() != active.getPhysicalWidth()
                    || m.getPhysicalHeight() != active.getPhysicalHeight()) continue;
            float err = frameError(m.getRefreshRate(), fps);
            if (err < bestError) {
                bestError = err;
                best = m.getModeId();
            }
        }
        return best;   // 0 when the TV has no matching mode: stay on its normal mode
    }

    /** How far the refresh rate is from a whole multiple of fps, as a fraction of the rate. */
    private static float frameError(float hz, float fps) {
        int k = Math.round(hz / fps);
        return k < 1 ? 1f : Math.abs(hz - k * fps) / hz;
    }

    // ---------- Page loading ----------

    static boolean isYouTube(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("youtube.com") || host.endsWith(".youtube.com");
    }

    private static boolean isYouTubeOrGoogle(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        String[] ok = {"youtube.com", "google.com", "googlevideo.com", "ytimg.com",
                "gstatic.com", "googleusercontent.com", "youtube-nocookie.com"};
        for (String d : ok) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    static final class Client extends WebViewClient {
        final MainActivity a;
        Client(MainActivity a) { this.a = a; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            // Stay inside YouTube/Google; ignore links anywhere else.
            return !isYouTubeOrGoogle(req.getUrl().getHost());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (req.isForMainFrame() && "GET".equalsIgnoreCase(req.getMethod())
                    && "www.youtube.com".equalsIgnoreCase(u.getHost())
                    && u.getPath() != null && u.getPath().startsWith("/tv")) {
                try {
                    return a.loadWithScript(req);
                } catch (Exception ignored) {
                    // Fall back to a normal load; onPageStarted still injects the script.
                }
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            a.pageReady = false;
            // Backup path in case the page didn't come through loadWithScript().
            if (isYouTube(Uri.parse(url).getHost())) view.evaluateJavascript(a.script, null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Uri u = Uri.parse(url);
            a.pageReady = "www.youtube.com".equalsIgnoreCase(u.getHost())
                    && u.getPath() != null && u.getPath().startsWith("/tv");
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) a.showOffline(view);
        }
    }

    /**
     * Download YouTube's TV page ourselves so our script is the first thing in it.
     * Returns null only when the response isn't a web page (the web view then loads it itself).
     */
    WebResourceResponse loadWithScript(WebResourceRequest req) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {   // one quick retry on a network hiccup
            try {
                return fetchOnce(req);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private WebResourceResponse fetchOnce(WebResourceRequest req) throws IOException {
        String url = req.getUrl().toString();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        for (Map.Entry<String, String> h : req.getRequestHeaders().entrySet()) {
            String k = h.getKey();
            if (k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Cookie")) continue;
            c.setRequestProperty(k, h.getValue());
        }
        c.setRequestProperty("User-Agent", userAgent);
        CookieManager cm = CookieManager.getInstance();
        String cookie = cm.getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);

        try {
            int code = c.getResponseCode();
            Map<String, List<String>> headers = c.getHeaderFields();
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String v : h.getValue()) cm.setCookie(url, v);
                }
            }

            // Redirect: hand the web view a tiny page that navigates to the new address.
            // That navigation comes back through here, so the script still goes in first.
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                if (location == null) return null;
                String target = new URL(new URL(url), location).toString();
                return htmlResponse(200, "OK", redirectPage(target), new HashMap<String, String>());
            }

            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("text/html")) return null;

            Charset cs = Charset.forName("UTF-8");
            int i = type.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                try { cs = Charset.forName(type.substring(i + 8).replace("\"", "").trim()); }
                catch (Exception ignored) {}
            }

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String html = in == null ? "" : new String(readAll(in), cs);
            if (in != null) in.close();

            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                String k = h.getKey();
                if (k == null || h.getValue().isEmpty()) continue;
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.startsWith("content-security-policy") || lk.equals("content-length")
                        || lk.equals("content-encoding") || lk.equals("transfer-encoding")
                        || lk.equals("set-cookie") || lk.equals("content-type")) continue;
                out.put(k, h.getValue().get(0));
            }
            // The web view only accepts 1xx/2xx/4xx/5xx codes here.
            int status = (code >= 200 && code < 300) || code >= 400 ? code : 200;
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = status < 400 ? "OK" : "Error";
            return new WebResourceResponse("text/html", cs.name(), status, reason, out,
                    new ByteArrayInputStream(HtmlInjector.inject(html, script).getBytes(cs)));
        } finally {
            c.disconnect();
        }
    }

    static String redirectPage(String target) {
        String js = target.replace("\\", "\\\\").replace("'", "\\'")
                .replace("<", "\\u003c").replace(">", "\\u003e");
        return "<!doctype html><html><head><script>location.replace('" + js + "');</script>"
                + "</head><body style='background:#000'></body></html>";
    }

    private static WebResourceResponse htmlResponse(int code, String reason, String html,
                                                    Map<String, String> headers) {
        return new WebResourceResponse("text/html", "UTF-8", code, reason, headers,
                new ByteArrayInputStream(html.getBytes(Charset.forName("UTF-8"))));
    }

    void showOffline(WebView view) {
        pageReady = false;
        String page = "<html><body style='background:#000;color:#ddd;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='text-align:center'><h2>Can't reach YouTube</h2>"
                + "<p>Check the TV's internet, then press OK to try again.</p>"
                + "<button autofocus onclick=\"location.href='" + HOME + "'\" "
                + "style='font-size:22px;padding:12px 32px'>Try again</button></div></body></html>";
        view.loadDataWithBaseURL("https://www.youtube.com/", page, "text/html", "UTF-8", HOME);
    }

    // ---------- Video poster, microphone (voice search), protected video ----------

    static final class Chrome extends WebChromeClient {
        final MainActivity a;
        Chrome(MainActivity a) { this.a = a; }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Hides the grey "play" placeholder that web views show before a video starts.
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            boolean wantsMic = false;
            boolean wantsDrm = false;
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) wantsDrm = true;
            }
            String host = request.getOrigin() != null ? request.getOrigin().getHost() : null;
            if (!isYouTube(host) || (!wantsMic && !wantsDrm)) {
                request.deny();
                return;
            }
            // Protected (Widevine) videos, e.g. label music videos, need this or the player
            // reports "This video format is not supported".
            if (!wantsMic) {
                request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                return;
            }
            if (a.pendingMic != null) {   // only one request waiting at a time
                a.pendingMic.deny();
                a.pendingMic = null;
            }
            if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                a.pendingMic = request;
                a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (a.pendingMic == request) a.pendingMic = null;
        }

        @Override
        public void onCloseWindow(WebView window) {
            a.finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != MIC_REQUEST || pendingMic == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingMic.deny();
        }
        pendingMic = null;
    }

    // ---------- Helpers ----------

    String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(readAll(in), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }
}
