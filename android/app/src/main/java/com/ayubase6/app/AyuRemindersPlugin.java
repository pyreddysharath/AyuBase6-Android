package com.ayubase6.app;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

/**
 * Bridge between the web app and the phone's reminders (see src/android-bridge.js).
 *   setRules({rules})          store the reminder rules and set the next alarm (rules null = all off)
 *   show({key,title,body,...})  show a notification now (tests, previews, the eye timer)
 *   cancel({key})               remove a notification
 *   checkPermissions / requestPermissions   notifications (Android 13+ asks), plus exact-alarm state
 *   openExactAlarmSettings / openNotificationSettings / openBatterySettings
 *   getStatus()                 next alarm time and permission state
 *   soundCheck()                ringer, volumes, Do Not Disturb, channels, exact alarms, battery, last force stop
 * Event "reminderTap" {key, section}: a notification was tapped.
 */
@CapacitorPlugin(
    name = "AyuReminders",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class AyuRemindersPlugin extends Plugin {

    private static java.lang.ref.WeakReference<AyuRemindersPlugin> current = new java.lang.ref.WeakReference<>(null);

    /** A notification button was used: tell the open app to fetch and apply it (Done ticks, Turn off switches off). */
    static void actionsWaiting() {
        AyuRemindersPlugin p = current.get();
        if (p != null) p.notifyListeners("reminderAction", new JSObject(), true);
    }

    /** A Clock button on a notification (or the timer's end) changed the timer or stopwatch: tell the open app. */
    static void clockChanged() {
        AyuRemindersPlugin p = current.get();
        if (p != null) p.notifyListeners("clockChanged", new JSObject(), true);
    }

    /** The page's Clock state: timer, stopwatch, world (each only if newer than the phone's copy, by its "v"). */
    @PluginMethod
    public void clockSet(PluginCall call) {
        android.content.Context c = getContext();
        synchronized (ClockReceiver.class) {
            for (String k : new String[]{"timer", "stopwatch", "world"}) {
                JSObject o = call.getObject(k);
                if (o == null) continue;
                JSONObject mine = ClockNotifier.load(c, k);
                if (o.optLong("v", 0) >= mine.optLong("v", 0)) ClockNotifier.save(c, k, o);
            }
        }
        ClockNotifier.refresh(c);
        call.resolve(clockState());
    }

    @PluginMethod
    public void clockGet(PluginCall call) { call.resolve(clockState()); }

    private JSObject clockState() {
        JSObject r = new JSObject();
        android.content.Context c = getContext();
        try {
            r.put("timer", JSObject.fromJSONObject(ClockNotifier.load(c, "timer")));
            r.put("stopwatch", JSObject.fromJSONObject(ClockNotifier.load(c, "stopwatch")));
            r.put("world", JSObject.fromJSONObject(ClockNotifier.load(c, "world")));
        } catch (Exception ignored) {}
        r.put("now", System.currentTimeMillis());
        return r;
    }

    /** An alarm was snoozed or its snooze ended: the page shows the new "rings at" time. */
    static void alarmChanged() {
        AyuRemindersPlugin p = current.get();
        if (p != null) p.notifyListeners("alarmChanged", new JSObject(), true);
    }

    /** The alarm was switched off or deleted in the app: a snooze in progress is dropped (it does not ring again). */
    @PluginMethod
    public void snoozeCancel(PluginCall call) {
        ReminderScheduler.cancelAlarmSnooze(getContext(), call.getString("key", ""));
        call.resolve();
    }

    /** The app has moved today's wake-up time itself: the phone's own shift ends. */
    @PluginMethod
    public void wakeShiftClear(PluginCall call) { ReminderScheduler.clearWakeShift(getContext()); call.resolve(); }

    @PluginMethod
    public void takeActions(PluginCall call) {
        JSObject r = new JSObject();
        r.put("actions", ReminderScheduler.takePending(getContext()));
        call.resolve(r);
    }

    @Override
    public void load() {
        current = new java.lang.ref.WeakReference<>(this);
        ReminderScheduler.ensureChannels(getContext());
        if (getActivity() != null) deliverTap(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        deliverTap(intent);
    }

    private void deliverTap(Intent intent) {
        if (intent == null || !intent.hasExtra(ReminderScheduler.EXTRA_SECTION)) return;
        // The app reopened from Recents (or Android restarted it) with the intent of an earlier tap: that tap is not repeated
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        JSObject d = new JSObject();
        d.put("key", intent.getStringExtra(ReminderScheduler.EXTRA_KEY));
        d.put("section", intent.getStringExtra(ReminderScheduler.EXTRA_SECTION));
        intent.removeExtra(ReminderScheduler.EXTRA_SECTION);
        intent.removeExtra(ReminderScheduler.EXTRA_KEY);
        try { if (getActivity() != null) getActivity().setIntent(new Intent(getContext(), MainActivity.class)); } catch (Exception ignored) {}   // used once
        notifyListeners("reminderTap", d, true);
    }

    private String notificationState() {
        boolean enabled = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return enabled ? "granted" : "denied";
        PermissionState s = getPermissionState("notifications");
        if (s == PermissionState.GRANTED) return enabled ? "granted" : "denied";
        return s == PermissionState.DENIED ? "denied" : "prompt";
    }

    private boolean batteryUnrestricted() {
        PowerManager pm = (PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    private JSObject status() {
        JSObject r = new JSObject();
        r.put("notifications", notificationState());
        r.put("exactAlarm", ReminderScheduler.canExact(getContext()));
        r.put("batteryUnrestricted", batteryUnrestricted());
        long next = ReminderScheduler.nextAt(getContext());
        if (next > 0) r.put("next", next); else r.put("next", JSONObject.NULL);
        r.put("rules", ReminderScheduler.loadRules(getContext()) != null);
        r.put("store", BuildConfig.FLAVOR);   // "website" (APK from ayubase6.com) or "play" (Google Play updates it)
        r.put("selfInstall", false);          // no installer inside the app: a new version downloads in the browser
        r.put("snoozed", ReminderScheduler.snoozedAlarms(getContext()));   // snoozed alarms and when each rings next
        JSONObject ws = ReminderScheduler.wakeShift(getContext());
        if (ws != null) r.put("wakeShift", ws);   // the wake-up alarm was snoozed today: the app moves the day by these minutes
        try {
            android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            r.put("versionCode", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode);
            r.put("versionName", pi.versionName);
        } catch (Exception ignored) {}
        return r;
    }

    @Override
    @PluginMethod
    public void checkPermissions(PluginCall call) { call.resolve(status()); }

    @Override
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationsCallback");
        } else {
            call.resolve(status());
        }
    }

    @PermissionCallback
    private void notificationsCallback(PluginCall call) { call.resolve(status()); }

    @PluginMethod
    public void getStatus(PluginCall call) { call.resolve(status()); }

    /** Help's notification check: everything on the phone that can silence a reminder or stop an alarm. */
    @PluginMethod
    public void soundCheck(PluginCall call) {
        android.content.Context c = getContext();
        JSObject r = new JSObject();
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                int m = am.getRingerMode();
                r.put("ringer", m == android.media.AudioManager.RINGER_MODE_SILENT ? "silent" : m == android.media.AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal");
                r.put("notifVol", am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION));
                r.put("notifMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_NOTIFICATION));
                r.put("alarmVol", am.getStreamVolume(android.media.AudioManager.STREAM_ALARM));
                r.put("alarmMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM));
            }
        } catch (Exception ignored) {}
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int f = nm.getCurrentInterruptionFilter();   // 1 all, 2 priority only, 3 none, 4 alarms only
                r.put("dnd", f == android.app.NotificationManager.INTERRUPTION_FILTER_ALL || f == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN ? "off"
                    : f == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY ? "priority" : f == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS ? "alarms" : "none");
                if (Build.VERSION.SDK_INT >= 34) r.put("fullScreen", nm.canUseFullScreenIntent());
                ReminderScheduler.ensureChannels(c); AlarmService.ensureChannel(c);
                org.json.JSONArray chs = new org.json.JSONArray();
                for (android.app.NotificationChannel ch : nm.getNotificationChannels()) {
                    String id = ch.getId();
                    if (!(id.equals(ReminderScheduler.CHANNEL_REMINDERS) || id.equals(ReminderScheduler.CHANNEL_REMINDERS_QUIET)
                        || id.equals(AlarmService.CHANNEL_ALARMS) || id.startsWith(ReminderScheduler.TONE_CH))) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", id); o.put("name", String.valueOf(ch.getName())); o.put("importance", ch.getImportance());
                    o.put("sound", ch.getSound() != null);
                    chs.put(o);
                }
                r.put("channels", chs);
            }
        } catch (Exception ignored) {}
        r.put("notifications", notificationState());
        r.put("exact", ReminderScheduler.canExact(c));
        r.put("batteryUnrestricted", batteryUnrestricted());
        try {
            android.app.ActivityManager act = (android.app.ActivityManager) c.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.put("bgRestricted", act.isBackgroundRestricted());
            if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The last time the app was closed: was it force-stopped (that cancels its alarms)? An app update is not counted.
                java.util.List<android.app.ApplicationExitInfo> ex = act.getHistoricalProcessExitReasons(c.getPackageName(), 0, 1);
                if (!ex.isEmpty()) {
                    android.app.ApplicationExitInfo e = ex.get(0);
                    String d = String.valueOf(e.getDescription()).toLowerCase(java.util.Locale.US);
                    if (e.getReason() == android.app.ApplicationExitInfo.REASON_USER_REQUESTED && !d.contains("install") && !d.contains("updat") && !d.contains("replac"))
                        r.put("forceStoppedAt", e.getTimestamp());
                }
            }
        } catch (Exception ignored) {}
        r.put("manufacturer", Build.MANUFACTURER);
        r.put("android", Build.VERSION.RELEASE);
        try { r.put("alarmLog", AlarmLog.get(c)); r.put("nextAlarm", ReminderScheduler.nextAt(c)); } catch (Exception ignored) {}
        call.resolve(r);
    }

    /** Background update checks: times a day (0 = off, 1–4), only between 9 AM and 9 PM. */
    @PluginMethod
    public void updateChecksGet(PluginCall call) { JSObject r = new JSObject(); r.put("times", UpdateCheck.times(getContext())); call.resolve(r); }
    @PluginMethod
    public void updateChecksSet(PluginCall call) {
        UpdateCheck.setTimes(getContext(), call.getInt("times", 2));
        JSObject r = new JSObject(); r.put("times", UpdateCheck.times(getContext())); call.resolve(r);
    }

    @PluginMethod
    public void setRules(PluginCall call) {
        JSONObject rules = call.getObject("rules", null);
        ReminderScheduler.keepPendingInForce(getContext(), rules);
        ReminderScheduler.saveRules(getContext(), rules);
        long next = ReminderScheduler.reschedule(getContext());
        JSObject r = new JSObject();
        if (next > 0) r.put("next", next); else r.put("next", JSONObject.NULL);
        r.put("exact", ReminderScheduler.canExact(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void show(PluginCall call) {
        String key = call.getString("key", "note");
        String color = call.getString("color", "");
        // A colour is sent only when the notification belongs to the open section (the Help test); otherwise its own section decides.
        NotificationArt.Theme theme;
        try { theme = (color != null && color.matches("^#[0-9a-fA-F]{6}$")) ? NotificationArt.fromColor(android.graphics.Color.parseColor(color)) : NotificationArt.forSection(call.getString("section", "")); }
        catch (Exception e) { theme = NotificationArt.REMINDERS; }
        String like = call.getString("previewLike", "");
        if (like != null && !like.isEmpty()) {   // Preview notification: the real buttons of that reminder; tapping one only closes the preview
            ReminderScheduler.show(getContext(), key, call.getString("title", "AyuBase6"), call.getString("body", ""),
                false, theme, call.getString("section", ""), "preview:" + like, ReminderScheduler.actionsFor(like));
        } else {
            ReminderScheduler.show(getContext(), key, call.getString("title", "AyuBase6"), call.getString("body", ""),
                Boolean.TRUE.equals(call.getBoolean("silent", false)), theme, call.getString("section", ""), call.getString("tapKey", ""));
        }
        call.resolve();
    }

    /** Preview of an alarm or the timer: its real notification (colours, Snooze / Stop), sound and vibration, for up to 30 seconds. */
    @PluginMethod
    public void previewAlarm(PluginCall call) {
        JSObject cfg = call.getObject("cfg", new JSObject());
        boolean ok = AlarmService.startPreview(getContext(), call.getString("key", "alarm-preview"), call.getString("title", "Alarm"), call.getString("body", ""),
            cfg == null ? new org.json.JSONObject() : cfg);
        JSObject r = new JSObject(); r.put("ok", ok); call.resolve(r);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        ReminderScheduler.cancel(getContext(), call.getString("key", ""));
        call.resolve();
    }

    private void openSettings(PluginCall call, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(intent);
        } catch (Exception e) {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { getActivity().startActivity(details); } catch (Exception ignored) {}
        }
        call.resolve();
    }

    // ── Settings pages for a permission: as soon as it is allowed there, the app comes back by itself ──
    private static final int REQ_SETTINGS = 7101;
    static final String BACK_TAG = "perm-back";
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable watcher;

    private void openAndReturn(PluginCall call, Intent intent, java.util.function.BooleanSupplier allowed) {
        if (allowed.getAsBoolean()) { call.resolve(status()); return; }
        // Opened in this app's task (no NEW_TASK) and for a result, so the app can close the page again.
        try { getActivity().startActivityForResult(intent, REQ_SETTINGS); }
        catch (Exception e) {
            try { getActivity().startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())), REQ_SETTINGS); }
            catch (Exception ignored) { call.resolve(status()); return; }
        }
        if (watcher != null) main.removeCallbacks(watcher);
        final long until = android.os.SystemClock.uptimeMillis() + 3 * 60 * 1000;
        watcher = new Runnable() {
            @Override public void run() {
                if (allowed.getAsBoolean()) {
                    watcher = null;
                    try { getActivity().finishActivity(REQ_SETTINGS); } catch (Exception ignored) {}
                    // If the page lives in another task, bring the app to the front instead.
                    main.postDelayed(() -> {
                        try {
                            if (!getActivity().hasWindowFocus()) {
                                getActivity().startActivity(new Intent(getContext(), MainActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
                            }
                        } catch (Exception ignored) {}
                    }, 350);
                    // Some phones (Xiaomi, Oppo, Vivo, Realme…) block apps from coming to the front by themselves:
                    // then say so on the settings page and leave a notification that opens the app.
                    main.postDelayed(() -> {
                        try {
                            if (getActivity().hasWindowFocus()) return;
                            android.widget.Toast.makeText(getContext(), "Allowed ✓  Tap Back to return to AyuBase6", android.widget.Toast.LENGTH_LONG).show();
                            if (NotificationManagerCompat.from(getContext()).areNotificationsEnabled())
                                ReminderScheduler.show(getContext(), BACK_TAG, "Allowed ✓", "Tap to return to AyuBase6", true, NotificationArt.REMINDERS, "", "");
                        } catch (Exception ignored) {}
                    }, 1500);
                    new Thread(() -> ReminderScheduler.reschedule(getContext()), "ayu-perm").start();
                    notifyListeners("permissionsChanged", status());
                    return;
                }
                if (android.os.SystemClock.uptimeMillis() < until) main.postDelayed(this, 400);
                else watcher = null;
            }
        };
        main.postDelayed(watcher, 600);
        call.resolve(status());
    }

    @PluginMethod
    public void openExactAlarmSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openAndReturn(call, new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getContext().getPackageName())),
                () -> ReminderScheduler.canExact(getContext()));
        } else call.resolve(status());
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        if (NotificationManagerCompat.from(getContext()).areNotificationsEnabled()) {
            // already on: just open Android's page for AyuBase6 (its switch turns them off)
            try { getActivity().startActivity(i); } catch (Exception e) {
                try { getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()))); } catch (Exception ignored) {}
            }
            call.resolve(status()); return;
        }
        openAndReturn(call, i, () -> NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
    }

    /** Settings → Disable Notifications. Android 13+: AyuBase6 gives up its notification permission (Android finishes it when the app
     *  closes, so the app closes now). Older Android has no such permission: its notification page opens instead. */
    @PluginMethod
    public void disableNotifications(PluginCall call) {
        JSObject r = new JSObject();
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ReminderScheduler.saveRules(getContext(), null); ReminderScheduler.reschedule(getContext());   // nothing more is scheduled
                getContext().revokeSelfPermissionOnKill(Manifest.permission.POST_NOTIFICATIONS);
                r.put("closing", true); call.resolve(r);
                main.postDelayed(() -> {
                    try { getActivity().finishAndRemoveTask(); } catch (Exception ignored) {}
                    main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 600);
                }, 400);
                return;
            } catch (Exception e) { /* fall back to the settings page */ }
        }
        r.put("closing", false);
        try { getActivity().startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName())); } catch (Exception ignored) {}
        call.resolve(r);
    }

    /** Keeps a copy of the app's data for Android's backup to the person's Google account, and asks Android to back it up soon. */
    @PluginMethod
    public void backupSave(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.length() > 4_000_000) { call.reject("bad data"); return; }
        long at = System.currentTimeMillis();
        getContext().getSharedPreferences(AyuBackupAgent.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("data", data).putLong("at", at).commit();
        new android.app.backup.BackupManager(getContext()).dataChanged();
        JSObject r = new JSObject(); r.put("at", at); call.resolve(r);
    }

    /** The saved copy, also after Android restored it on a new install. */
    @PluginMethod
    public void backupLoad(PluginCall call) {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(AyuBackupAgent.PREFS, android.content.Context.MODE_PRIVATE);
        JSObject r = new JSObject(); r.put("data", sp.getString("data", "")); r.put("at", sp.getLong("at", 0));
        r.put("lastBackup", getContext().getSharedPreferences(AyuBackupAgent.META, android.content.Context.MODE_PRIVATE).getLong("last", 0));
        call.resolve(r);
    }

    /** Removes the copy, so the next Google backup holds nothing (backup switched off). */
    @PluginMethod
    public void backupClear(PluginCall call) {
        getContext().getSharedPreferences(AyuBackupAgent.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        new android.app.backup.BackupManager(getContext()).dataChanged();
        call.resolve();
    }

    // Updates: the app only says a new version is ready; the download opens in the phone's browser (openUrl),
    // and the person installs the downloaded file. The app holds no installer and asks for no "install apps" permission.

    // ── Speak: the phone's voices, and a preview ──
    @PluginMethod
    public void ttsVoices(PluginCall call) {
        Speaker.voices(getContext(), list -> { JSObject r = new JSObject(); r.put("voices", list); call.resolve(r); });
    }

    @PluginMethod
    public void ttsSay(PluginCall call) {
        String text = call.getString("text", ""), voice = call.getString("voice", ""), lang = call.getString("lang", "");
        new Thread(() -> { Speaker.say(getContext(), text.length() > 200 ? text.substring(0, 200) : text, voice, lang); call.resolve(); }, "ayu-say").start();
    }

    /**
     * The screens on show are not the ones packed in this version of the app (the bridge compares the two numbers):
     * the web view's kept copies are dropped and the app's own start page is loaded again under a new address.
     */
    @PluginMethod
    public void reloadFresh(PluginCall call) {
        try {
            getActivity().runOnUiThread(() -> {
                try {
                    android.webkit.WebView wv = getBridge().getWebView();
                    wv.clearCache(true);
                    Uri u = Uri.parse(String.valueOf(wv.getUrl()));
                    if (u.getScheme() != null && u.getAuthority() != null) wv.loadUrl(u.getScheme() + "://" + u.getAuthority() + "/?fresh=" + BuildConfig.VERSION_CODE);
                    else wv.reload();
                } catch (Exception ignored) {}
            });
            call.resolve();
        } catch (Exception e) { call.reject("reload"); }
    }

    /** Opens a web address in the phone's browser (e.g. the new app version to download). Only https. */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) { call.reject("https only"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("No browser"); }
    }

    /**
     * Saves a file the app made (the Backup file, PDFs): to Downloads on Android 10+, otherwise through the share sheet.
     * The web view cannot download the page's own files, so the bridge hands them here.
     */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("name", "AyuBase6.json").replaceAll("[\\\\/:*?\"<>|]", "_");
        String text = call.getString("text", ""), b64 = call.getString("base64", null);
        String mime = call.getString("mime", "application/json");
        byte[] data = b64 != null ? android.util.Base64.decode(b64, android.util.Base64.DEFAULT) : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JSObject r = new JSObject();
        r.put("name", name);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                // Backup files go to their own folder, Download/AyuBase6, which also stays after the app is uninstalled.
                boolean backup = name.startsWith("AyuBase6-backup");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + (backup ? "/AyuBase6" : ""));
                Uri uri = getContext().getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("no uri");
                try (java.io.OutputStream os = getContext().getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new java.io.IOException("no stream");
                    os.write(data);
                }
                r.put("where", backup ? "folder" : "downloads");
            } else {
                java.io.File f = new java.io.File(getContext().getCacheDir(), name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { os.write(data); }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
                Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getActivity().startActivity(Intent.createChooser(send, "Save " + name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                r.put("where", "share");
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Could not save the file");
        }
    }

    // ── Checks alarm ringtones ──
    private android.media.MediaPlayer preview;
    private final android.os.Handler previewStop = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Keeps the person's own ringtone (one) in the app's private storage. */
    @PluginMethod
    public void saveTone(PluginCall call) {
        String b64 = call.getString("base64", "");
        try {
            byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            if (data.length == 0 || data.length > 8 * 1024 * 1024) { call.reject("Choose a sound file under 8 MB"); return; }
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(AlarmService.ownTone(getContext()))) { os.write(data); }
            call.resolve();
        } catch (Exception e) { call.reject("Could not save the sound"); }
    }

    /** Plays a ringtone for a few seconds: an app tone id, "phone" (the phone's default alarm) or "own". */
    @PluginMethod
    public void previewTone(PluginCall call) {
        stopPreviewNow();
        String tone = call.getString("tone", "rise");
        android.net.Uri uri;
        if (tone != null && tone.startsWith("content://")) uri = android.net.Uri.parse(tone);
        else if ("phone".equals(tone)) {
            uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(getContext(), android.media.RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
        } else if ("own".equals(tone) && AlarmService.ownTone(getContext()).exists()) {
            uri = android.net.Uri.fromFile(AlarmService.ownTone(getContext()));
        } else uri = AlarmService.rawTone(getContext(), tone);
        try {
            preview = new android.media.MediaPlayer();
            preview.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            preview.setDataSource(getContext(), uri);
            preview.setLooping(!"phone".equals(tone) && !"own".equals(tone));
            preview.prepare(); preview.start();
            previewStop.postDelayed(this::stopPreviewNow, 6000);
            call.resolve();
        } catch (Exception e) { stopPreviewNow(); call.reject("Could not play this sound"); }
    }

    @PluginMethod
    public void stopPreview(PluginCall call) { stopPreviewNow(); call.resolve(); }

    /** The phone's own alarm sounds and ringtones (title and address), for the alarm Sound list. */
    @PluginMethod
    public void listTones(PluginCall call) {
        new Thread(() -> {
            org.json.JSONArray out = new org.json.JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int type : new int[]{android.media.RingtoneManager.TYPE_ALARM, android.media.RingtoneManager.TYPE_RINGTONE}) {
                try {
                    android.media.RingtoneManager rm = new android.media.RingtoneManager(getContext());
                    rm.setType(type);
                    android.database.Cursor c = rm.getCursor();
                    for (int i = 0; c != null && i < c.getCount() && out.length() < 80; i++) {
                        c.moveToPosition(i);
                        String title = c.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
                        android.net.Uri u = rm.getRingtoneUri(i);
                        if (title == null || u == null || !seen.add(title)) continue;
                        out.put(new JSONObject().put("title", title).put("uri", u.toString()));
                    }
                } catch (Exception ignored) {}
            }
            JSObject r = new JSObject(); r.put("tones", out); call.resolve(r);
        }, "ayu-tones").start();
    }

    private void stopPreviewNow() {
        previewStop.removeCallbacksAndMessages(null);
        try { if (preview != null) { preview.stop(); preview.release(); } } catch (Exception ignored) {}
        preview = null;
        notifyListeners("previewEnded", new JSObject());
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        openAndReturn(call, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), this::batteryUnrestricted);
    }
}
