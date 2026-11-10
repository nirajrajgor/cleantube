package com.cleantube.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates the app from the latest GitHub release. Android only installs an update
 * signed with the same key, and always asks the user to confirm on the TV.
 */
final class Updater {

    interface Listener {
        /** Called on any thread whenever state() changes. */
        void updateChanged(Updater u);

        /** Installs from CleanTube are not allowed yet: explain the one-time step on the TV. */
        void explainSetup(Updater u);
    }

    private static final String LATEST =
            "https://api.github.com/repos/nirajrajgor/cleantube/releases/latest";
    private static final String ACTION_STATUS = "com.cleantube.tv.INSTALL_STATUS";

    private final Activity activity;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Turning on "install unknown apps" makes Android close CleanTube, so the version
    // the user asked for is remembered and installed on the next start.
    private final SharedPreferences prefs;
    final String installed;

    // Guarded by this.
    private String latest = "";
    private String apkUrl = "";
    // idle, checking, uptodate, available, setup, downloading, confirm, failed
    private String status = "idle";
    private int progress;
    private String error = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int code = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    set("failed", 0, "The TV did not show the install prompt");
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                set("confirm", 100, "");
                activity.startActivity(confirm);
                // Android closes the app it updates and never reopens it.
                Toast.makeText(activity, "After installing, open CleanTube again", Toast.LENGTH_LONG).show();
            } else if (code == PackageInstaller.STATUS_SUCCESS) {
                set("uptodate", 0, "");
            } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
                skip();   // Cancel means later: don't ask again until the next version
                set("available", 0, "Install was canceled on the TV");
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                set("failed", 0, msg != null ? msg : "Install failed");
            }
        }
    };

    Updater(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        String v = "0";
        try {
            v = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        installed = v;
        prefs = activity.getSharedPreferences("update", Context.MODE_PRIVATE);
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
    }

    void stop() {
        try {
            activity.unregisterReceiver(receiver);
        } catch (Exception ignored) {}
        worker.shutdownNow();
    }

    synchronized JSONObject state() {
        try {
            return new JSONObject().put("installed", installed).put("latest", latest)
                    .put("status", status).put("progress", progress).put("error", error);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    synchronized String latest() {
        return latest;
    }

    /** The user chose Later: no automatic prompt again until the next version. */
    void skip() {
        prefs.edit().putString("skipped", latest()).apply();
        if (status().equals("setup")) set("available", 0, "");
    }

    private synchronized String status() {
        return status;
    }

    private void set(String status, int progress, String error) {
        synchronized (this) {
            this.status = status;
            this.progress = progress;
            this.error = error;
        }
        listener.updateChanged(this);   // outside the lock, the listener may take its own
    }

    private synchronized boolean busy() {
        return status.equals("checking") || status.equals("downloading");
    }

    /**
     * Asks GitHub for the latest release. Runs in the background.
     * auto: on app start. A new version then goes straight to Android's install prompt,
     * unless the user skipped it. The phone's "Check now" only reports.
     */
    void check(final boolean auto) {
        if (busy()) return;
        set("checking", 0, "");
        worker.execute(() -> {
            try {
                HttpURLConnection c = open(LATEST);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IOException("GitHub answered " + c.getResponseCode());
                JSONObject r = new JSONObject(read(c.getInputStream()));
                String tag = r.optString("tag_name").replaceFirst("^[vV]", "");
                String url = "";
                JSONArray assets = r.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url");
                }
                boolean newer = !url.isEmpty() && compare(tag, installed) > 0;
                synchronized (this) {
                    latest = tag;
                    apkUrl = url;
                }
                boolean resume = tag.equals(prefs.getString("pending", "")) && allowed();
                if (newer && (resume || (auto && !tag.equals(prefs.getString("skipped", ""))))) {
                    synchronized (this) {
                        status = "idle";   // not busy, so install() goes ahead
                    }
                    install(resume);
                } else {
                    set(newer ? "available" : "uptodate", 0, "");
                }
            } catch (Exception e) {
                set("failed", 0, "Could not check for updates");
            }
        });
    }

    /** Downloads the update and hands it to Android, which asks the user on the TV. */
    void install() {
        install(false);
    }

    /** setupSeen: the user already read the one-time setup steps. */
    void install(boolean setupSeen) {
        final String url;
        synchronized (this) {
            if (busy() || apkUrl.isEmpty() || compare(latest, installed) <= 0) return;
            url = apkUrl;
        }
        prefs.edit().remove("pending").apply();
        if (!allowed()) {
            if (!setupSeen) {
                set("setup", 0, "");
                listener.explainSetup(this);
                return;
            }
            try {
                prefs.edit().putString("pending", latest()).commit();   // before Android closes the app
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                set("setup", 0, "");
                return;
            } catch (Exception e) {
                // No such screen on this TV: Android's own install prompt links to it instead.
                prefs.edit().remove("pending").apply();
            }
        }
        set("downloading", 0, "");
        worker.execute(() -> download(url));
    }

    private boolean allowed() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void download(String url) {
        PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            HttpURLConnection c = open(url);
            if (c.getResponseCode() != 200) throw new IOException("Download failed");
            long size = c.getContentLengthLong();
            PackageInstaller.SessionParams p =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(activity.getPackageName());
            id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = c.getInputStream();
                     OutputStream out = s.openWrite("update.apk", 0, size)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int last = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = size > 0 ? (int) (done * 100 / size) : 0;
                        if (pct / 10 != last / 10) {   // tell phones every 10%, not every block
                            last = pct;
                            set("downloading", pct, "");
                        }
                    }
                    s.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;   // Android fills in the result
                Intent result = new Intent(ACTION_STATUS).setPackage(activity.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(activity, id, result, flags);
                s.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            if (id >= 0) {
                try {
                    pi.abandonSession(id);
                } catch (Exception ignored) {}
            }
            set("failed", 0, "Download failed, try again");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "CleanTube");
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Compares versions like "1.10" and "1.9" by number, part by part. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
