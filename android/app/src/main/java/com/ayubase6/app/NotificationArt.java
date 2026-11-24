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
 * Reminders = lime (Alerts), Care = sky blue, Mind = indigo-blue; anything else (e.g. the Help test) uses the colour of the section it came from.
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
    static final Theme REMINDERS = new Theme(c("#f8faf3"), c("#eaf2d5"), c("#bee068"), c("#799e1b"), c("#8db529"), c("#73961b"));
    static final Theme ROUTINES = new Theme(c("#f2fafe"), c("#d8eff9"), c("#a6d9ee"), c("#41aad4"), c("#41aad4"), c("#3c85a2"));   // Care: sky blue

    static final Theme MIND = new Theme(c("#f1f4ff"), c("#dfe5ff"), c("#b4d0ed"), c("#35489b"), c("#35489b"), c("#2f45a2"));   // Mind: indigo-blue

    static final int[] LOGO_PETALS = {0xFF5aa6dc, 0xFF4c9be4, 0xFF4590c8, 0xFF86b84a, 0xFF8cbd4c};   // the logo: drop, left and right petal, two leaves
    static final Theme LOGO = new Theme(c("#ffffff"), c("#f1f7fc"), c("#b4d0ed"), c("#3982c3"), c("#ffffff"), c("#f1f7fc"));   // Test notification: white card, the logo in its own colours

    static Theme forSection(String section) { return "routines".equals(section) ? ROUTINES : REMINDERS; }
    /** Care (coral pink) holds My Day Plan and the Health Breaks (Eyes, Walk, Water, Posture, Stretch, Breathing, Eye Exercise); Reminders (lime) the rest. */
    static Theme forKey(String section, String key) {
        if ("mind".equals(key)) return MIND;
        if ("test".equals(key)) return LOGO;
        String bk = key == null ? "" : key.replaceFirst("^(preview[-:]|nudge-)+", "");   // a preview or scheduled key still means the same Health Break
        if (bk.matches("break|walk|drink|posture|stretch|breathe|eyeex|stand|break-ex")) return ROUTINES;
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

    // The lotus-and-drop mark (5 shapes: drop, two petals, two leaves), x from the centre, y from the bottom; box 244.2 x 144.5.
    private static final float[][] MARK = {
        {-0.1f,-143.5f,-1.1f,-142.5f,-4.4f,-134.0f,-7.2f,-128.4f,-18.1f,-110.0f,-22.6f,-101.3f,-25.5f,-93.5f,-26.6f,-84.7f,-25.6f,-77.2f,-24.9f,-74.8f,-22.6f,-70.0f,-19.9f,-66.4f,-15.7f,-62.4f,-11.7f,-59.8f,-4.7f,-57.3f,-1.4f,-56.8f,3.9f,-57.1f,10.3f,-59.1f,14.3f,-61.3f,17.3f,-63.8f,20.9f,-67.7f,23.4f,-71.7f,25.5f,-78.0f,26.3f,-83.3f,25.6f,-91.8f,23.1f,-99.8f,18.2f,-109.5f,7.4f,-127.7f,4.9f,-132.7f,0.7f,-142.8f},
        {-120.9f,-68.2f,-121.1f,-67.2f,-120.6f,-64.7f,-118.6f,-57.8f,-116.0f,-50.7f,-111.1f,-40.5f,-104.8f,-30.7f,-99.7f,-24.4f,-91.4f,-16.7f,-84.4f,-11.7f,-75.7f,-7.2f,-67.9f,-4.2f,-58.9f,-1.9f,-47.7f,-1.0f,-42.4f,-1.1f,-34.6f,-1.9f,-25.1f,-4.2f,-17.4f,-7.0f,-8.4f,-11.7f,-5.1f,-13.9f,-3.9f,-15.2f,-4.1f,-16.3f,-5.6f,-16.9f,-19.4f,-17.7f,-32.4f,-20.5f,-38.1f,-22.5f,-43.2f,-24.8f,-48.6f,-27.8f,-57.8f,-34.8f,-61.5f,-38.3f,-65.5f,-42.7f,-72.0f,-51.8f,-77.8f,-63.5f,-79.7f,-65.1f,-84.6f,-67.1f,-91.9f,-69.1f,-101.1f,-70.2f,-107.1f,-70.3f,-114.7f,-69.9f,-119.5f,-69.2f},
        {120.9f,-68.2f,120.3f,-68.8f,119.4f,-69.2f,113.6f,-69.9f,106.3f,-70.3f,98.8f,-70.1f,92.4f,-69.2f,84.6f,-67.0f,80.1f,-65.1f,78.9f,-64.3f,78.0f,-63.3f,72.4f,-52.5f,66.4f,-44.0f,58.6f,-35.8f,51.1f,-29.8f,43.6f,-25.2f,37.9f,-22.7f,32.3f,-20.6f,19.1f,-17.7f,13.0f,-17.3f,12.4f,-16.9f,12.4f,-16.3f,13.3f,-15.3f,17.2f,-12.7f,18.4f,-11.6f,20.1f,-8.0f,19.9f,-6.5f,20.3f,-6.1f,24.9f,-4.2f,35.1f,-1.9f,47.6f,-1.2f,58.1f,-2.0f,69.3f,-4.9f,74.1f,-6.7f,79.6f,-9.2f,87.6f,-14.1f,94.6f,-19.5f,101.5f,-26.5f,106.9f,-33.5f,111.9f,-41.7f,116.6f,-52.0f,119.4f,-60.5f,121.1f,-66.8f},
        {-81.6f,-108.2f,-82.1f,-107.1f,-82.2f,-105.2f,-81.6f,-96.8f,-80.5f,-89.3f,-78.1f,-79.5f,-75.5f,-71.5f,-72.9f,-65.5f,-67.9f,-56.5f,-63.6f,-50.3f,-57.2f,-43.0f,-48.6f,-35.9f,-44.4f,-33.1f,-39.6f,-30.4f,-29.1f,-26.0f,-20.4f,-24.0f,-17.8f,-23.9f,-16.8f,-24.3f,-16.9f,-25.4f,-18.7f,-27.9f,-18.6f,-28.4f,-17.8f,-28.4f,-4.9f,-19.1f,6.8f,-11.8f,12.8f,-9.0f,15.3f,-8.5f,18.1f,-7.4f,19.5f,-7.5f,19.6f,-8.1f,18.6f,-10.7f,17.5f,-12.3f,10.9f,-17.1f,4.2f,-20.6f,-2.9f,-25.9f,-6.8f,-29.6f,-10.9f,-34.8f,-13.9f,-39.7f,-16.4f,-45.2f,-20.3f,-56.0f,-26.3f,-62.8f,-30.7f,-70.7f,-32.0f,-75.3f,-33.2f,-82.3f,-34.0f,-83.8f,-35.6f,-85.6f,-44.8f,-93.6f,-50.4f,-97.5f,-56.2f,-100.7f,-61.4f,-103.1f,-73.9f,-107.3f,-76.7f,-108.1f,-80.1f,-108.5f},
        {81.4f,-108.1f,80.6f,-108.4f,78.4f,-108.3f,73.9f,-107.3f,66.6f,-105.0f,55.9f,-100.5f,50.4f,-97.5f,44.4f,-93.4f,36.0f,-85.8f,33.7f,-83.2f,32.7f,-81.5f,31.3f,-73.2f,28.9f,-67.2f,25.3f,-61.7f,20.2f,-56.3f,17.0f,-46.5f,12.6f,-37.3f,8.4f,-31.3f,4.2f,-27.0f,3.8f,-26.2f,4.1f,-25.7f,5.9f,-25.4f,9.8f,-26.3f,17.4f,-30.4f,22.1f,-33.6f,23.3f,-34.1f,23.7f,-33.7f,23.1f,-32.6f,19.1f,-28.2f,16.1f,-25.4f,15.7f,-24.7f,15.7f,-24.1f,16.8f,-23.8f,19.9f,-24.0f,29.4f,-26.2f,35.9f,-28.7f,44.8f,-33.4f,50.9f,-37.7f,55.3f,-41.3f,58.6f,-44.7f,63.8f,-50.7f,68.6f,-57.5f,73.4f,-66.5f,76.0f,-72.7f,78.5f,-80.7f,80.6f,-90.3f,81.7f,-100.2f,81.9f,-106.3f}
    };
    private static final float MARK_W = 244.2f;
    private static void lotus(Canvas cv, float cx, float cy, float scale, int fill, float alpha) { lotus(cv, cx, cy, scale, new int[]{fill, fill, fill, fill, fill}, alpha); }
    /** Draws the mark with its bottom centre at (cx, cy); scale 1 = the old lotus size (about 40 wide). */
    private static void lotus(Canvas cv, float cx, float cy, float scale, int[] fills, float alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float k = 40.6f * scale / MARK_W;
        for (int i = 0; i < MARK.length; i++) {
            float[] q = MARK[i];
            Path p = new Path();
            p.moveTo(cx + q[0] * k, cy + q[1] * k);
            for (int j = 2; j < q.length; j += 2) p.lineTo(cx + q[j] * k, cy + q[j + 1] * k);
            p.close();
            paint.setColor(fills[i]); paint.setAlpha(Math.round(alpha * 255));
            cv.drawPath(p, paint);
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
        if (t == LOGO) { lotus(cv, w - 240 * k, h * .95f, 13.2f * k, LOGO_PETALS, .55f); lotus(cv, w * .42f, h, 6.5f * k, 0xFF3982c3, .14f); return b; }
        lotus(cv, w - 240 * k, h * .95f, 13.2f * k, t.big, .55f);
        lotus(cv, w * .42f, h, 6.5f * k, t.deep, .14f);
        return b;
    }
}
