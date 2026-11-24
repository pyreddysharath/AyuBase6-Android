package com.ayubase6.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    // Start screen (logo + name + "Life Basics") stays over the page until the page has drawn its first screen
    // (the page tells us through the plugin), or 4 s at most, so there is no white screen.
    static volatile boolean bootReady = false;

    private void showStartScreen() {
        try {
            final android.widget.FrameLayout ov = new android.widget.FrameLayout(this);
            ov.setBackgroundColor(0xFFFFFFFF);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(R.drawable.splash_brand);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
            ov.addView(iv, new android.widget.FrameLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            addContentView(ov, new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            final long t0 = android.os.SystemClock.uptimeMillis();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() { @Override public void run() {
                if (bootReady || android.os.SystemClock.uptimeMillis() - t0 > 4000) {
                    ov.animate().alpha(0f).setDuration(180).withEndAction(() -> {
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
