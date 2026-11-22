package io.github.voicetrail;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class TaskAlerts {
    static final String TASK = "task-progress", REMINDER = "reminders";
    private static final int TASK_ID = 30;

    static void channels(Context c, boolean zh) {
        NotificationManager n = c.getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(TASK, zh ? "任务状态" : "Task status",
                NotificationManager.IMPORTANCE_LOW));
        n.createNotificationChannel(new NotificationChannel(REMINDER, zh ? "提醒" : "Reminders",
                NotificationManager.IMPORTANCE_DEFAULT));
    }
    static boolean enabled(Context c, String channel) {
        NotificationManager n = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = n.getNotificationChannel(channel);
        return (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) && n.areNotificationsEnabled()
                && ch != null && ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private static PendingIntent action(Context c, String op, String id) {
        Intent i = new Intent(c, MainActivity.class).setAction(op)
                .setData(new android.net.Uri.Builder().scheme("voicetrail").authority("task").appendPath(id).build())
                .putExtra("task",id);
        return PendingIntent.getActivity(c, op.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void status(Context c, boolean zh, String id, boolean active, boolean retry, String safeText) {
        if (!enabled(c,TASK)) return;
        PendingIntent details = action(c,"details",id);
        Notification.Builder b = new Notification.Builder(c,TASK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(zh ? "声迹助手" : "Voice Trail").setContentText(safeText)
                .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(details).setAutoCancel(!active);
        b.addAction(new Notification.Action.Builder(null,zh ? "详情" : "Details",details).build());
        if (active) b.addAction(new Notification.Action.Builder(null,zh ? "取消" : "Cancel",action(c,"cancel",id)).build());
        else if (retry) b.addAction(new Notification.Action.Builder(null,zh ? "重新执行" : "Retry",action(c,"retry",id)).build());
        c.getSystemService(NotificationManager.class).notify(TASK_ID,b.build());
    }
    static void clear(Context c) { c.getSystemService(NotificationManager.class).cancel(TASK_ID); }
}

