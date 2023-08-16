package com.arges.sepan.argmusicplayer.Callbacks;

import android.app.Notification;
import android.app.NotificationChannel;

import androidx.core.app.NotificationCompat;


public interface OnBuildNotificationListener {
    void onBuildNotification(Notification.Builder builder);

    void onBuildNotificationChannel(NotificationChannel notificationChannel);
}
