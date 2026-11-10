package com.ayubase6.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** A short record of what the phone did with alarms (set, fired, rang, refused), shown in Help's notification check. */
final class AlarmLog {
    private static final String PREFS = "ayu_alarm_log", KEY = "log";
    private static final int MAX = 25;
    private AlarmLog() {}

    static synchronized void add(Context c, String what) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString(KEY, "[]")), out = new JSONArray();
            int from = Math.max(0, old.length() - (MAX - 1));
            for (int i = from; i < old.length(); i++) out.put(old.get(i));
            out.put(new JSONObject().put("t", System.currentTimeMillis()).put("m", what));
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Exception ignored) {}
    }

    static JSONArray get(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
}
