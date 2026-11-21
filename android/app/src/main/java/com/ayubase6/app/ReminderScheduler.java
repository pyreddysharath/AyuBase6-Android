package com.ayubase6.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps one Android alarm set for the next minute at which a reminder is due.
 * When it rings, AlarmReceiver shows what is due and sets the alarm for the next one.
 * The rules are stored on the phone, so this works with the app closed and after a restart.
 */
final class ReminderScheduler {
    private ReminderScheduler() {}

    static final String TAG = "AyuReminders";
    static final String CHANNEL_REMINDERS = "reminders";
    static final String CHANNEL_REMINDERS_QUIET = "reminders_novib";
    static final String CHANNEL_UPDATES = "updates";
    static final String EXTRA_MINUTE = "ayu_minute";
    static final String EXTRA_KEY = "ayu_key";
    static final String EXTRA_SECTION = "ayu_section";
    private static final String PREFS = "ayu_reminders";
    private static final String PREF_RULES = "rules";
    private static final String PREF_NEXT = "next";
    private static final int ALARM_REQUEST = 7001;
    private static final int SEARCH_DAYS = 8;      // every rule repeats at least weekly
    private static final int MAX_LATE_MIN = 60;    // an alarm this late (phone was off) shows only what is still recent

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONObject loadRules(Context c) {
        String s = prefs(c).getString(PREF_RULES, null);
        if (s == null) return null;
        try {
            JSONObject r = new JSONObject(s);
            JSONObject ck = r.optJSONObject("checks"), sh = wakeShift(c);   // a snoozed wake-up alarm moves today's checks
            if (ck != null && sh != null) { ck.put("shiftDay", sh.optString("day")); ck.put("shiftMin", sh.optInt("min")); }
            return r;
        } catch (Exception e) { return null; }
    }

