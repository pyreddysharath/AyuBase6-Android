package com.ayubase6.app;

import android.content.Context;

/** How the text of a reminder notification is drawn: "panel" (a soft section-colour panel behind the text, the default),
 *  "bar" (a section-colour bar beside it) or "title" (only the title in the section colour). The header line stays Android's. */
final class NotifLook {
    static final String PREFS = "ayu_look";
    private NotifLook() {}
    static String get(Context c) {
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("look", "panel");
        return "bar".equals(v) || "title".equals(v) ? v : "panel";
    }
    static void set(Context c, String v) {
        if (!"panel".equals(v) && !"bar".equals(v) && !"title".equals(v)) v = "panel";
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("look", v).apply();
    }
}
