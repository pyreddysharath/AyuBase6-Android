package com.ayubase6.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * After an app update Android may turn "Alarms & reminders" or full-screen alarms off again.
 * Every time the app is opened the two settings are remembered. When an update lands and one that was on is now off,
 * while an alarm or reminder is set, a single note is posted if the app has not been opened within WAIT_MIN minutes
 * (in the colour of the section; opening the app shows the Allow card at once). Opening the app cancels it.
 */
public class PermWatch extends BroadcastReceiver {
    static final String PREFS = "ayu_permwatch", TAG = "perm-watch", ACTION = "com.ayubase6.app.PERM_WATCH";
    static final int WAIT_MIN = 5;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 7602, new Intent(c, PermWatch.class).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean fullScreenOn(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm == null || nm.canUseFullScreenIntent();
    }

    /** The app is open: remember how the two settings are now, and drop the waiting note and its timer. */
    static void noteOpen(Context c) {
        try {
            sp(c).edit().putBoolean("exactOn", ReminderScheduler.canExact(c)).putBoolean("fsOn", fullScreenOn(c)).putBoolean("known", true).apply();
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi(c));
            ReminderScheduler.cancel(c, TAG);
        } catch (Exception ignored) {}
    }

    private static boolean hasAlarms(Context c) {
        JSONObject r = ReminderScheduler.loadRules(c);
        JSONArray a = r == null ? null : r.optJSONArray("alarms");
        return a != null && a.length() > 0;
    }

    private static boolean hasAnything(Context c) { return hasAlarms(c) || ReminderScheduler.nextAt(c) > 0; }

    /** What was on before and is off now (and matters): bit 1 = Alarms & reminders, bit 2 = full-screen alarms. */
    private static int lost(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("known", false)) return 0;   // never opened since this feature: nothing to compare with
        int m = 0;
        if (p.getBoolean("exactOn", false) && !ReminderScheduler.canExact(c) && hasAnything(c)) m |= 1;
        if (p.getBoolean("fsOn", false) && !fullScreenOn(c) && hasAlarms(c)) m |= 2;
        return m;
    }

    /** After an update (or restart): if a setting was lost, post the note in WAIT_MIN minutes unless the app is opened first. */
    static void afterUpdate(Context c) {
        try {
            if (lost(c) == 0) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + WAIT_MIN * 60000L;
            // inexact on purpose: the exact-alarm permission is what may have been lost
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            else am.set(AlarmManager.RTC_WAKEUP, at, pi(c));
        } catch (Exception ignored) {}
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        try {
            if (AnnouncementCheck.appVisible) return;
            int m = lost(c);
            if (m == 0) return;
            boolean alarms = hasAlarms(c);
            String what = m == 3 ? "“Alarms & reminders” and full-screen alarms are off"
                : m == 1 ? "“Alarms & reminders” is off" : "Full-screen alarms are off";
            String body = what + " after the update. Tap to open AyuBase6 and allow it, so your " + (alarms ? "alarms" : "reminders") + " arrive on time.";
            ReminderScheduler.show(c, TAG, "Allow alarms in AyuBase6", body, false,
                alarms ? NotificationArt.REMINDERS : NotificationArt.ROUTINES, "perm", "perm", new String[0], false, "");
        } catch (Exception ignored) {}
    }
}
