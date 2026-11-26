package com.ayubase6.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * There are no background update checks any more (App Updates → Check for update is manual).
 * Older versions set a repeating check alarm; schedule() only cancels it, so an updated app stops it the first time it opens.
 */
public class UpdateCheck extends BroadcastReceiver {
    private static final String ACTION = "com.ayubase6.app.UPDATE_CHECK";

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 7601, new Intent(c, UpdateCheck.class).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(c));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        schedule(context.getApplicationContext());   // an old alarm that still fires: cancel it
    }
}