    /** Today's shift of the Checks times after the wake-up alarm was snoozed: {day, min}, or null. The app takes it over when it opens. */
    static JSONObject wakeShift(Context c) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("wakeShift", "{}"));
            String today = local(System.currentTimeMillis() / 60000L).day;
            return today.equals(o.optString("day")) && o.optInt("min", 0) > 0 ? o : null;
        } catch (Exception e) { return null; }
    }
    static void addWakeShift(Context c, int minutes) {
        try {
            JSONObject old = wakeShift(c);
            String today = local(System.currentTimeMillis() / 60000L).day;
            prefs(c).edit().putString("wakeShift", new JSONObject().put("day", today).put("min", (old == null ? 0 : old.optInt("min")) + minutes).toString()).apply();
        } catch (Exception ignored) {}
    }
    static void clearWakeShift(Context c) { prefs(c).edit().remove("wakeShift").apply(); }

    // ── Checks that only matter while the phone is in use ("Do not use mobile", "Sleep"): not sent to a phone that is put away ──
    static boolean onlyInUse(String key) { return key != null && key.matches("^routine-(end-|again-)?(nomobile|sleep)$"); }
    static boolean inUse(Context c) {
        try { android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE); return pm == null || pm.isInteractive(); }
        catch (Exception e) { return true; }
    }

    // ── A quiet note two hours before an alarm rings ("Alarm at 6:30 AM"), also on the lock screen ──
    static final String UPCOMING_TAG = "alarm-upcoming";
    private static final long UPCOMING_MS = 2 * 60 * 60000L;
    private static void upcoming(Context c, AlarmManager am, JSONObject rules, long now) {
        Intent wi = new Intent(c, AlarmReceiver.class).setAction("com.ayubase6.app.UPCOMING");
        PendingIntent wake = PendingIntent.getBroadcast(c, ALARM_REQUEST + 2, wi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(wake);
        long ring = 0; String key = null, title = "";
        if (rules != null) for (long t = now + 1; t <= now + 1440L && ring == 0; t++) {
            for (ReminderRules.Due d : ReminderRules.due(rules, local(t), t))
                if (d.key.startsWith("alarm-") && AlarmService.config(c, d.key) != null) { ring = t; key = d.key; title = d.title; break; }
        }
        if (ring == 0) { cancel(c, UPCOMING_TAG); return; }
        long ms = ring * 60000L, left = ms - System.currentTimeMillis();
        if (left > UPCOMING_MS) {   // not yet: come back two hours before it
            cancel(c, UPCOMING_TAG);
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms - UPCOMING_MS, wake); } catch (Exception ignored) {}
            return;
        }
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;
        ensureChannels(c);
        String sec = "alarm-wake".equals(key) ? "routines" : "alerts";
        NotificationArt.Theme theme = NotificationArt.forSection(sec);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_SECTION, sec);
        PendingIntent pi = PendingIntent.getActivity(c, idFor(UPCOMING_TAG), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String when = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(ms));
        String name = noTime(title);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_UPDATES)
            .setSmallIcon(smallIcon(c))
            .setContentTitle("Alarm at " + when)
            .setContentText("Alarm".equals(name) || name.isEmpty() ? "Upcoming alarm" : name)
            .setLargeIcon(art("alarm" + theme.key(), () -> NotificationArt.alarm(theme)))   // an alarm clock in the section's colour
            .setColor(theme.deep).setContentIntent(pi)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(ms).setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        try { nm.notify(idFor(UPCOMING_TAG), b.build()); } catch (SecurityException ignored) {}
    }

    static void saveRules(Context c, JSONObject rules) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (rules == null) e.remove(PREF_RULES); else e.putString(PREF_RULES, rules.toString());
        e.apply();
    }

    static long nextAt(Context c) { return prefs(c).getLong(PREF_NEXT, 0); }

    static ReminderRules.Local local(long epochMin) {
        ZonedDateTime z = Instant.ofEpochMilli(epochMin * 60000L).atZone(ZoneId.systemDefault());
        String day = String.format(Locale.US, "%04d-%02d-%02d", z.getYear(), z.getMonthValue(), z.getDayOfMonth());
        int dow = z.getDayOfWeek().getValue() % 7;   // Monday=1 ... Sunday=7 -> Sunday=0
        return new ReminderRules.Local(day, z.getHour() * 60 + z.getMinute(), dow);
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent alarmIntent(Context c, long minute) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("com.ayubase6.app.REMINDER");
        i.putExtra(EXTRA_MINUTE, minute);
        return PendingIntent.getBroadcast(c, ALARM_REQUEST, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets the alarm for the next due minute after now. Returns its time in ms, or 0 when nothing is due. */
    static long reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;
        am.cancel(alarmIntent(c, 0));
        JSONObject rules = loadRules(c);
        long next = 0;
        long now = System.currentTimeMillis() / 60000L;
        if (rules != null) {
            for (long t = now + 1; t <= now + SEARCH_DAYS * 1440L; t++) {
                if (!ReminderRules.due(rules, local(t), t).isEmpty()) { next = t; break; }
            }
        }
        // "Not yet" asks again later: the earliest of those, if it comes first
        org.json.JSONArray sn = snoozes(c);
        for (int i = 0; i < sn.length(); i++) {
            long t = sn.optJSONObject(i) == null ? 0 : sn.optJSONObject(i).optLong("t");
            if (t > now && (next == 0 || t < next)) next = t;
        }
        if (next > 0) {
            long ms = next * 60000L;
            PendingIntent pi = alarmIntent(c, next);
            try {
                // A ringing alarm is due: Android's alarm-clock alarm (on time even in deep sleep and battery saving,
                // and allowed to start ringing with the app closed); the alarm icon shows in the status bar.
                String ringKey = ringKeyAt(c, rules, sn, next);
                boolean rings = ringKey != null;
                if (canExact(c) && rings) {
                    // Tapping the time / next alarm on the phone opens this alarm in AyuBase6
                    String sec = "alarm-wake".equals(ringKey) ? "routines" : "alerts";
                    Intent open = new Intent(c, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_KEY, ringKey).putExtra(EXTRA_SECTION, sec);
                    // Clock → Alarm "Tapping the phone's time opens: Phone's Clock": the phone's own Clock app (if it has one)
                    if (rules != null && "clock".equals(rules.optString("alarmTap"))) {
                        Intent clk = new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (clk.resolveActivity(c.getPackageManager()) != null) open = clk;
                    }
                    PendingIntent show = PendingIntent.getActivity(c, ALARM_REQUEST + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(ms, show), pi);
                }
                else if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
                if (rings && prefs(c).getLong("loggedNext", 0) != ms) {   // once per alarm time
                    prefs(c).edit().putLong("loggedNext", ms).apply();
                    AlarmLog.add(c, "set:" + ms + ":" + (canExact(c) ? "clock" : "inexact"));
                }
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
            }
        }
        try { upcoming(c, am, rules, now); } catch (Exception ignored) {}
        long nextMs = next * 60000L;
        prefs(c).edit().putLong(PREF_NEXT, nextMs).apply();
        Log.i(TAG, "next reminder at " + (next > 0 ? Instant.ofEpochMilli(nextMs).toString() : "none") + (canExact(c) ? " (exact)" : " (inexact)"));
        return nextMs;
    }

    /** The alarm (Checks wake-up or Reminders alarm) that rings at minute t, or null. */
    static String ringKeyAt(Context c, JSONObject rules, org.json.JSONArray sn, long t) {
        try {
            if (rules != null) for (ReminderRules.Due d : ReminderRules.due(rules, local(t), t))
                if (d.key.startsWith("alarm-") && AlarmService.config(c, d.key) != null) return d.key;
            for (int i = 0; sn != null && i < sn.length(); i++) {
                JSONObject o = sn.optJSONObject(i);
                if (o != null && o.optLong("t") == t && o.optBoolean("alarm", false)) return o.optString("key", "alarm-rem");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** The alarm for minute `target` rang: show what is due (latest per reminder), then set the next alarm. */
    static void onAlarm(Context c, long target) {
        try { onAlarmInner(c, target); }
        catch (Throwable t) { AlarmLog.add(c, "onAlarm-failed:" + t.getClass().getSimpleName()); }
        finally { try { reschedule(c); } catch (Throwable ignored) { } }
    }

    private static final java.util.concurrent.ScheduledExecutorService GAP = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    private static void onAlarmInner(Context c, long target) {
        JSONObject rules = loadRules(c);
        long now = System.currentTimeMillis() / 60000L;
        if (rules != null && target > 0) {
            long from = Math.max(target, now - MAX_LATE_MIN);
            Map<String, ReminderRules.Due> latest = new LinkedHashMap<>();
            Map<String, Long> dueAt = new java.util.HashMap<>();
            for (long t = from; t <= now; t++) {
                for (ReminderRules.Due d : ReminderRules.due(rules, local(t), t)) { latest.put(d.key, d); dueAt.put(d.key, t); }
            }
            int soundIdx = 0;
            for (Map.Entry<String, ReminderRules.Due> e : latest.entrySet()) {
                ReminderRules.Due d = e.getValue();
                // Checks → Alarm rings (only if it is due now, not hours late); everything else is a notification
                boolean isAlarm = d.key.startsWith("alarm-") && AlarmService.config(c, d.key) != null;
                long late = now - dueAt.getOrDefault(d.key, 0L);
                boolean ring = isAlarm && late <= 2;
                if (isAlarm && !ring) AlarmLog.add(c, "late:" + d.key + ":" + late);
                if (ring) { boolean ok = AlarmService.start(c, d.key, d.title, d.body, 0); AlarmLog.add(c, (ok ? "fired:" : "blocked:") + d.key); if (ok) continue; }
                if (onlyInUse(d.key) && !inUse(c)) continue;   // the phone is put away: no "Do not use mobile" / "Sleep" reminder
                try {
                    final NotificationArt.Theme art = NotificationArt.forKey(d.section, d.key);
                    if (soundIdx == 0) {
                        show(c, "nudge-" + d.key, d.title, d.body, false, art, d.section, d.key, actionsFor(d.key), d.vibrate, d.sound);
                    } else {
                        // Several due together: the sound plays once; the others appear quietly now and ring one by one, 25 s apart
                        show(c, "nudge-" + d.key, d.title, d.body, true, art, d.section, d.key, actionsFor(d.key), false, d.sound);
                        final ReminderRules.Due dd = d; final long gapSec = 25L * soundIdx; final Context app = c.getApplicationContext();
                        GAP.schedule(() -> { try { show(app, "nudge-" + dd.key, dd.title, dd.body, false, art, dd.section, dd.key, actionsFor(dd.key), dd.vibrate, dd.sound); } catch (Throwable ignored) { } },
                                gapSec, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    soundIdx++;
                    if (!d.say.isEmpty() && NotificationManagerCompat.from(c).areNotificationsEnabled()) Speaker.say(c, d.say, d.voice, d.lang);
                } catch (Throwable t) { AlarmLog.add(c, "show-failed:" + d.key + ":" + t.getClass().getSimpleName()); }
            }
        }
        // "Not yet" reminders that are due now (or missed by less than an hour)
        org.json.JSONArray sn = snoozes(c), keep = new org.json.JSONArray();
        for (int i = 0; i < sn.length(); i++) {
            JSONObject o = sn.optJSONObject(i); if (o == null) continue;
            long t = o.optLong("t");
            if (t > now) { keep.put(o); continue; }
            if (o.optBoolean("alarm", false)) cancel(c, SNOOZE_TAG + o.optString("key"));   // the "Snoozed" note goes: it rings now
            if (o.optBoolean("alarm", false) && now - t <= 2 && (o.has("cfg") || AlarmService.config(c, o.optString("key")) != null)
                    && AlarmService.startSnoozed(c, o.optString("key"), o.optString("title"), o.optString("body"), o.optInt("count", 1), o.optString("cfg", ""))) continue;
            if (now - t <= MAX_LATE_MIN) {
                String k = o.optString("key");
                String sec = k.startsWith("alarm-rem") ? "alerts" : "routines";   // Reminders alarms: "rem", "rem-<id>"
                show(c, "nudge-" + k, o.optString("title"), o.optString("body"), false, NotificationArt.forKey(sec, k), sec, k, actionsFor(k));
            }
        }
        saveSnoozes(c, keep);
    }


    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { for (NotificationChannel o : nm.getNotificationChannels()) if (o.getId().startsWith("tone_")) nm.deleteNotificationChannel(o.getId()); } catch (Exception ignored) {}
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Eyes, Walk, Water, Eye Exercise, Checks and My Reminders");
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 200, 100, 200, 100, 200});
            ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_REMINDERS_QUIET) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_REMINDERS_QUIET, "Reminders (no vibration)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Reminders whose Vibrate switch is off");
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(false);
            ch.setVibrationPattern(new long[]{0});
            ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_UPDATES) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_UPDATES, "Timers", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Countdowns that update quietly, such as the 20-second eye timer");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    static int idFor(String tag) { return tag == null ? 0 : tag.hashCode(); }

    static void show(Context c, String tag, String title, String body, boolean silent, NotificationArt.Theme theme, String section, String tapKey) {
        show(c, tag, title, body, silent, theme, section, tapKey, new String[0]);
    }

    // ── Buttons on reminders, the same as the website's: Checks "✓ Done" / "Not yet", the others "Turn off this" / "Turn off all" ──
    static final String A_DONE = "done", A_NOT_YET = "not-yet", A_OFF_THIS = "disable-this", A_OFF_ALL = "disable-all";
    static String[] actionsFor(String key) {
        if (key.startsWith("routine-")) return new String[]{A_DONE, A_NOT_YET};
        if ("break".equals(key)) return new String[]{A_EYES_START, A_OFF_THIS};   // Eyes (20-20-20): Start begins the 20-second countdown
        if (key.matches("^(break|walk|drink|eyeex|posture|stretch|breathe|stand)$")) return new String[]{A_OFF_THIS};   // "Turn off all" was dropped: one wrong tap silenced everything
        return new String[0];   // My Reminders: no buttons, as on the website
    }
    private static String actionLabel(String a) {
        switch (a) {
            case A_DONE: return "✓ Done";
            case A_NOT_YET: return "Not yet";
            case A_EYES_START: return "▶ Start";
            case A_OFF_THIS: return "Turn off this";
            default: return "Turn off all";
        }
    }

    static void show(Context c, String tag, String title, String body, boolean silent, NotificationArt.Theme theme, String section, String tapKey, String[] actions) {
        show(c, tag, title, body, silent, theme, section, tapKey, actions, true);
    }

    static void show(Context c, String tag, String title, String body, boolean silent, NotificationArt.Theme theme, String section, String tapKey, String[] actions, boolean vibrate) {
        show(c, tag, title, body, silent, theme, section, tapKey, actions, vibrate, "");
    }

    /** A channel per app tone (Android keeps the sound per channel), with and without vibration.
     *  "tn2_": the sound is named, not numbered (the old "tone_" channels could point at no file after an update: no sound). */
    static final String TONE_CH = "tn2_";
    static String toneChannel(Context c, String tone, boolean vibrate) {
        String id = TONE_CH + tone + (vibrate ? "" : "_nv");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(id) != null) return id;
        NotificationChannel ch = new NotificationChannel(id, ("none".equals(tone) ? "Reminders · no sound" : "Checks · " + tone) + (vibrate ? "" : " (no vibration)"), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Checks notifications with this sound");
        if ("none".equals(tone)) ch.setSound(null, null);   // No sound (vibrate only)
        else ch.setSound(AlarmService.rawTone(c, tone), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(vibrate);
        ch.setVibrationPattern(vibrate ? new long[]{0, 200, 100, 200, 100, 200} : new long[]{0});
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        return id;
    }

    /** The reminder as the app draws it. Closed (`open` false): one line of text with Start and the bell beside it.
     *  Open: the whole text, then Start and the bell on the last line (without Start the bell stays beside the text). */
    private static android.widget.RemoteViews offView(Context c, String title, String body, android.graphics.Bitmap bell, PendingIntent off, PendingIntent start, boolean open, long countMs) {
        android.widget.RemoteViews v = new android.widget.RemoteViews(c.getPackageName(), R.layout.notif_reminder);
        v.setTextViewText(R.id.nr_title, title == null ? "" : title);
        v.setTextViewText(R.id.nr_text, body == null ? "" : body);
        v.setInt(R.id.nr_text, "setMaxLines", open ? 6 : 1);
        boolean counting = countMs > 0;   // Eyes (20-20-20) after Start: the countdown sits where the Start button was
        boolean row = open && (start != null || counting);
        v.setViewVisibility(R.id.nr_row, row ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setViewVisibility(R.id.nr_off_side, row ? android.view.View.GONE : android.view.View.VISIBLE);
        v.setViewVisibility(R.id.nr_start_side, !row && start != null && !counting ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setViewVisibility(R.id.nr_start, start != null && !counting ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setViewVisibility(R.id.nr_count_side, !row && counting ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setViewVisibility(R.id.nr_count, row && counting ? android.view.View.VISIBLE : android.view.View.GONE);
        if (counting) {
            long base = android.os.SystemClock.elapsedRealtime() + countMs;
            for (int id : new int[]{R.id.nr_count_side, R.id.nr_count}) {
                v.setChronometerCountDown(id, true); v.setChronometer(id, base, "%s", true);
            }
        }
        v.setImageViewBitmap(R.id.nr_off, bell); v.setImageViewBitmap(R.id.nr_off_side, bell);
        v.setOnClickPendingIntent(R.id.nr_off, off); v.setOnClickPendingIntent(R.id.nr_off_side, off);
        if (start != null) { v.setOnClickPendingIntent(R.id.nr_start, start); v.setOnClickPendingIntent(R.id.nr_start_side, start); }
        return v;
    }
    /** The small icon beside the app name: the lotus, which Android fills with the notification's section colour. */
    static androidx.core.graphics.drawable.IconCompat smallIcon(Context c) {
        try { return androidx.core.graphics.drawable.IconCompat.createWithBitmap(art("mark", NotificationArt::mark)); }
        catch (Exception e) { return androidx.core.graphics.drawable.IconCompat.createWithResource(c, R.drawable.ic_stat_lotus); }
    }

    static void show(Context c, String tag, String title, String body, boolean silent, NotificationArt.Theme theme, String section, String tapKey, String[] actions, boolean vibrate, String sound) {
        show(c, tag, title, body, silent, theme, section, tapKey, actions, vibrate, sound, 0L);
    }

    static void show(Context c, String tag, String title, String body, boolean silent, NotificationArt.Theme theme, String section, String tapKey, String[] actions, boolean vibrate, String sound, long countMs) {
        ensureChannels(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;
        Intent open = new Intent(c, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KEY, tapKey == null ? "" : tapKey)
            .putExtra(EXTRA_SECTION, section == null ? "" : section);
        PendingIntent pi = PendingIntent.getActivity(c, idFor(tag), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Vibrate switch off: the same sound, without vibration (Android 8+ keeps vibration per channel)
        String channel = silent ? CHANNEL_UPDATES : (sound != null && !sound.isEmpty()) ? toneChannel(c, sound, vibrate) : (vibrate ? CHANNEL_REMINDERS : CHANNEL_REMINDERS_QUIET);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
            .setSmallIcon(smallIcon(c))
            .setContentTitle(title)
            .setContentText(body)
            // Standard Android look (Android 12+ always frames a custom picture): the section logo on the right, the section colour for the app name and buttons
            .setLargeIcon(art("logo" + theme.key(), () -> NotificationArt.logo(theme)))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setColor(theme.deep)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setCategory(silent ? NotificationCompat.CATEGORY_PROGRESS : NotificationCompat.CATEGORY_REMINDER)
            .setPriority(silent ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (!silent && !vibrate) b.setVibrate(new long[]{0});   // Android 7 and older
        boolean checks = tapKey != null && (tapKey.startsWith("routine-") || tapKey.startsWith("alarm-"));
        if (checks) { b.setShowWhen(false); b.setContentTitle(noTime(title)); }   // the time is not repeated on the notification
        java.util.LinkedHashMap<String, PendingIntent> acts = new java.util.LinkedHashMap<>();
        for (String a : actions) {
            Intent ai = new Intent(c, ActionReceiver.class).setAction("com.ayubase6.app.ACTION." + a)
                .putExtra(EXTRA_KEY, tapKey).putExtra(ActionReceiver.EXTRA_ACTION, a).putExtra(ActionReceiver.EXTRA_TAG, tag)
                .putExtra(ActionReceiver.EXTRA_TITLE, title).putExtra(ActionReceiver.EXTRA_BODY, body);
            acts.put(a, PendingIntent.getBroadcast(c, (tag + "|" + a).hashCode(), ai, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        // Care (My Day Plan and Health Breaks): closes by itself after the chosen minutes, when that is switched on
        try { if (theme == NotificationArt.ROUTINES && !silent) { JSONObject ar = loadRules(c); int am = ar == null ? 0 : ar.optInt("autoDismissMin", 0); if (am > 0 && am <= 240) b.setTimeoutAfter(am * 60000L); } } catch (Throwable ignored) { }
        // Posture check: the sitting picture opens under the text (the standard picture style, so its own buttons are normal buttons)
        boolean pic = false;
        if (tapKey != null && tapKey.endsWith("posture")) {
            try {
                android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options(); bo.inSampleSize = 2; bo.inPreferredConfig = Bitmap.Config.RGB_565;   // 480x240: a full-size picture can be too large for the notification and made posting fail
                Bitmap bp = android.graphics.BitmapFactory.decodeResource(c.getResources(), R.drawable.posture_pic, bo);
                if (bp != null) { b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(bp).bigLargeIcon((Bitmap) null).setSummaryText(body)); pic = true; }
            } catch (Exception | OutOfMemoryError e) { pic = false; }
        }
        boolean own = false;
        if (!pic && acts.containsKey(A_OFF_THIS)) {   // "Turn off this" is a bell icon at the bottom right; on Eyes, Start sits on the same line
            try {
                boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                android.graphics.Bitmap bell = art("belloff" + theme.key() + dark, () -> NotificationArt.bellOff(theme, dark));
                PendingIntent off = acts.get(A_OFF_THIS), start = acts.get(A_EYES_START);
                android.widget.RemoteViews small = offView(c, title, body, bell, off, start, false, countMs), big = offView(c, title, body, bell, off, start, true, countMs);
                b.setStyle(new NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(small).setCustomBigContentView(big).setCustomHeadsUpContentView(small);
                b.setLargeIcon((android.graphics.Bitmap) null);   // no picture at the top right: the text uses the full width
                own = true;
            } catch (Exception e) { own = false; }
        }
        if (!own && !pic && tag != null && tag.endsWith("test")) b.setLargeIcon((android.graphics.Bitmap) null);   // the test notification: no big logo, as in the newer notifications
        for (java.util.Map.Entry<String, PendingIntent> e : acts.entrySet()) {
            if (own && (A_OFF_THIS.equals(e.getKey()) || A_EYES_START.equals(e.getKey()))) continue;   // already in the app's own drawing
            b.addAction(new NotificationCompat.Action.Builder(0, actionLabel(e.getKey()), e.getValue()).setAuthenticationRequired(false).build());   // works from the lock screen without unlocking
        }
        try {
            nm.notify(idFor(tag), b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification permission missing");
        } catch (Throwable t) {
            // Anything else (for example a picture too large for the notification) must not stop the reminders after it:
            // post the plain version and go on.
            AlarmLog.add(c, "notify-failed:" + tag + ":" + t.getClass().getSimpleName());
            try {
                b.setStyle(null).setCustomContentView(null).setCustomBigContentView(null).setCustomHeadsUpContentView(null);
                nm.notify(idFor(tag), b.build());
            } catch (Throwable ignored) { }
        }
    }

    // The section-coloured card: light section gradient with a faint lotus, the section logo, dark section-coloured text.
    private static final java.util.Map<String, Bitmap> ART = new java.util.HashMap<>();
    private static synchronized Bitmap art(String k, java.util.function.Supplier<Bitmap> make) {
        Bitmap b = ART.get(k); if (b == null) { b = make.get(); ART.put(k, b); } return b;
    }
    private static RemoteViews cardView(Context c, int layout, NotificationArt.Theme t, String title, String body, int w, int h) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setImageViewBitmap(R.id.notif_bg, art("card" + t.key() + h, () -> NotificationArt.card(t, w, h)));
        v.setImageViewBitmap(R.id.notif_logo, art("logo" + t.key(), () -> NotificationArt.logo(t)));
        v.setTextViewText(R.id.notif_title, title);
        v.setTextColor(R.id.notif_title, t.title);
        v.setTextViewText(R.id.notif_body, body);
        v.setTextColor(R.id.notif_body, t.body);
        return v;
    }

    // ── What a notification button does, on the phone, even with the app closed ──
    private static final String PREF_SNOOZE = "snoozes", PREF_PENDING = "pending";
    static synchronized org.json.JSONArray snoozes(Context c) {
        try { return new org.json.JSONArray(prefs(c).getString(PREF_SNOOZE, "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }
    static synchronized void saveSnoozes(Context c, org.json.JSONArray a) { prefs(c).edit().putString(PREF_SNOOZE, a.toString()).apply(); }

    /** Actions the app itself must also apply (tick the activity, turn its switch off) when it is next open. */
    static synchronized org.json.JSONArray takePending(Context c) {
        org.json.JSONArray a;
        try { a = new org.json.JSONArray(prefs(c).getString(PREF_PENDING, "[]")); } catch (Exception e) { a = new org.json.JSONArray(); }
        prefs(c).edit().remove(PREF_PENDING).apply();
        return a;
    }
    private static synchronized void addPending(Context c, String key, String action) {
        org.json.JSONArray a;
        try { a = new org.json.JSONArray(prefs(c).getString(PREF_PENDING, "[]")); } catch (Exception e) { a = new org.json.JSONArray(); }
        try {
            long now = System.currentTimeMillis();
            a.put(new JSONObject().put("key", key).put("action", action).put("at", now).put("day", local(now / 60000L).day));
        } catch (Exception ignored) {}
        org.json.JSONArray keep = new org.json.JSONArray();   // the last 50 are plenty
        for (int i = Math.max(0, a.length() - 50); i < a.length(); i++) keep.put(a.opt(i));
        prefs(c).edit().putString(PREF_PENDING, keep.toString()).apply();
    }

    static void applyAction(Context c, String key, String action, String tag, String title, String body) {
        if (key == null) key = "";
        JSONObject rules = loadRules(c);
        long now = System.currentTimeMillis() / 60000L;
        String today = local(now).day;
        try {
            if (A_DONE.equals(action) && key.startsWith("routine-")) {
                mutate(rules, key, action, today);
                dropSnooze(c, "routine-" + key.replaceFirst("^routine-(end-|again-)?", ""));
                addPending(c, key, action);
            } else if (A_NOT_YET.equals(action) && key.startsWith("routine-")) {
                String item = key.replaceFirst("^routine-(end-|again-)?", "");
                org.json.JSONArray sn = snoozes(c), keep = new org.json.JSONArray();
                for (int i = 0; i < sn.length(); i++) { JSONObject o = sn.optJSONObject(i); if (o != null && !("routine-" + item).equals(o.optString("key"))) keep.put(o); }
                String b = body == null ? "" : body;
                keep.put(new JSONObject().put("t", now + 15).put("key", "routine-" + item)
                    .put("title", title == null ? "" : title).put("body", b.startsWith("Still to do: ") ? b : "Still to do: " + b));
                saveSnoozes(c, keep);
            } else if ((A_OFF_THIS.equals(action) || A_OFF_ALL.equals(action)) && rules != null) {
                mutate(rules, key, action, today);
                addPending(c, key, action);
            }
        } catch (Exception e) { Log.w(TAG, "action " + action + " failed", e); }
        if (rules != null) saveRules(c, rules);
        if (tag != null) cancel(c, tag);
        reschedule(c);
    }
    /** The change a button makes to the stored rules (Done: ticked for today; Turn off: that reminder, or all Reminders, off). */
    private static void mutate(JSONObject rules, String key, String action, String today) throws org.json.JSONException {
        if (rules == null) return;
        if (A_DONE.equals(action) && key.startsWith("routine-")) {
            String item = key.replaceFirst("^routine-(end-|again-)?", "");
            JSONObject ck = rules.optJSONObject("checks");
            if (ck == null) return;
            if (!today.equals(ck.optString("doneDay"))) { ck.put("doneDay", today); ck.put("done", new org.json.JSONArray()); }
            org.json.JSONArray done = ck.optJSONArray("done");
            if (done == null) { done = new org.json.JSONArray(); ck.put("done", done); }
            for (int i = 0; i < done.length(); i++) if (item.equals(done.optString(i))) return;
            done.put(item);
        } else if (A_OFF_THIS.equals(action)) {
            if ("eyeex".equals(key)) { rules.put("exercise", JSONObject.NULL); return; }
            org.json.JSONArray r = rules.optJSONArray("reminders"), keep = new org.json.JSONArray();
            if (r != null) for (int i = 0; i < r.length(); i++) { JSONObject o = r.optJSONObject(i); if (o != null && !key.equals(o.optString("key"))) keep.put(o); }
            rules.put("reminders", keep);
        } else if (A_OFF_ALL.equals(action)) {
            // the Reminders switch off: Eyes, Walk, Water, Eye Exercise and My Reminders; Checks has its own switch
            rules.put("reminders", new org.json.JSONArray()); rules.put("exercise", JSONObject.NULL); rules.put("custom", new org.json.JSONArray());
        }
    }

    /**
     * New rules from the app while button actions are still waiting for it: keep those actions in force,
     * so the app's older state cannot switch a reminder back on before it has applied them.
     */
    static synchronized void keepPendingInForce(Context c, JSONObject rules) {
        if (rules == null) return;
        org.json.JSONArray a;
        try { a = new org.json.JSONArray(prefs(c).getString(PREF_PENDING, "[]")); } catch (Exception e) { return; }
        String today = local(System.currentTimeMillis() / 60000L).day;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            if (A_DONE.equals(o.optString("action")) && !today.equals(o.optString("day"))) continue;
            try { mutate(rules, o.optString("key"), o.optString("action"), today); } catch (Exception ignored) {}
        }
    }

    /** Snooze on a ringing alarm: ring again after `minutes`; `count` snoozes used so far. A quiet note says when it rings next. */
    static final String SNOOZE_TAG = "snz-", A_SNOOZE_OFF = "snooze-off";
    static void addAlarmSnooze(Context c, String key, String title, String body, int count, int minutes, JSONObject cfg, String section) {
        long at = System.currentTimeMillis() / 60000L + minutes;
        try {
            org.json.JSONArray sn = snoozes(c), keep = new org.json.JSONArray();
            for (int i = 0; i < sn.length(); i++) { JSONObject o = sn.optJSONObject(i); if (o != null && !key.equals(o.optString("key"))) keep.put(o); }
            JSONObject e = new JSONObject().put("t", at).put("key", key)
                .put("title", title).put("body", body).put("alarm", true).put("count", count);
            if (cfg != null && cfg.length() > 0) e.put("cfg", cfg.toString());
            keep.put(e);
            saveSnoozes(c, keep);
        } catch (Exception ignored) {}
        if ("alarm-wake".equals(key)) addWakeShift(c, minutes);   // the day starts later: today's checks move with it
        reschedule(c);
        showSnoozed(c, key, title, at * 60000L, section);
    }

    /** "Snoozed · rings at 7:10 AM", with a countdown; stays until it rings or the alarm is stopped. Tapping it opens that alarm. */
    static void showSnoozed(Context c, String key, String title, long atMs, String section) {
        ensureChannels(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;
        String tag = SNOOZE_TAG + key;
        NotificationArt.Theme theme = NotificationArt.forSection(section);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_SECTION, section == null ? "" : section);
        PendingIntent pi = PendingIntent.getActivity(c, idFor(tag), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent off = new Intent(c, ActionReceiver.class).setAction("com.ayubase6.app.ACTION." + A_SNOOZE_OFF)
            .putExtra(EXTRA_KEY, key).putExtra(ActionReceiver.EXTRA_ACTION, A_SNOOZE_OFF).putExtra(ActionReceiver.EXTRA_TAG, tag);
        PendingIntent offPi = PendingIntent.getBroadcast(c, (tag + "|off").hashCode(), off, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String when = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(atMs));
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_UPDATES)
            .setSmallIcon(smallIcon(c))
            .setContentTitle("⏰ " + noTime(title))
            .setContentText("Snoozed · rings at " + when)
            .setLargeIcon(art("logo" + theme.key(), () -> NotificationArt.logo(theme)))
            .setColor(theme.deep)
            .setContentIntent(pi)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(atMs).setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop alarm", offPi);
        try { nm.notify(idFor(tag), b.build()); } catch (SecurityException ignored) {}
    }

    /** The alarm was switched off or deleted in the app, or "Stop alarm" was tapped on its note: it does not ring again. */
    static void cancelAlarmSnooze(Context c, String key) {
        org.json.JSONArray sn = snoozes(c), keep = new org.json.JSONArray();
        boolean had = false;
        for (int i = 0; i < sn.length(); i++) {
            JSONObject o = sn.optJSONObject(i); if (o == null) continue;
            if (o.optBoolean("alarm", false) && key.equals(o.optString("key"))) { had = true; continue; }
            keep.put(o);
        }
        cancel(c, SNOOZE_TAG + key);
        if (had) { saveSnoozes(c, keep); reschedule(c); }
    }

    /** Snoozed alarms and the time each rings next: {"alarm-<id>": ms}. */
    static JSONObject snoozedAlarms(Context c) {
        JSONObject r = new JSONObject();
        long now = System.currentTimeMillis() / 60000L;
        org.json.JSONArray sn = snoozes(c);
        for (int i = 0; i < sn.length(); i++) {
            JSONObject o = sn.optJSONObject(i);
            if (o == null || !o.optBoolean("alarm", false) || o.optLong("t") <= now) continue;
            try { r.put(o.optString("key"), o.optLong("t") * 60000L); } catch (Exception ignored) {}
        }
        return r;
    }

    /** "Gym · 7:15 AM" → "Gym": the notification itself says when. */
    static String noTime(String title) {
        return title == null ? "" : title.replaceFirst("\\s*·\\s*\\d{1,2}:\\d{2}(\\s*[AaPp][Mm])?\\s*$", "");
    }

    private static void dropSnooze(Context c, String key) {
        org.json.JSONArray sn = snoozes(c), keep = new org.json.JSONArray();
        for (int i = 0; i < sn.length(); i++) { JSONObject o = sn.optJSONObject(i); if (o != null && !key.equals(o.optString("key"))) keep.put(o); }
        saveSnoozes(c, keep);
    }

    static void cancel(Context c, String tag) { NotificationManagerCompat.from(c).cancel(idFor(tag)); }

    // ── Eyes (20-20-20): when the 20 seconds are over the same notification says so, quietly ──
    static final String A_EYES_DONE = "com.ayubase6.app.EYES_DONE", A_EYES_START = "eyes-start";
    /** Start was tapped: the same notification now counts the 20 seconds down, quietly, then says they are done. */
    static void eyesStart(Context c, String tag, String title) {
        ensureChannels(c);
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;
        NotificationArt.Theme theme = NotificationArt.ROUTINES;   // Eyes belongs to Care
        // The same notification as before (title, text, the bell) with the countdown where Start was
        show(c, tag, title == null || title.isEmpty() ? "Eyes" : title, "Look 20 feet away until the time runs out.", true, theme, "alerts", "break",
            new String[]{A_OFF_THIS}, false, "", 20000L);
        eyesDone(c, tag, title, theme, "alerts");
    }
    private static void eyesDone(Context c, String tag, String title, NotificationArt.Theme theme, String section) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, ActionReceiver.class).setAction(A_EYES_DONE)
                .putExtra(ActionReceiver.EXTRA_TAG, tag).putExtra(ActionReceiver.EXTRA_TITLE, title).putExtra(EXTRA_SECTION, section == null ? "" : section);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7050, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + 20500L;
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {}
    }
    /** The 20 seconds are over (only if the notification is still there). */
    static void eyesFinished(Context c, String tag, String title, String section) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean there = false;
            for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == idFor(tag)) there = true;
            if (!there) return;
            show(c, tag, title, "✓ 20 seconds done. Back to your screen.", true, NotificationArt.forKey(section, "break"), section, "break");
        } catch (Exception ignored) {}
    }
}
