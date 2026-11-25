package com.ayubase6.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    // Start screen (logo + name + "Life Basics") stays over the page until the page has drawn its first screen
    // (the page tells us through the plugin), or 8 s at most, so there is no white screen.
    static volatile boolean bootReady = false;

    private void showStartScreen() {
        try {
            final android.widget.FrameLayout ov = new android.widget.FrameLayout(this);
            ov.setBackgroundColor(0xFFF6F8FB);   // the app's page colour, as the start screen before it
            // Two soft corner shapes in the logo's colours: green top-left, blue bottom-right (one big circle each; the earlier version with a smaller second circle is commit 37231e4)
            final float sc = getResources().getDisplayMetrics().widthPixels / 180f;
            final int[][] sh = { {0xFFA9D58C, 150, -55, -60, 0, 230}, {0xFF93BFE8, 150, -55, -60, 1, 217} };
            for (int[] d : sh) {
                android.view.View v = new android.view.View(this);
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setColor(d[0]);
                v.setBackground(g); v.setAlpha(d[5] / 255f);
                int size = Math.round(d[1] * sc);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size,
                    d[4] == 0 ? (android.view.Gravity.TOP | android.view.Gravity.START) : (android.view.Gravity.BOTTOM | android.view.Gravity.END));
                if (d[4] == 0) { lp.leftMargin = Math.round(d[2] * sc); lp.topMargin = Math.round(d[3] * sc); }
                else { lp.rightMargin = Math.round(d[2] * sc); lp.bottomMargin = Math.round(d[3] * sc); }
                ov.addView(v, lp);
            }
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(R.drawable.splash_brand);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            // A soft breathing pulse while the app loads (no progress implied)
            try {
                android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(iv,
                    android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 1.04f),
                    android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 1.04f),
                    android.animation.PropertyValuesHolder.ofFloat(android.view.View.ALPHA, 1f, 0.82f));
                pulse.setDuration(750); pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE); pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                pulse.start();
                iv.setTag(pulse);
            } catch (Throwable ignored) {}
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
            ov.addView(iv, new android.widget.FrameLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            // One short tip under the logo (from the kept copy; nothing when there is none)
            final String tip = StartTips.pick(this);
            if (tip != null) {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText("\u201C" + tip + "\u201D"); tv.setTextColor(0xFF334155); tv.setTextSize(12f); tv.setGravity(android.view.Gravity.CENTER);
                tv.setTypeface(null, android.graphics.Typeface.ITALIC);
                int pad = Math.round(28 * getResources().getDisplayMetrics().density);
                tv.setPadding(pad, 0, pad, 0);
                int H = getResources().getDisplayMetrics().heightPixels;
                int imgH = Math.round(w * 900f / 1020f);
                android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP);
                tl.topMargin = (H - imgH) / 2 + Math.round(imgH * 0.74f);
                ov.addView(tv, tl);
            }
            final long minShow = tip != null ? 1500 : 0;
            addContentView(ov, new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            final long t0 = android.os.SystemClock.uptimeMillis();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() { @Override public void run() {
                long el = android.os.SystemClock.uptimeMillis() - t0;
                if ((bootReady && el >= minShow) || el > 8000) {
                    try { Object pa = iv.getTag(); if (pa instanceof android.animation.Animator) ((android.animation.Animator) pa).cancel(); } catch (Throwable ignored) {}
                    ov.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                        try { ((android.view.ViewGroup) ov.getParent()).removeView(ov); } catch (Throwable ignored) {}
                    }).start();
                } else h.postDelayed(this, 40);
            } });
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AyuRemindersPlugin.class);
        try { androidx.core.splashscreen.SplashScreen.installSplashScreen(this); } catch (Throwable ignored) {}
        super.onCreate(savedInstanceState);
        showStartScreen();
        StartTips.refresh(this);
        // Text at the same size as the website: the phone's font-size setting does not enlarge the app's text.
        // (Pinch-zoom still works.) To follow the phone again, remove this line.
        if (getBridge() != null && getBridge().getWebView() != null) {
            android.webkit.WebView wv = getBridge().getWebView();
            wv.getSettings().setTextZoom(100);
            // Ads: the website's AdSense runs in the app through Google's WebView API for Ads.
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(true);
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
            try { com.google.android.gms.ads.MobileAds.registerWebView(wv); } catch (Throwable ignored) {}
        }
        // A new version of the app: the WebView's kept copies of the old pages are dropped, so every screen is the new one
        try {
            android.content.SharedPreferences sp = getSharedPreferences("ayu_app", MODE_PRIVATE);
            int was = sp.getInt("www_version", 0), now = BuildConfig.VERSION_CODE;
            if (was != now) {
                sp.edit().putInt("www_version", now).apply();
                if (getBridge() != null && getBridge().getWebView() != null) {
                    android.webkit.WebView wv = getBridge().getWebView();
                    wv.clearCache(true);
                    wv.postDelayed(wv::reload, 300);
                }
            }
        } catch (Exception ignored) {}
        try { AnnouncementCheck.schedule(this); } catch (Exception ignored) {}
        try { UpdateCheck.schedule(this); } catch (Exception ignored) {}
    }

    // Announcements: no notification while the app is in use (the banner shows in the app)
    @Override public void onResume() {
        super.onResume(); AnnouncementCheck.appVisible = true;
        try { ReminderScheduler.cancel(this, AyuRemindersPlugin.BACK_TAG); } catch (Exception ignored) {}   // back in the app: the "Tap to return" note goes
        PermWatch.noteOpen(this);   // remember Alarms & reminders / full-screen alarms; the "allow alarms" note and its timer go
    }
    @Override public void onPause() { AnnouncementCheck.appVisible = false; super.onPause(); }

    // An alarm (or its preview) is ringing while AyuBase6 is on screen: the volume buttons snooze or stop it
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && AlarmService.current() != null) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) AlarmService.current().keyPressed();
            return true;   // the press is used for the alarm, not for the volume
        }
        return super.dispatchKeyEvent(e);
    }
}
