package io.github.voicetrail;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.time.ZonedDateTime;
import java.util.UUID;

public final class ReminderReceiver extends BroadcastReceiver {
    static boolean schedule(Context c, int hour, int minute, String label, boolean zh) {
        if (!TaskAlerts.enabled(c,TaskAlerts.REMINDER)) return false;
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime due = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!due.isAfter(now)) due = due.plusDays(1);
        Intent i = new Intent(c, ReminderReceiver.class)
                .setData(new Uri.Builder().scheme("voicetrail").authority("reminder")
                        .appendPath(UUID.randomUUID().toString()).build())
                .putExtra("label",label).putExtra("zh",zh);
        PendingIntent pi = PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                due.toInstant().toEpochMilli(),pi);
        return true;
    }
    @Override public void onReceive(Context c, Intent i) {
        boolean zh = i.getBooleanExtra("zh",true);
        TaskAlerts.channels(c,zh);
        if (!TaskAlerts.enabled(c,TaskAlerts.REMINDER) || i.getData()==null) return;
        String title = zh ? "声迹提醒" : "Voice Trail reminder";
        String body = i.getStringExtra("label");
        PendingIntent open = PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification generic = new Notification.Builder(c,TaskAlerts.REMINDER)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).build();
        Notification actual = new Notification.Builder(c,TaskAlerts.REMINDER)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
                .setContentText(body == null || body.isEmpty() ? title : body)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(generic)
                .setContentIntent(open).setAutoCancel(true).build();
        c.getSystemService(NotificationManager.class).notify(i.getData().toString(),1,actual);
    }
}

