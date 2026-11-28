package com.ayubase6.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Checks alarm (at the start time) and Reminders alarm (own time): rings (looping ringtone, vibration) until stopped or for the chosen minutes,
 * with Snooze (up to the chosen number of times) and Stop. Runs as a foreground service so it
 * keeps ringing with the app closed; on a locked phone it opens AlarmActivity full screen.
 */
public class AlarmService extends Service {
    static final String A_START = "com.ayubase6.app.ALARM_START";
    static final String EX_KEY = "ayu_key", EX_TITLE = "ayu_title", EX_BODY = "ayu_body", EX_COUNT = "ayu_count", EX_CFG = "ayu_cfg", EX_PREVIEW = "ayu_preview";
    static final String CHANNEL_ALARMS = "alarms";
    static final int NOTIF_ID = 7301;

    private static volatile AlarmService running;
    static WeakReference<AlarmActivity> screen = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private Runnable autoStop;
    private MediaSession session;          // receives the volume buttons while it rings (also without the alarm screen)
    private BroadcastReceiver screenOff;   // the lock screen (power) button
    private BroadcastReceiver volChange;   // a volume button changed the alarm volume (when no screen of ours gets the key)
    private int alarmVol = -1;
    private long buttonsFrom = 0;          // ignore presses in the first moment (the screen may be switching)
    private Runnable screenWatch;          // the power button, also on phones that send "screen off" late or not at all
    private boolean wasOn = true;
    private final java.util.Map<Integer, Integer> vols = new java.util.HashMap<>();   // every volume when the ringing began
    private boolean answered = false;
    String key = "", title = "", body = "";
    int count = 0;
    boolean preview = false;   // Preview notification: the real look, sound and buttons for up to 30 s; any button just closes it
    JSONObject cfg = new JSONObject();

