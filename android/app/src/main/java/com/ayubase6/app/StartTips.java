package com.ayubase6.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The one-line tip on the start screen. The list lives in config/tips.json on GitHub; the last good copy is kept on the phone,
 * so it works offline. Each launch shows one tip from the kept copy and, when online, fetches the latest for next time.
 * An empty or unreadable list (no tips) means no line is shown.
 */
final class StartTips {
    static final String URL_JSON = "https://raw.githubusercontent.com/pyreddysharath/AyuBase6.com/main/config/tips.json";
    private static final String PREFS = "ayu_tips", KEY = "tips";

    private StartTips() {}

    /** A random tip from the kept copy, or null when there is none. */
    static String pick(Context c) {
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            if (a.length() == 0) return null;
            String s = a.optString(new java.util.Random().nextInt(a.length()), "").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) { return null; }
    }

    /** Gets the latest list in the background; a failed fetch keeps the old copy. */
    static void refresh(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(URL_JSON + "?t=" + (System.currentTimeMillis() / 300000L)).openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000); h.setUseCaches(false);
                if (h.getResponseCode() != 200) return;
                String body;
                try (InputStream in = h.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0 && out.size() < 100000) out.write(b, 0, n);
                    body = out.toString("UTF-8");
                }
                JSONArray src;
                try { src = new JSONObject(body.trim()).optJSONArray("tips"); } catch (Exception bad) { src = null; }
                if (src == null && body.trim().isEmpty()) src = new JSONArray();   // a blank file: no tips
                if (src == null) return;                                          // unreadable: keep the old copy
                JSONArray keep = new JSONArray();
                for (int i = 0; i < src.length() && keep.length() < 200; i++) {
                    String s = src.optString(i, "").trim();
                    if (!s.isEmpty() && s.length() <= 140) keep.put(s);
                }
                SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putString(KEY, keep.toString()).apply();
            } catch (Exception ignored) {}
        }, "ayu-tips").start();
    }
}
