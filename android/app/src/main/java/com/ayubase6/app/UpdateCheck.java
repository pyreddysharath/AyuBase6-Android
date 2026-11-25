package com.ayubase6.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * Looks for a new app version (config/android-app.json on the website, the file the app's own check reads)
 * every 2 hours from 10 AM to 8 PM (10, 12, 2, 4, 6, 8: 6 times a day), and shows a notification at every check until the new version is installed.
 * The stored "times" value is only on (above 0) or off (0); it is on by default.
 */
public class UpdateCheck extends BroadcastReceiver {
    static final String URL_JSON = "https://ayubase6.com/config/android-app.json", PREFS = "ayu_upd", TAG = "app-update";
    private static final String ACTION = "com.ayubase6.app.UPDATE_CHECK";
    private static final int[] SLOTS = { 600, 720, 840, 960, 1080, 1200 };

    static int times(Context c) { return Math.max(0, Math.min(4, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("times", 2))); }

    static void setTimes(Context c, int n) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("times", Math.max(0, Math.min(4, n))).apply();
        schedule(c);
    }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 7601, new Intent(c, UpdateCheck.class).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next check time (inexact: Android may move it a few minutes to save battery). */
    /** The Google Play version is updated by Google Play: no checks of its own. */
    static boolean selfUpdating() { return !"play".equals(BuildConfig.FLAVOR); }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int n = selfUpdating() ? times(c) : 0;
        if (n == 0) { am.cancel(pi(c)); return; }
        Calendar now = Calendar.getInstance(), at = null;
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int d = 0; d < 2 && at == null; d++) for (int s : SLOTS) {
            if (d == 0 && s <= mins) continue;
            Calendar t = (Calendar) now.clone(); t.add(Calendar.DATE, d);
            t.set(Calendar.HOUR_OF_DAY, s / 60); t.set(Calendar.MINUTE, s % 60); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
            at = t; break;
        }
        if (at == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi(c));
        else am.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi(c));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { check(c); } catch (Exception ignored) {}
            finally { try { schedule(c); } catch (Exception ignored) {} pr.finish(); }
        }, "ayu-upd").start();
    }

    static void check(Context c) throws Exception {
        if (!selfUpdating() || times(c) == 0) return;
        Calendar now = Calendar.getInstance();
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (mins < 600 || mins > 1230) return;   // 10 AM – 8:30 PM only (a late alarm may arrive a little after 8 PM)
        HttpURLConnection h = (HttpURLConnection) new URL(URL_JSON + "?t=" + System.currentTimeMillis()).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(10000); h.setUseCaches(false);
        if (h.getResponseCode() != 200) return;
        String body;
        try (InputStream in = h.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0 && out.size() < 50000) out.write(b, 0, n); body = out.toString("UTF-8");
        }
        JSONObject cfg = new JSONObject(body);
        long latest = cfg.optLong("versionCode", 0), mine = myVersion(c);
        if (latest <= mine || !cfg.optString("url", "").startsWith("https://")) return;
        if (AnnouncementCheck.appVisible) return;   // the app shows its own card while it is open
        // At every check (every 2 hours, 10 AM to 8 PM) until the update is installed: it pops up on the screen (sound, no vibration)
        ReminderScheduler.show(c, TAG, "A new version of AyuBase6 is ready", "Tap to download.",
            false, NotificationArt.LOGO, "update", TAG, new String[0], false, "");   // logo colours (blue title, white card)
    }

    static long myVersion(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }
}
