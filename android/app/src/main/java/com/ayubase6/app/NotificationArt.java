package com.ayubase6.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Section colours and pictures for notifications, drawn the same way as the web app's nudgeArt():
 * a light section-coloured card with a large faint lotus, and a solid section-coloured logo with a white lotus.
 * Reminders = gold, Checks = amber; anything else (e.g. the Help test) uses the colour of the section it came from.
 */
final class NotificationArt {
    private NotificationArt() {}

    static final class Theme {
        final int g0, g1, big, deep, ic0, ic1, title, body;
        Theme(int g0, int g1, int big, int deep, int ic0, int ic1) {
            this.g0 = g0; this.g1 = g1; this.big = big; this.deep = deep; this.ic0 = ic0; this.ic1 = ic1;
            this.title = dark(deep, .45); this.body = dark(deep, .62);
        }
        String key() { return Integer.toHexString(ic0); }
    }

    // Same values as NUDGE_THEMES in the web app.
    static final Theme REMINDERS = new Theme(c("#fdf8ea"), c("#fbf1d6"), c("#f0d78c"), c("#c9921a"), c("#cf9a24"), c("#a87708"));
    static final Theme ROUTINES = new Theme(c("#fff1f3"), c("#fcd5dc"), c("#f4a0b0"), c("#e8607a"), c("#e8607a"), c("#c94862"));   // Care: coral pink

    static final Theme MIND = new Theme(c("#f3f1ff"), c("#e5dfff"), c("#a5b4fc"), c("#6250b3"), c("#6250b3"), c("#4a3a96"));   // Mind: indigo

    static final int[] LOGO_PETALS = {0xFF60A5FA, 0xFF86EFAC, 0xFFA5B4FC, 0xFF67E8F9, 0xFFFB7A98, 0xFFF4C24E};   // the logo's six petals, left to right
    static final Theme LOGO = new Theme(c("#ffffff"), c("#eef2ff"), c("#f4c24e"), c("#6d7cf0"), c("#ffffff"), c("#eef2ff"));   // Test notification: white card, six-colour lotus

    static Theme forSection(String section) { return "routines".equals(section) ? ROUTINES : REMINDERS; }
    /** Care (coral pink) holds My Day Plan and the Health Breaks (Eyes, Walk, Water, Posture, Stretch, Breathing, Eye Exercise); Reminders (gold) the rest. */
    static Theme forKey(String section, String key) {
        if ("mind".equals(key)) return MIND;
        if ("test".equals(key)) return LOGO;
        if (key != null && key.matches("break|walk|drink|posture|stretch|breathe|eyeex")) return ROUTINES;
        return forSection(section);
    }

