package com.cleantube.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Phone remote: a small web server on the TV. A phone on the same Wi-Fi opens
 * http://<tv-ip>:8080 to search YouTube, build a queue, save playlists and pause.
 * The TV plays the queue one video after another.
 */
final class PhoneRemote {

    /** What the remote needs from the app. */
    interface Player {
        void playVideo(String id);
        void pauseVideo(boolean pause);
        void seekVideo(double seconds);
        void remoteReady();
    }

    private static final int FIRST_PORT = 8080;
    // Commands that change the queue or playlists, so they are written to storage.
    private static final Set<String> SAVED = new HashSet<>(java.util.Arrays.asList(
            "add", "remove", "move", "save", "playPlaylist", "deletePlaylist"));
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    // Signed-out search through YouTube's web API. No cookies are sent, so no account is used.
    private static final String API_URL = "https://www.youtube.com/youtubei/v1/";
    private static final String WEB_VERSION = "2.20251101.00.00";

    private final Player player;
    private final SharedPreferences prefs;
    private final String page;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    // Related videos per video id. Songs don't change their related list often, so the
    // same suggestion request never goes to YouTube twice in a session.
    private final Map<String, JSONArray> relatedCache = new LinkedHashMap<String, JSONArray>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONArray> e) {
            return size() > 40;
        }
    };
    private volatile ServerSocket server;
    volatile int port;
    private final Updater updater;

    // Guarded by this.
    private JSONArray queue;
    private JSONArray playlists;   // [{name, items: [video]}]
    private int current = -1;      // index in queue playing on the TV, -1 = none
    private boolean paused;
    private long playStarted;      // when we last asked the TV to play, for onWatching
    // Playback position, reported by the TV page only when it changes course
    // (play, pause, seek, buffering). Phones work out the position in between.
    private double pos, dur, rate = 1;
    private boolean advancing;
    private long posAt;
    private long version;          // bumped on every change; phones listening get the new state

    PhoneRemote(Context context, Player player, Updater updater, String page) {
        this.player = player;
        this.updater = updater;
        this.page = page;
        prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        queue = readArray("queue");
        playlists = readArray("playlists");
    }

    // ---------- Server ----------

    void start() {
        pool.execute(() -> {
            for (int p = FIRST_PORT; p < FIRST_PORT + 10 && server == null; p++) {
                try {
                    server = new ServerSocket(p);
                    port = p;
                } catch (IOException ignored) {
                    // Port taken by another app: try the next one.
                }
            }
            if (server == null) return;
            player.remoteReady();
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    break;
                }
            }
        });
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String path = req[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            int len = 0;
            String host = null, origin = null;
            for (String l : lines) {
                int c = l.indexOf(':');
                if (c <= 0) continue;
                String name = l.substring(0, c).trim();
                String value = l.substring(c + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) len = Integer.parseInt(value);
                else if (name.equalsIgnoreCase("Host")) host = value;
                else if (name.equalsIgnoreCase("Origin")) origin = value;
            }
            if (!allowed(host, origin)) {
                send(sock, 403, "text/plain", "Forbidden");
                return;
            }
            if (len < 0 || len > 65536) {
                send(sock, 413, "text/plain", "Too large");
                return;
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }

            if (method.equals("GET") && path.equals("/")) {
                send(sock, 200, "text/html; charset=utf-8", page);
            } else if (method.equals("GET") && path.equals("/api/state")) {
                send(sock, 200, "application/json", state());
            } else if (method.equals("GET") && path.equals("/api/events")) {
                events(sock);
            } else if (method.equals("GET") && path.equals("/api/search")) {
                String text = URLDecoder.decode(param(query, "q"), "UTF-8").trim();
                try {
                    send(sock, 200, "application/json", search(text).toString());
                } catch (Exception e) {
                    send(sock, 502, "text/plain", "Search failed");
                }
            } else if (method.equals("GET") && path.equals("/api/related")) {
                String ids = URLDecoder.decode(param(query, "ids"), "UTF-8");
                try {
                    send(sock, 200, "application/json", related(ids).toString());
                } catch (Exception e) {
                    send(sock, 502, "text/plain", "Suggestions failed");
                }
            } else if (method.equals("POST") && path.equals("/api/cmd")) {
                send(sock, 200, "application/json", command(new JSONObject(new String(body, UTF8))));
            } else {
                send(sock, 404, "text/plain", "Not found");
            }
        } catch (Exception ignored) {
            // A bad request from one phone must never affect the TV.
        }
    }

    /**
     * Blocks other websites from driving the TV. A page on any device on the Wi-Fi
     * could otherwise send commands here.
     * Host must be an IP address, which stops DNS rebinding, where an attacker's
     * domain name is pointed at the TV. Browsers send Origin with every POST and
     * cross-site request, so it must match the remote page itself. Scripts and apps
     * send no Origin and still work.
     */
    static boolean allowed(String host, String origin) {
        if (host != null) {
            String name = host.startsWith("[") ? "[" : host.replaceFirst(":\\d+$", "");
            if (!name.equals("[") && !name.equals("localhost")
                    && !name.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        }
        return origin == null || (host != null && origin.equals("http://" + host));
    }

    /**
     * Server-sent events. The phone keeps this one request open and gets the state
     * each time it changes, instead of asking every few seconds.
     */
    private void events(Socket sock) throws IOException, InterruptedException {
        OutputStream out = sock.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Cache-Control: no-store\r\nConnection: keep-alive\r\n\r\nretry: 2000\n\n").getBytes(UTF8));
        out.flush();
        long seen = -1;
        while (server != null && !server.isClosed()) {
            String data = null;
            synchronized (this) {
                if (version == seen) wait(15000);
                if (version != seen) {
                    seen = version;
                    data = state();
                }
            }
            // A comment line every 15 s finds phones that went away, so their thread ends.
            out.write((data == null ? ": ping\n\n" : "data: " + data + "\n\n").getBytes(UTF8));
            out.flush();
        }
    }

    private void changed() {
        version++;
        notifyAll();
    }

    /** Something outside the queue changed, like the update status: tell the phones. */
    synchronized void refresh() {
        changed();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < 16384) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            // Looking for \r\n\r\n
            state = (b == '\r') ? (state == 2 ? 3 : 1) : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(buf.toByteArray(), UTF8);
        }
        return null;
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int e = pair.indexOf('=');
            if (e > 0 && pair.substring(0, e).equals(name)) return pair.substring(e + 1);
        }
        return "";
    }

    private static void send(Socket sock, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(UTF8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = sock.getOutputStream();
        out.write(head.getBytes(UTF8));
        out.write(b);
        out.flush();
    }

    // ---------- Queue and playlists ----------

    synchronized String state() {
        try {
            JSONArray lists = new JSONArray();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject p = playlists.getJSONObject(i);
                lists.put(new JSONObject().put("name", p.getString("name"))
                        .put("count", p.getJSONArray("items").length()));
            }
            return new JSONObject().put("queue", queue).put("current", current)
                    .put("paused", paused).put("playlists", lists)
                    .put("pos", position()).put("dur", dur).put("advancing", advancing && current >= 0)
                    .put("rate", rate)
                    .put("update", updater.state()).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private synchronized String command(JSONObject c) throws JSONException {
        String action = c.optString("action");
        switch (action) {
            case "add": {
                JSONObject v = cleanVideo(c.optJSONObject("video"));
                if (v == null) break;
                // "index" puts the video back where it was, to undo a remove.
                int at = c.optInt("index", queue.length());
                if (at < 0 || at > queue.length()) at = queue.length();
                queue = insert(queue, at, v);
                if (current >= at) current++;
                else if (current < 0 && c.optBoolean("current")) current = at;
                break;
            }
            case "remove": {
                int i = c.optInt("index", -1);
                if (!inQueue(i)) break;
                queue.remove(i);
                if (i == current) current = -1;
                else if (i < current) current--;
                break;
            }
            case "move": {
                int from = c.optInt("from", -1), to = c.optInt("to", -1);
                if (!inQueue(from) || !inQueue(to)) break;
                // Takes the video out and puts it at its new place; the ones between shift by one.
                Object x = queue.remove(from);
                queue = insert(queue, to, x);
                if (current == from) current = to;
                else if (from < current && current <= to) current--;
                else if (to <= current && current < from) current++;
                break;
            }
            case "play":
                playAt(c.optInt("index", 0));
                break;
            case "pause":
            case "resume":
                if (current < 0) break;
                paused = action.equals("pause");
                player.pauseVideo(paused);
                break;
            case "seek": {
                double to = c.optDouble("to", -1);
                if (current < 0 || !(to >= 0)) break;
                // Jumping to (or past) the end means "next song". Seeking YouTube's player
                // to its last moment can make it start its own suggestion instead.
                if (dur > 0 && to >= dur - 1 && inQueue(current + 1)) {
                    playAt(current + 1);
                    break;
                }
                if (dur > 0 && to > dur) to = dur;
                pos = to;
                posAt = SystemClock.elapsedRealtime();
                player.seekVideo(to);
                break;
            }
            case "save": {
                String name = c.optString("name").trim();
                if (name.isEmpty() || queue.length() == 0) break;
                if (name.length() > 60) name = name.substring(0, 60);
                removePlaylist(name);
                playlists.put(new JSONObject().put("name", name)
                        .put("items", new JSONArray(queue.toString())));
                break;
            }
            case "playPlaylist": {
                int i = findPlaylist(c.optString("name"));
                if (i < 0) break;
                queue = new JSONArray(playlists.getJSONObject(i).getJSONArray("items").toString());
                playAt(0);
                break;
            }
            case "checkUpdate":
                updater.check(false);
                break;
            case "installUpdate":
                updater.install();
                break;
            case "deletePlaylist":
                removePlaylist(c.optString("name"));
                break;
            default:
                break;
        }
        if (SAVED.contains(action)) {
            prefs.edit().putString("queue", queue.toString())
                    .putString("playlists", playlists.toString()).apply();
        }
        changed();
        return state();
    }

    private boolean inQueue(int i) {
        return i >= 0 && i < queue.length();
    }

    /** A copy of the list with item at index i; later items move down by one. */
    private static JSONArray insert(JSONArray list, int i, Object item) throws JSONException {
        JSONArray out = new JSONArray();
        for (int k = 0; k < list.length(); k++) {
            if (k == i) out.put(item);
            out.put(list.get(k));
        }
        if (i >= list.length()) out.put(item);
        return out;
    }

    private void playAt(int i) {
        if (!inQueue(i)) return;
        select(i);
        player.playVideo(currentId());
    }

    /** Marks queue item i as the one on the TV, without telling the TV to play it. */
    private void select(int i) {
        current = i;
        paused = false;
        pos = dur = 0;
        advancing = false;
        playStarted = SystemClock.elapsedRealtime();
    }

    private String currentId() {
        return inQueue(current) ? queue.optJSONObject(current).optString("id") : "";
    }

    private int findPlaylist(String name) throws JSONException {
        for (int i = 0; i < playlists.length(); i++) {
            if (playlists.getJSONObject(i).getString("name").equals(name)) return i;
        }
        return -1;
    }

    private void removePlaylist(String name) throws JSONException {
        int i = findPlaylist(name);
        if (i >= 0) playlists.remove(i);
    }

    /** Keeps only the fields we use, and only a valid video id. */
    private static JSONObject cleanVideo(JSONObject v) throws JSONException {
        if (v == null) return null;
        String id = v.optString("id");
        if (!VIDEO_ID.matcher(id).matches()) return null;
        return new JSONObject().put("id", id)
                .put("title", cut(v.optString("title"), 200))
                .put("channel", cut(v.optString("channel"), 100))
                .put("length", cut(v.optString("length"), 12));
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    // ---------- Events from the TV page ----------

    /**
     * A video on the TV finished. If it was ours, returns the next queued video's id
     * for the page to open right away, or "" when there is none.
     */
    synchronized String onEnded(String id) {
        if (current < 0 || !id.equals(currentId())) return "";
        String next = "";
        if (inQueue(current + 1)) {
            select(current + 1);
            next = currentId();
        } else {
            current = -1;
        }
        changed();
        return next;
    }

    /**
     * The TV moved to another page or video. Returns a queued video to open instead,
     * or "". If YouTube's own autoplay replaced our song at its end, the queue goes on
     * with its next song. Any other move away means the queue stops following the TV.
     */
    synchronized String onWatching(String id) {
        // Right after we start a video, YouTube can still pass through other pages
        // (e.g. home after the previous video ends). Give it a few seconds.
        if (SystemClock.elapsedRealtime() - playStarted < 5000) return "";
        if (current < 0 || id.equals(currentId())) return "";
        if (!id.isEmpty() && nearEnd() && inQueue(current + 1)) {
            select(current + 1);
            changed();
            return currentId();
        }
        current = -1;
        changed();
        return "";
    }

    /** True when our song was in its last 30 seconds, where YouTube shows "Up next". */
    private boolean nearEnd() {
        if (dur <= 0) return false;
        return dur - position() <= 30;
    }

    /** Where the video is now: the last reported position plus the time it has been playing. */
    private double position() {
        double now = pos + (advancing ? (SystemClock.elapsedRealtime() - posAt) / 1000.0 * rate : 0);
        return dur > 0 && now > dur ? dur : now;
    }

    /** The video on the watch page started, stopped, buffered or jumped. */
    synchronized void onProgress(double position, double duration, boolean isPaused,
                                 boolean isAdvancing, double playbackRate) {
        pos = position;
        dur = duration;
        rate = playbackRate > 0 ? playbackRate : 1;
        advancing = isAdvancing;
        posAt = SystemClock.elapsedRealtime();
        if (current >= 0) paused = isPaused;
        changed();
    }

    // ---------- Search ----------

    private static JSONArray search(String text) throws IOException, JSONException {
        JSONArray out = new JSONArray();
        if (text.isEmpty()) return out;
        collect(youtube("search", new JSONObject().put("query", text)), out, new HashSet<String>(), 0);
        return out;
    }

    /**
     * Suggestions for up to 2 videos: their related videos, taken in turns so both
     * count, without duplicates or the videos themselves. Up to 40, so the phone can
     * skip ones it already showed. The 2 lookups run at once.
     */
    private JSONArray related(String ids) throws Exception {
        Set<String> seen = new HashSet<>();
        List<Future<JSONArray>> jobs = new ArrayList<>();
        for (String id : ids.split(",")) {
            if (!VIDEO_ID.matcher(id).matches() || !seen.add(id) || jobs.size() == 2) continue;
            final String vid = id;
            jobs.add(pool.submit(() -> relatedFor(vid)));
        }
        List<JSONArray> lists = new ArrayList<>();
        for (Future<JSONArray> j : jobs) lists.add(j.get());
        JSONArray out = new JSONArray();
        for (int i = 0; i < 20 && out.length() < 40; i++) {
            for (JSONArray l : lists) {
                JSONObject v = l.optJSONObject(i);
                if (v != null && seen.add(v.optString("id"))) out.put(v);
            }
        }
        return out;
    }

    private JSONArray relatedFor(String id) throws IOException, JSONException {
        synchronized (relatedCache) {
            JSONArray hit = relatedCache.get(id);
            if (hit != null) return hit;
        }
        JSONArray out = new JSONArray();
        collect(youtube("next", new JSONObject().put("videoId", id)), out, new HashSet<String>(), 0);
        synchronized (relatedCache) {
            relatedCache.put(id, out);
        }
        return out;
    }

    /** One signed-out request to YouTube's web API. No cookies are sent. */
    private static JSONObject youtube(String endpoint, JSONObject request) throws IOException, JSONException {
        Locale l = Locale.getDefault();
        JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", WEB_VERSION)
                .put("hl", l.getLanguage().isEmpty() ? "en" : l.getLanguage())
                .put("gl", l.getCountry().isEmpty() ? "US" : l.getCountry());
        byte[] body = request.put("context", new JSONObject().put("client", client)).toString().getBytes(UTF8);

        HttpURLConnection c = (HttpURLConnection) new URL(API_URL + endpoint + "?prettyPrint=false").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(MainActivity.readAll(in), UTF8));
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Finds every video in a YouTube response, in order. Search uses "videoRenderer";
     * related videos use the newer "lockupViewModel".
     */
    private static void collect(Object node, JSONArray out, Set<String> seen, int depth) {
        if (out.length() >= 20 || depth > 40) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, seen, depth + 1);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject lockup = o.optJSONObject("lockupViewModel");
            if (lockup != null) {
                addLockup(lockup, out, seen);
                return;
            }
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null) {
                String id = v.optString("videoId");
                if (VIDEO_ID.matcher(id).matches() && seen.add(id)) {
                    String length = text(v.optJSONObject("lengthText"));
                    try {
                        out.put(new JSONObject().put("id", id)
                                .put("title", text(v.optJSONObject("title")))
                                .put("channel", text(v.optJSONObject("ownerText")))
                                .put("length", length.isEmpty() ? "Live" : length));
                    } catch (JSONException ignored) {}
                }
                return;
            }
            JSONArray keys = o.names();
            if (keys == null) return;
            for (int i = 0; i < keys.length(); i++) {
                Object child = o.opt(keys.optString(i));
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collect(child, out, seen, depth + 1);
                }
            }
        }
    }

    private static void addLockup(JSONObject l, JSONArray out, Set<String> seen) {
        if (!"LOCKUP_CONTENT_TYPE_VIDEO".equals(l.optString("contentType"))) return;   // skip mixes, playlists
        String id = l.optString("contentId");
        if (!VIDEO_ID.matcher(id).matches() || !seen.add(id)) return;
        JSONObject md = path(l, "metadata", "lockupMetadataViewModel");
        JSONObject title = path(md, "title");
        JSONArray rows = path(md, "metadata", "contentMetadataViewModel") == null ? null
                : path(md, "metadata", "contentMetadataViewModel").optJSONArray("metadataRows");
        String channel = "";
        if (rows != null && rows.length() > 0) {
            JSONArray parts = rows.optJSONObject(0) == null ? null : rows.optJSONObject(0).optJSONArray("metadataParts");
            JSONObject first = parts == null ? null : parts.optJSONObject(0);
            channel = first == null ? "" : path(first, "text") == null ? "" : path(first, "text").optString("content");
        }
        JSONObject badge = find(l.optJSONObject("contentImage"), "thumbnailBadgeViewModel", 0);
        String length = badge == null ? "" : badge.optString("text");
        try {
            out.put(new JSONObject().put("id", id)
                    .put("title", title == null ? "" : title.optString("content"))
                    .put("channel", channel)
                    .put("length", length.equalsIgnoreCase("live") ? "Live" : length));
        } catch (JSONException ignored) {}
    }

    private static JSONObject path(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o == null) return null;
            o = o.optJSONObject(k);
        }
        return o;
    }

    /** First object stored under key, anywhere below node. */
    private static JSONObject find(Object node, String key, int depth) {
        if (node == null || depth > 12) return null;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = find(a.opt(i), key, depth + 1);
                if (r != null) return r;
            }
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject hit = o.optJSONObject(key);
            if (hit != null) return hit;
            JSONArray names = o.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject r = find(o.opt(names.optString(i)), key, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** YouTube text is either {simpleText} or {runs: [{text}, ...]}. */
    private static String text(JSONObject t) {
        if (t == null) return "";
        if (t.has("simpleText")) return t.optString("simpleText");
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text"));
        }
        return sb.toString();
    }
}
