package com.ayubase6.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Clock on the phone: the running timer and stopwatch as live notifications (shown on the lock screen, with
 * their buttons), the timer's end as an exact alarm, and the World Clock cities (if switched on).
 * State is sent by the page (clockSet) and changed by the notification buttons (ClockReceiver).
 */
final class ClockNotifier {
    private ClockNotifier() {}
    static final String CH = "clock", PREFS = "ayu_clock";
    static final int ID_TIMER = 7401, ID_SW = 7402, ID_WORLD = 7403;
    private static final int RQ_FIRE = 7410;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static JSONObject load(Context c, String k) {
        try { String s = prefs(c).getString(k, null); return s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }
    static void save(Context c, String k, JSONObject o) { prefs(c).edit().putString(k, o.toString()).commit(); }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Clock", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Running timer and stopwatch, and World Clock (silent)");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent act(Context c, String a, int code) {
        Intent i = new Intent(c, ClockReceiver.class).setAction(ClockReceiver.PREFIX + a);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    /** Tapping a Clock notification opens Reminders → Clock on its tab (timer, stopwatch or world). */
    private static PendingIntent open(Context c, String part) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReminderScheduler.EXTRA_KEY, part).putExtra(ReminderScheduler.EXTRA_SECTION, "clock");
        return PendingIntent.getActivity(c, 7420 + ("timer".equals(part) ? 0 : "stopwatch".equals(part) ? 1 : 2), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static NotificationCompat.Builder base(Context c) { return base(c, "timer"); }
    private static NotificationCompat.Builder base(Context c, String part) {
        return new NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_lotus).setColor(NotificationArt.REMINDERS.deep)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true).setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(open(c, part));
    }

    /** Shows / updates / removes all Clock notifications and sets the timer's alarm. */
    static void refresh(Context c) {
        ensureChannel(c);
        long now = System.currentTimeMillis();
        try { postTimer(c, load(c, "timer"), now); } catch (Exception ignored) {}
        try { postStopwatch(c, load(c, "stopwatch"), now); } catch (Exception ignored) {}
        try { postWorld(c, load(c, "world")); } catch (Exception ignored) {}
        scheduleTimer(c, load(c, "timer"), now);
    }

    static String timerTitle(JSONObject t) {
        String n = t.optString("name", "").trim();
        return "⏱ " + (n.isEmpty() ? "Timer" : n);
    }
    static String roundText(JSONObject t) {
        int n = ClockKit.times(t), r = Math.max(1, t.optInt("round", 1));
        if (n == 1) return "Once";
        return n == 0 ? "Alert " + r + " · repeats until stopped" : "Alert " + r + " of " + n;
    }

    static void postTimer(Context c, JSONObject t, long now) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        String run = ClockKit.run(t);
        if ("idle".equals(run)) { nm.cancel(ID_TIMER); return; }
        NotificationCompat.Builder b = base(c).setCategory(NotificationCompat.CATEGORY_ALARM).setContentTitle(timerTitle(t));
        if ("running".equals(run)) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.optLong("endAt")).setShowWhen(true)
             .setContentText(roundText(t) + " · every " + ClockKit.hms(ClockKit.len(t)))
             .addAction(0, "Pause", act(c, "t_pause", 7431)).addAction(0, "+1 min", act(c, "t_add", 7432)).addAction(0, "Reset", act(c, "t_reset", 7433));
        } else {
            b.setShowWhen(false).setContentText("Paused · " + ClockKit.hms(t.optLong("left")) + " left · " + roundText(t))
             .addAction(0, "Resume", act(c, "t_resume", 7434)).addAction(0, "+1 min", act(c, "t_add", 7432)).addAction(0, "Reset", act(c, "t_reset", 7433));
        }
        try { nm.notify(ID_TIMER, b.build()); } catch (SecurityException ignored) {}
    }

    static String swText(long ms) {
        long cs = (ms / 10) % 100, s = ms / 1000;
        return ClockKit.hms(s * 1000) + "." + String.format(java.util.Locale.US, "%02d", cs);
    }

    static void postStopwatch(Context c, JSONObject s, long now) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        String run = ClockKit.run(s);
        if ("idle".equals(run)) { nm.cancel(ID_SW); return; }
        JSONArray laps = s.optJSONArray("laps");
        int n = laps == null ? 0 : laps.length();
        long el = ClockKit.swElapsed(s, now);
        NotificationCompat.Builder b = base(c, "stopwatch").setContentTitle("⏱ Stopwatch");
        String lapTxt = n == 0 ? "" : (n == 1 ? "1 lap" : n + " laps");
        if ("running".equals(run)) {
            b.setUsesChronometer(true).setWhen(now - el).setShowWhen(true).setContentText(lapTxt.isEmpty() ? "Running" : lapTxt)
             .addAction(0, "Pause", act(c, "s_pause", 7441)).addAction(0, "Lap", act(c, "s_lap", 7442)).addAction(0, "Reset", act(c, "s_reset", 7443));
        } else {
            b.setShowWhen(false).setContentText("Paused · " + swText(el) + (lapTxt.isEmpty() ? "" : " · " + lapTxt))
             .addAction(0, "Resume", act(c, "s_resume", 7444)).addAction(0, "Reset", act(c, "s_reset", 7443));
        }
        try { nm.notify(ID_SW, b.build()); } catch (SecurityException ignored) {}
    }

    private static final int[] ROW = {R.id.w1, R.id.w2, R.id.w3, R.id.w4, R.id.w5};
    private static final int[] CITY = {R.id.w1_city, R.id.w2_city, R.id.w3_city, R.id.w4_city, R.id.w5_city};
    private static final int[] TIME = {R.id.w1_time, R.id.w2_time, R.id.w3_time, R.id.w4_time, R.id.w5_time};

    /** The cities switched on for the lock screen (an older list without the per-city switch shows them all). */
    private static JSONArray lockCities(JSONArray list) {
        JSONArray out = new JSONArray();
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject z = list.optJSONObject(i); if (z != null && z.optBoolean("lock", true)) out.put(z); }
        return out;
    }
    private static String gmt(String tz) {
        try {
            int min = java.util.TimeZone.getTimeZone(tz).getOffset(System.currentTimeMillis()) / 60000, a = Math.abs(min);
            return "GMT" + (min < 0 ? "-" : "+") + (a / 60) + (a % 60 == 0 ? "" : ":" + String.format(java.util.Locale.US, "%02d", a % 60));
        } catch (Exception e) { return ""; }
    }
    private static RemoteViews worldViews(Context c, JSONArray list, int max, JSONObject w) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.notif_world);
        String fmt = w.optString("fmt", "phone"), show = w.optString("place", "both");
        boolean h24 = "24".equals(fmt) || (!"12".equals(fmt) && android.text.format.DateFormat.is24HourFormat(c));
        for (int i = 0; i < ROW.length; i++) {
            JSONObject z = list.optJSONObject(i);
            if (z == null || i >= max) { v.setViewVisibility(ROW[i], View.GONE); continue; }
            v.setViewVisibility(ROW[i], View.VISIBLE);
            String place = "city".equals(show) ? z.optString("city") : "country".equals(show) && !z.optString("country").isEmpty() ? z.optString("country")
                : z.optString("city") + (z.optString("country").isEmpty() ? "" : ", " + z.optString("country"));
            if (!z.optString("label").isEmpty()) place = z.optString("label");   // the person's own name for this city
            if (w.optBoolean("zone", false)) place += " · " + gmt(z.optString("tz"));
            v.setTextViewText(CITY[i], place);
            v.setString(TIME[i], "setTimeZone", z.optString("tz"));
            CharSequence f = styled(style(w), h24);
            v.setCharSequence(TIME[i], "setFormat12Hour", f);
            v.setCharSequence(TIME[i], "setFormat24Hour", f);
            float sp = "d2".equals(style(w)) ? 24 : "d3".equals(style(w)) || "d4".equals(style(w)) ? 20 : 0;
            if (sp > 0) v.setTextViewTextSize(TIME[i], android.util.TypedValue.COMPLEX_UNIT_SP, max <= 2 ? Math.min(sp, 18) : sp);   // the closed notification has less room
        }
        return v;
    }
    /** d1–d4 = digital text styles, f1–f4 = clock faces (older values: "digital", "analog"). */
    private static String style(JSONObject w) {
        String s = w.optString("style", "d1");
        return s.matches("^[df][1-4]$") ? s : "analog".equals(s) ? "f1" : "d1";
    }
    /** The time format with its look: regular bold, large and light, heavy, or display digits. */
    private static CharSequence styled(String style, boolean h24) {
        String f = h24 ? "HH:mm" : "h:mm a";
        android.text.SpannableString s = new android.text.SpannableString(f);
        int all = f.length(), fl = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        if ("d2".equals(style)) {
            s.setSpan(new android.text.style.TypefaceSpan("sans-serif-light"), 0, all, fl);
            if (!h24) s.setSpan(new android.text.style.RelativeSizeSpan(0.5f), all - 2, all, fl);
        } else if ("d4".equals(style)) {
            s.setSpan(new android.text.style.TypefaceSpan("monospace"), 0, all, fl);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, all, fl);
        } else s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, all, fl);
        return s;
    }

    private static final int[] AROW = {R.id.a1, R.id.a2, R.id.a3}, ACLOCK = {R.id.a1_clock, R.id.a2_clock, R.id.a3_clock}, ACITY = {R.id.a1_city, R.id.a2_city, R.id.a3_city}, ATIME = {R.id.a1_time, R.id.a2_time, R.id.a3_time};
    private static final int[] BROW = {R.id.b1, R.id.b2, R.id.b3}, BCLOCK = {R.id.b1_clock, R.id.b2_clock, R.id.b3_clock}, BCITY = {R.id.b1_city, R.id.b2_city, R.id.b3_city}, BTIME = {R.id.b1_time, R.id.b2_time, R.id.b3_time};
    /** Clock faces, up to three (Android 12+, where a clock face can be set to a time zone). */
    private static RemoteViews worldFaces(Context c, JSONArray list, JSONObject w) {
        String st = style(w), fmt = w.optString("fmt", "phone");
        boolean rows = "f4".equals(st), digits = rows || "f2".equals(st);
        boolean h24 = "24".equals(fmt) || (!"12".equals(fmt) && android.text.format.DateFormat.is24HourFormat(c));
        RemoteViews v = new RemoteViews(c.getPackageName(), rows ? R.layout.notif_world_rows : R.layout.notif_world_analog);
        final int[] AROW = rows ? BROW : ClockNotifier.AROW, ACLOCK = rows ? BCLOCK : ClockNotifier.ACLOCK, ACITY = rows ? BCITY : ClockNotifier.ACITY, ATIME = rows ? BTIME : ClockNotifier.ATIME;
        String show = w.optString("place", "both");
        for (int i = 0; i < AROW.length; i++) {
            JSONObject z = list.optJSONObject(i);
            if (z == null) { v.setViewVisibility(AROW[i], View.GONE); continue; }
            v.setViewVisibility(AROW[i], View.VISIBLE);
            v.setString(ACLOCK[i], "setTimeZone", z.optString("tz"));
            v.setViewVisibility(ATIME[i], digits ? View.VISIBLE : View.GONE);
            if (digits) {
                CharSequence f = styled("d1", h24);
                v.setString(ATIME[i], "setTimeZone", z.optString("tz"));
                v.setCharSequence(ATIME[i], "setFormat12Hour", f); v.setCharSequence(ATIME[i], "setFormat24Hour", f);
            }
            String place = "country".equals(show) && !z.optString("country").isEmpty() ? z.optString("country") : z.optString("city");
            if (!z.optString("label").isEmpty()) place = z.optString("label");   // the person's own name for this city
            if (w.optBoolean("zone", false)) place += " · " + gmt(z.optString("tz"));
            if ("f3".equals(st)) {   // a bigger, bolder name
                android.text.SpannableString bold = new android.text.SpannableString(place);
                bold.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, place.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                v.setTextViewText(ACITY[i], bold); v.setTextViewTextSize(ACITY[i], android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            } else v.setTextViewText(ACITY[i], place);
        }
        return v;
    }

    static void postWorld(Context c, JSONObject w) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        JSONArray list = lockCities(w.optJSONArray("list"));
        if (!w.optBoolean("on", false) || list.length() == 0) { nm.cancel(ID_WORLD); return; }
        NotificationCompat.Builder b = base(c, "world").setCategory(NotificationCompat.CATEGORY_STATUS).setContentTitle("World Clock").setShowWhen(false)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(worldViews(c, list, 2, w))
            .setCustomBigContentView(style(w).startsWith("f") && Build.VERSION.SDK_INT >= 31 ? worldFaces(c, list, w) : worldViews(c, list, 5, w))
            .setPriority(NotificationCompat.PRIORITY_LOW);
        try { nm.notify(ID_WORLD, b.build()); } catch (SecurityException ignored) {}
    }

    private static PendingIntent fireIntent(Context c) {
        Intent i = new Intent(c, ClockReceiver.class).setAction(ClockReceiver.PREFIX + "t_fire");
        return PendingIntent.getBroadcast(c, RQ_FIRE, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleTimer(Context c, JSONObject t, long now) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = fireIntent(c);
        am.cancel(pi);
        if (!"running".equals(ClockKit.run(t))) return;
        long at = Math.max(now + 500, t.optLong("endAt"));
        try {
            if (ReminderScheduler.canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
