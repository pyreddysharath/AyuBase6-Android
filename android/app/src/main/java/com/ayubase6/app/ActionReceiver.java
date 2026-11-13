package com.ayubase6.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A button on a reminder was tapped (Done, Not yet, Turn off this, Turn off all): carry it out without opening the app. */
public class ActionReceiver extends BroadcastReceiver {
    static final String EXTRA_ACTION = "ayu_action", EXTRA_TAG = "ayu_tag", EXTRA_TITLE = "ayu_title", EXTRA_BODY = "ayu_body";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String key = intent.getStringExtra(ReminderScheduler.EXTRA_KEY), action = intent.getStringExtra(EXTRA_ACTION);
        final String tag = intent.getStringExtra(EXTRA_TAG), title = intent.getStringExtra(EXTRA_TITLE), body = intent.getStringExtra(EXTRA_BODY);
        if (ReminderScheduler.A_EYES_DONE.equals(intent.getAction())) {   // Eyes (20-20-20): the countdown is over
            ReminderScheduler.eyesFinished(app, tag, title, intent.getStringExtra(ReminderScheduler.EXTRA_SECTION)); return;
        }
        if (ReminderScheduler.A_SNOOZE_OFF.equals(action) && key != null) {   // "Stop alarm" on the Snoozed note
            ReminderScheduler.cancelAlarmSnooze(app, key); AyuRemindersPlugin.alarmChanged(); return;
        }
        if (ReminderScheduler.A_EYES_START.equals(action) && tag != null) {   // Eyes (20-20-20): Start begins the countdown (also on a Preview)
            ReminderScheduler.eyesStart(app, tag, title); return;
        }
        if (key != null && key.startsWith("preview:")) { ReminderScheduler.cancel(app, tag); return; }   // a button on a Preview notification: just close it
        final PendingResult done = goAsync();
        new Thread(() -> {
            try {
                ReminderScheduler.applyAction(app, key, action, tag, title, body);
                if ("done".equals(action) && key != null) AlarmService.stopIfKey(key);
                AyuRemindersPlugin.actionsWaiting();   // the app, if it is open, applies it at once
            } finally { done.finish(); }
        }, "ayu-action").start();
    }
}
