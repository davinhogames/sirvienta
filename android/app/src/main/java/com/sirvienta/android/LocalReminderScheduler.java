package com.sirvienta.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

final class LocalReminderScheduler {
    private static final long MAX_DELAY_MILLIS = 30L * 24L * 60L * 60L * 1_000L;

    private LocalReminderScheduler() { }

    static void schedule(Context context, String message, long delayMillis) {
        if (delayMillis <= 0 || delayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("El recordatorio debe ser de hasta 30 días.");
        }
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            throw new IllegalStateException("Android no ofrece el servicio de alarmas.");
        }

        long requestId = System.currentTimeMillis();
        Intent intent = new Intent(context, LocalReminderReceiver.class)
                .setAction(LocalReminderReceiver.ACTION_REMINDER)
                .setData(Uri.parse("sirvienta://reminder/" + requestId))
                .putExtra(LocalReminderReceiver.EXTRA_MESSAGE, message);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMillis,
                pendingIntent);
    }
}