    // ── settings sent by the app with the rules (rules.alarms; key "alarm-<id>") ──
    static JSONObject config(Context c, String key) {
        JSONObject rules = ReminderScheduler.loadRules(c);
        org.json.JSONArray list = rules == null ? null : rules.optJSONArray("alarms");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a != null && a.optBoolean("on", true) && ("alarm-" + a.optString("id")).equals(key)) return a;
        }
        return null;
    }

    /** Colours of the section the alarm belongs to: Checks (wake) or Reminders. */
    NotificationArt.Theme theme() { return "alerts".equals(section()) ? NotificationArt.REMINDERS : NotificationArt.ROUTINES; }
    String section() { return "alerts".equals(cfg.optString("section")) ? "alerts" : "routines"; }

    /** Starts ringing for a Checks activity. Returns false if Android did not allow it (then a notification is shown). */
    static boolean start(Context c, String key, String title, String body, int count) {
        Intent i = new Intent(c, AlarmService.class).setAction(A_START)
            .putExtra(EX_KEY, key).putExtra(EX_TITLE, title).putExtra(EX_BODY, body).putExtra(EX_COUNT, count);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(ReminderScheduler.TAG, "alarm could not start", e);
            AlarmLog.add(c, "startfail:" + key + ":" + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Rings with its own settings (the Clock timer: tone, vibrate, ringSec). */
    static boolean startWith(Context c, String key, String title, String body, JSONObject cfg) {
        Intent i = new Intent(c, AlarmService.class).setAction(A_START)
            .putExtra(EX_KEY, key).putExtra(EX_TITLE, title).putExtra(EX_BODY, body).putExtra(EX_COUNT, 0).putExtra(EX_CFG, cfg.toString());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(ReminderScheduler.TAG, "timer alarm could not start", e);
            return false;
        }
    }

    /** Preview notification: the alarm's real notification, sound and vibration for up to 30 seconds, without the full screen. */
    static boolean startPreview(Context c, String key, String title, String body, JSONObject cfg) {
        Intent i = new Intent(c, AlarmService.class).setAction(A_START).putExtra(EX_PREVIEW, true)
            .putExtra(EX_KEY, key).putExtra(EX_TITLE, title).putExtra(EX_BODY, body).putExtra(EX_COUNT, 0).putExtra(EX_CFG, cfg.toString());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(ReminderScheduler.TAG, "alarm preview could not start", e);
            return false;
        }
    }

    /** A snoozed alarm rings again with the settings it had when it was snoozed. */
    static boolean startSnoozed(Context c, String key, String title, String body, int count, String cfg) {
        Intent i = new Intent(c, AlarmService.class).setAction(A_START)
            .putExtra(EX_KEY, key).putExtra(EX_TITLE, title).putExtra(EX_BODY, body).putExtra(EX_COUNT, count);
        if (cfg != null && !cfg.isEmpty()) i.putExtra(EX_CFG, cfg);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            AlarmLog.add(c, "startfail:" + key + ":" + e.getClass().getSimpleName());
            return false;
        }
    }

    static AlarmService current() { return running; }

    /** A Done from a normal notification for the same activity also silences its alarm. */
    static void stopIfKey(String k) {
        AlarmService s = running;
        if (s != null && s.key.equals(k)) s.main.post(s::stopAll);
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ALARMS) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Checks and Reminders alarms (the ringtone is chosen in the app)");
        ch.setSound(null, null);          // the alarm plays its own ringtone
        ch.enableVibration(false);        // and vibrates itself
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        ch.setBypassDnd(false);
        nm.createNotificationChannel(ch);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !A_START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        boolean pv = intent.getBooleanExtra(EX_PREVIEW, false);
        if (pv && running == this && player != null) return START_NOT_STICKY;   // a real alarm is ringing: no preview over it
        preview = pv;
        if (running == this && player != null) { leaveNotice(); stopSound(); }   // a second alarm while one rings: the first one becomes a notification
        key = str(intent.getStringExtra(EX_KEY));
        JSONObject c = config(this, key);
        String own = intent.getStringExtra(EX_CFG);
        if (own != null) { try { c = new JSONObject(own); } catch (Exception ignored) {} }
        title = str(intent.getStringExtra(EX_TITLE)); body = str(intent.getStringExtra(EX_BODY));
        count = intent.getIntExtra(EX_COUNT, 0);
        cfg = c != null ? c : new JSONObject();
        running = this;
        ensureChannel(this);
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
        } catch (Exception e) {
            Log.w(ReminderScheduler.TAG, "alarm foreground refused", e);
            AlarmLog.add(this, "refused:" + key + ":" + e.getClass().getSimpleName());
            ReminderScheduler.show(this, "nudge-" + key, title, body, false, theme(), section(), key, ReminderScheduler.actionsFor(key));
            running = null; stopSelf(); return START_NOT_STICKY;
        }
        if (!preview) AlarmLog.add(this, "ringing:" + key);
        answered = false;
        startButtons();
        if (preview) {   // the real alarm with its sound and vibration, for up to 30 seconds
            startRinging();
            if (autoStop != null) main.removeCallbacks(autoStop);
            autoStop = this::stopAll; main.postDelayed(autoStop, 30000L);
            return START_NOT_STICKY;
        }
        startRinging();
        int ringMin = cfg.optInt("ring", 0);
        if (autoStop != null) main.removeCallbacks(autoStop);
        int ringSec = cfg.optInt("ringSec", 0);
        if (ringSec > 0) { autoStop = this::missed; main.postDelayed(autoStop, ringSec * 1000L); }
        else if (ringMin > 0) { autoStop = this::missed; main.postDelayed(autoStop, ringMin * 60000L); }
        AlarmActivity a = screen.get();
        if (a != null) a.refresh();
        return START_NOT_STICKY;
    }


    private static String str(String s) { return s == null ? "" : s; }

    boolean canSnooze() { return count < cfg.optInt("snoozeMax", 0); }
    int snoozeMin() { return Math.max(1, cfg.optInt("snoozeMin", 10)); }
    String keyAction() { return "snooze".equals(cfg.optString("keys", "stop")) ? "snooze" : "stop"; }     // volume buttons (default Stop)
    String lockAction() { return "stop".equals(cfg.optString("lock", "snooze")) ? "stop" : "snooze"; }    // lock screen button (default Snooze)

    /** Volume buttons and the lock screen (power) button while it rings, also for a preview and without the alarm screen. */
    private void startButtons() {
        stopButtons();
        buttonsFrom = System.currentTimeMillis() + (preview ? 400 : 1500);   // a preview has no screen change to wait for
        try {
            session = new MediaSession(this, "AyuBase6Alarm");
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 10, 5) {
                @Override public void onAdjustVolume(int direction) { if (direction != 0) main.post(() -> pressed("volume")); }
            });
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
            session.setActive(true);
        } catch (Exception e) { Log.w(ReminderScheduler.TAG, "volume buttons not available", e); }
        try {
            final android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            alarmVol = am != null ? am.getStreamVolume(android.media.AudioManager.STREAM_ALARM) : -1;
            vols.clear();
            if (am != null) for (int st : new int[]{android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.STREAM_RING, android.media.AudioManager.STREAM_NOTIFICATION}) {
                try { vols.put(st, am.getStreamVolume(st)); } catch (Exception ignored) {}
            }
            // Which volume the buttons move differs from phone to phone (alarm, media or ring): any of them counts, and is put back
            volChange = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) {
                if (am == null) return;
                int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                Integer was = vols.get(st);
                if (was == null) return;
                int now = am.getStreamVolume(st);
                if (now == was) return;
                try { am.setStreamVolume(st, was, 0); } catch (Exception ignored) {}   // keep the person's volume
                pressed("volume");
            } };
            IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volChange, vf, Context.RECEIVER_EXPORTED); else registerReceiver(volChange, vf);
        } catch (Exception e) { volChange = null; }
        try {
            screenOff = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { pressed("lock"); } };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenOff, f);
        } catch (Exception e) { screenOff = null; }
        // The same button, seen directly: the screen was on and is now off
        try {
            final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wasOn = pm == null || pm.isInteractive();
            screenWatch = new Runnable() { @Override public void run() {
                if (screenWatch != this) return;
                boolean on = pm == null || pm.isInteractive();
                if (wasOn && !on) pressed("lock");
                wasOn = on;
                main.postDelayed(this, 250);
            } };
            main.postDelayed(screenWatch, 250);
        } catch (Exception e) { screenWatch = null; }
    }

    private void stopButtons() {
        if (screenWatch != null) { main.removeCallbacks(screenWatch); screenWatch = null; }
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) {}
        session = null;
        try { if (screenOff != null) unregisterReceiver(screenOff); } catch (Exception ignored) {}
        screenOff = null;
        try { if (volChange != null) unregisterReceiver(volChange); } catch (Exception ignored) {}
        volChange = null;
    }

    private void pressed(String which) {
        if (answered || System.currentTimeMillis() < buttonsFrom || running != this) return;
        answered = true;
        String a = "lock".equals(which) ? lockAction() : keyAction();
        if ("stop".equals(a)) userStop(); else snooze();
    }

    private Uri toneUri() {
        String tone = cfg.optString("tone", "rise");
        if (tone.startsWith("content://")) return Uri.parse(tone);   // one of the phone's own alarm sounds or ringtones
        if ("phone".equals(tone)) {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u != null) return u;
            tone = "rise";
        }
        if ("own".equals(tone)) {
            File f = ownTone(this);
            if (f.exists() && f.length() > 0) return Uri.fromFile(f);
            tone = "rise";
        }
        return rawTone(this, tone);
    }

    static File ownTone(Context c) { return new File(c.getFilesDir(), "alarm-own-tone"); }

    /** The sound of a notification: one of the phone's own sounds (content://), "phone" (its default notification sound) or an app tone. */
    static Uri notifTone(Context c, String tone) {
        if (tone != null && tone.startsWith("content://")) return Uri.parse(tone);
        if ("phone".equals(tone)) { Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); if (u != null) return u; }
        return rawTone(c, tone);
    }

    /** The app tone by name (android.resource://<app>/raw/tone_x): a number would point at another file after an update. */
    static Uri rawTone(Context c, String id) {
        String n = "tone_" + (id == null ? "" : id.replaceAll("[^a-z]", ""));
        if (c.getResources().getIdentifier(n, "raw", c.getPackageName()) == 0) n = "tone_rise";
        return Uri.parse("android.resource://" + c.getPackageName() + "/raw/" + n);
    }

    private void startRinging() {
        stopSound();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) { wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ayubase6:alarm"); wake.acquire(15 * 60000L); }
        } catch (Exception ignored) {}
        if ("none".equals(cfg.optString("tone"))) { startVibration(); return; }   // No sound (vibrate only)
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, toneUri());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(ReminderScheduler.TAG, "ringtone failed, using the app tone", e);
            try {
                player.reset();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                player.setDataSource(this, rawTone(this, "rise"));
                player.setLooping(true); player.prepare(); player.start();
            } catch (Exception ignored) {}
        }
        startVibration();
    }

    private void startVibration() {
        if (cfg != null && !cfg.optBoolean("vibrate", false)) return;   // Vibrate switch off for this alarm
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] pattern = {0, 700, 700};
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                else vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
    }

    private void stopSound() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
        player = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, AlarmActionReceiver.class).setAction("com.ayubase6.app.ALARM." + a).putExtra(AlarmActionReceiver.EX_ACTION, a);
        return PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The notification text: not the plain "Your alarm." — the time instead. */
    private String shownBody() {
        String b = body == null ? "" : body.trim();
        if (b.equalsIgnoreCase("Your alarm.") || b.equalsIgnoreCase("Your alarm")) return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date());
        return body;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent full = PendingIntent.getActivity(this, 7302, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ALARMS)
            .setSmallIcon(ReminderScheduler.smallIcon(this))   // the lotus, in the section colour like every other notification
            .setContentTitle(ReminderScheduler.noTime(title))
            .setShowWhen(false)
            .setContentText(shownBody())
            .setStyle(new NotificationCompat.BigTextStyle().bigText(shownBody()))
            .setColor(NotificationArt.accentColor(this, theme()))   // not colorized: the card looks the same as the other notifications (section-coloured title, icon and Done)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setDeleteIntent(action("stop", 7314));   // swiped away (Android 14+ allows it): stop the sound too
        boolean routine = key.startsWith("routine-");
        boolean custom = false;
        try {   // Snooze / Stop drawn by the app as coloured pills (Android draws its own action buttons grey)
            String ttl = ReminderScheduler.noTime(title);
            int col = NotificationArt.titleColor(this, theme());
            boolean snz = canSnooze();
            // expanded / pop-up: title, time, full pills, and the alarm clock in the section's colour
            android.widget.RemoteViews v = new android.widget.RemoteViews(getPackageName(), R.layout.notif_alarm);
            v.setTextViewText(R.id.na_title, ttl); v.setTextColor(R.id.na_title, col);
            v.setTextViewText(R.id.na_text, shownBody());
            v.setImageViewBitmap(R.id.na_icon, NotificationArt.alarm(theme()));
            if (snz) { v.setTextViewText(R.id.na_snooze, "Snooze"); v.setOnClickPendingIntent(R.id.na_snooze, action("snooze", 7311)); }
            else v.setViewVisibility(R.id.na_snooze, android.view.View.GONE);
            v.setOnClickPendingIntent(R.id.na_stop, action("stop", 7312));
            if (routine) { v.setViewVisibility(R.id.na_done, android.view.View.VISIBLE); v.setOnClickPendingIntent(R.id.na_done, action("done", 7313)); }
            // collapsed: one row, small Snooze / Stop on the right, no alarm clock
            android.widget.RemoteViews sm = new android.widget.RemoteViews(getPackageName(), R.layout.notif_alarm_small);
            sm.setTextViewText(R.id.nas_title, ttl); sm.setTextColor(R.id.nas_title, col);
            sm.setTextViewText(R.id.nas_text, shownBody());
            if (snz) sm.setOnClickPendingIntent(R.id.nas_snooze, action("snooze", 7311)); else sm.setViewVisibility(R.id.nas_snooze, android.view.View.GONE);
            sm.setOnClickPendingIntent(R.id.nas_stop, action("stop", 7312));
            if (routine) { sm.setViewVisibility(R.id.nas_done, android.view.View.VISIBLE); sm.setOnClickPendingIntent(R.id.nas_done, action("done", 7313)); }
            b.setStyle(new NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(sm).setCustomBigContentView(v).setCustomHeadsUpContentView(v);
            custom = true;
        } catch (Exception e) { /* fall back to the standard buttons */ }
        if (!custom) {
            b.setLargeIcon(NotificationArt.alarm(theme()));
            ReminderScheduler.sectionTitle(this, b, theme(), ReminderScheduler.noTime(title), shownBody());
            if (canSnooze()) b.addAction(0, "Snooze", action("snooze", 7311));
            b.addAction(0, "Stop", action("stop", 7312));
            if (routine) b.addAction(0, ReminderScheduler.tinted(this, theme(), "✓ Done"), action("done", 7313));
        }
        if (!preview) b.setFullScreenIntent(full, true);
        return b.build();
    }

    // ── what the buttons (notification, alarm screen, volume keys) do ──
    /** The person pressed Stop (or Snooze with none left). For the Wake-up Alarm this is when the day starts. */
    void userStop() {
        if (!preview && "alarm-wake".equals(key)) { try { ReminderScheduler.wakeStopped(getApplicationContext()); } catch (Exception ignored) {} }
        stopAll();
    }

    void snooze() {
        if (preview || !canSnooze()) { userStop(); return; }
        ReminderScheduler.addAlarmSnooze(this, key, title, body, count + 1, snoozeMin(), cfg, section());
        stopAll();
        AyuRemindersPlugin.alarmChanged();
    }

    void done() {
        if (preview) { stopAll(); return; }
        final String k = key, t = title, b = body;
        stopAll();
        new Thread(() -> {
            ReminderScheduler.applyAction(getApplicationContext(), k, ReminderScheduler.A_DONE, "nudge-" + k, t, b);
            AyuRemindersPlugin.actionsWaiting();
        }, "ayu-alarm-done").start();
    }

    void keyPressed() { if (answered) return; answered = true; if ("stop".equals(keyAction())) userStop(); else snooze(); }   // snooze in a preview just stops it

    /** Rang for the chosen minutes without an answer: stop ringing, leave a normal notification (Done / Not yet). */
    void missed() {
        if (preview) { stopAll(); return; }
        leaveNotice();
        stopAll();
    }

    private void leaveNotice() {
        if (key.equals("timer")) return;   // the timer's own notification stays
        if (!key.isEmpty()) ReminderScheduler.show(this, "nudge-" + key, title, body, false, theme(), section(), key, ReminderScheduler.actionsFor(key));
    }

    void stopAll() {
        if (autoStop != null) main.removeCallbacks(autoStop);
        stopButtons();
        stopSound();
        NotificationManagerCompat.from(this).cancel(NOTIF_ID);
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        AlarmActivity a = screen.get();
        if (a != null) a.finish();
        if (running == this) running = null;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopButtons();
        stopSound();
        if (running == this) running = null;
        super.onDestroy();
    }
}