    private static boolean night(android.content.Context c) {
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    /** A medium tone of the section colour for dark notifications: clearly coloured, not near-white. */
    private static int darkTone(Theme t) { return mix(t == LOGO ? t.deep : t.ic0, .3); }
    /** The title colour: the section's dark shade on a light notification, a medium tone of it in dark mode. */
    static int titleColor(android.content.Context c, Theme t) { return night(c) ? darkTone(t) : t.title; }
    /** The notification colour (the app name and small icon): the section colour, a medium tone of it in dark mode so the system does not wash it out. */
    static int accentColor(android.content.Context c, Theme t) { return night(c) ? darkTone(t) : t.deep; }

    /** A theme from one section colour, mixed like the web app's sectionTheme(). */
    static Theme fromColor(int col) {
        if (col == REMINDERS.ic0 || col == REMINDERS.deep) return REMINDERS;
        if (col == ROUTINES.ic0 || col == ROUTINES.deep) return ROUTINES;
        return new Theme(mix(col, .92), mix(col, .78), mix(col, .4), dark(col, .1), col, dark(col, .22));
    }

    private static int c(String hex) { return Color.parseColor(hex); }
    private static int mix(int col, double t) {
        return Color.rgb((int) Math.round(Color.red(col) + (255 - Color.red(col)) * t),
            (int) Math.round(Color.green(col) + (255 - Color.green(col)) * t),
            (int) Math.round(Color.blue(col) + (255 - Color.blue(col)) * t));
    }
    private static int dark(int col, double t) {
        return Color.rgb((int) Math.round(Color.red(col) * (1 - t)), (int) Math.round(Color.green(col) * (1 - t)),
            (int) Math.round(Color.blue(col) * (1 - t)));
    }

    // The six-petal lotus (NUDGE_LOTUS_PETAL); (cx, cy) is where the petals meet.
    private static Path petal() {
        Path p = new Path();
        p.moveTo(0, -1);
        p.cubicTo(-3.01f, -5.00f, -9.40f, -10.50f, 0f, -21.00f);
        p.cubicTo(9.40f, -10.50f, 3.01f, -5.00f, 0, -1);
        p.close();
        return p;
    }
    private static void lotus(Canvas cv, float cx, float cy, float scale, int fill, float alpha) { lotus(cv, cx, cy, scale, new int[]{fill, fill, fill, fill, fill, fill}, alpha); }
    private static void lotus(Canvas cv, float cx, float cy, float scale, int[] fills, float alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path p = petal();
        int[] angles = {-75, -45, -15, 15, 45, 75};
        for (int i = 0; i < 6; i++) {
            int a = angles[i];
            paint.setColor(fills[i]); paint.setAlpha(Math.round(alpha * 255));
            cv.save(); cv.translate(cx, cy); cv.rotate(a); cv.scale(scale, scale); cv.drawPath(p, paint); cv.restore();
        }
    }

    /** Solid section-coloured logo with a white lotus (the web app's notification icon), rounded. */
    /** "Turn off this": a crossed bell on a soft patch of the section's colour. */
    static Bitmap bellOff(Theme t, boolean dark) {
        int s = 144;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int ink = dark ? blend(t.deep, Color.WHITE, 0.55f) : t.deep;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(dark ? 70 : 38, Color.red(ink), Color.green(ink), Color.blue(ink)));
        cv.drawCircle(s / 2f, s / 2f, s / 2f, bg);
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG);
        w.setColor(ink); w.setStyle(Paint.Style.STROKE); w.setStrokeWidth(2f); w.setStrokeCap(Paint.Cap.ROUND); w.setStrokeJoin(Paint.Join.ROUND);
        Path p = new Path();   // on a 24 x 24 grid
        p.moveTo(9.5f, 4.7f); p.cubicTo(11.2f, 3.9f, 13.6f, 3.9f, 15.4f, 5.3f); p.cubicTo(17.1f, 6.5f, 18f, 8.2f, 18f, 10.2f); p.cubicTo(18f, 12.5f, 18.5f, 14.1f, 19.2f, 15.2f);
        p.moveTo(17f, 17f); p.lineTo(4f, 17f); p.cubicTo(5.5f, 15.5f, 6f, 13.5f, 6f, 10.2f); p.cubicTo(6f, 9.4f, 6.15f, 8.7f, 6.4f, 8f);
        p.moveTo(10f, 20f); p.cubicTo(10f, 22.6f, 14f, 22.6f, 14f, 20f);
        p.moveTo(4f, 4f); p.lineTo(20f, 20f);
        cv.save(); cv.translate(s * 0.21f, s * 0.2f); cv.scale(s * 0.58f / 24f, s * 0.58f / 24f);
        cv.drawPath(p, w); cv.restore();
        return b;
    }
    private static int blend(int a, int b, float k) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * k), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * k), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    /** An alarm clock in the section's colour (the note before an alarm rings). */
    static Bitmap alarm(Theme t) {
        int s = 192;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, s, s, t.ic0, t.ic1, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(0, 0, s, s), 44, 44, bg);
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG);
        w.setColor(Color.WHITE); w.setStyle(Paint.Style.STROKE); w.setStrokeWidth(12); w.setStrokeCap(Paint.Cap.ROUND); w.setStrokeJoin(Paint.Join.ROUND);
        cv.drawCircle(96, 104, 50, w);                 // the face
        cv.drawLine(96, 104, 96, 74, w);               // hands
        cv.drawLine(96, 104, 118, 116, w);
        cv.drawLine(42, 58, 64, 40, w);                // bells
        cv.drawLine(150, 58, 128, 40, w);
        cv.drawLine(62, 146, 52, 160, w);              // feet
        cv.drawLine(130, 146, 140, 160, w);
        return b;
    }

    /** The lotus alone, as the small icon beside the app name: Android fills it with the section colour of the notification. */
    static Bitmap mark() {
        int s = 96;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        lotus(new Canvas(b), 48, 76, 2.35f, Color.WHITE, 1f);
        return b;
    }

    static Bitmap logo(Theme t) {
        int s = 192;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, s, s, t.ic0, t.ic1, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(0, 0, s, s), 44, 44, bg);
        if (t == LOGO) lotus(cv, 96, 142, 3.7f, LOGO_PETALS, 1f); else lotus(cv, 96, 142, 3.7f, Color.WHITE, .96f);
        return b;
    }

    /** Light section-coloured background with a large faint lotus (the web app's notification banner). */
    static Bitmap card(Theme t, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h, t.g0, t.g1, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);   // square edges: the colour fills the whole notification area, edge to edge
        float k = h / 360f;
        if (t == LOGO) { lotus(cv, w - 250 * k, h * .93f, 14.5f * k, LOGO_PETALS, .55f); lotus(cv, w * .42f, h, 6.5f * k, 0xFF94A3B8, .22f); return b; }
        lotus(cv, w - 250 * k, h * .93f, 14.5f * k, t.big, .55f);
        lotus(cv, w * .42f, h, 6.5f * k, t.deep, .14f);
        return b;
    }
}
