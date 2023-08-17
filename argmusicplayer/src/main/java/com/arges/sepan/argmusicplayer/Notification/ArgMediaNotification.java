package com.arges.sepan.argmusicplayer.Notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

import com.arges.sepan.argmusicplayer.MediaStyleHelper;
import com.arges.sepan.argmusicplayer.Models.ArgNotificationOptions;
import com.arges.sepan.argmusicplayer.R;

public class ArgMediaNotification extends Notification {
    private static int notificationId;
    protected final Notification notification;

    private final NotificationManager mNotificationManager;
    protected ArgNotificationOptions options;
    private MediaSessionCompat mediaSession;
    private Context context;
    private int currentPlaybackState = PlaybackStateCompat.STATE_NONE;

    public ArgMediaNotification(Context context, @NonNull ArgNotificationOptions options, MediaSessionCompat mediaSession) {
        super();

        this.options = options;
        this.context = options.getActivity().getApplicationContext();
        this.mediaSession = mediaSession;

        notificationId = options.getNotificationId();
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        //region create builder with min attributes

        Intent homeIntent = null;
        if (options.getActivity() != null) {
            homeIntent = new Intent(context, options.getActivity().getClass()).setAction("com.arges.intent.HOME");
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //region create notification channel
            NotificationChannel channel = new NotificationChannel("ArgPlayer-" + context.getPackageName(), "ArgPlayer", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notification from ArgPlayer");
            channel.enableLights(false);
            channel.enableVibration(false);

            if (options.getListener() != null)
                options.getListener().onBuildNotificationChannel(channel);

            mNotificationManager.createNotificationChannel(channel);

            notification = getNotification(currentPlaybackState,channel);
        } else {

            notification = getNotification(currentPlaybackState, null);
        }

        notification.flags |= Notification.FLAG_NO_CLEAR;   //FLAG_ONGOING_EVENT
    }

    public void setPlaybackState(int state) {
        currentPlaybackState = state;
    }

    private Notification getNotification(int playbackState, @Nullable NotificationChannel channel) {
        NotificationCompat.Builder builder = MediaStyleHelper.from(context, mediaSession);

     //   if (options.getListener() != null)
      //      options.getListener().onBuildNotification(builder);

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, context.getString(R.string.previous), MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));

        if (playbackState == PlaybackStateCompat.STATE_PLAYING)
            builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_pause, context.getString(R.string.pause), MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        else
            builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_play, context.getString(R.string.play), MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)));

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, context.getString(R.string.next), MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
        builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
                .setMediaSession(mediaSession.getSessionToken())); // setMediaSession требуется для Android Wear
        builder.setSmallIcon(R.drawable.ic_launcher);
        builder.setColor(ContextCompat.getColor(context, R.color.colorPrimaryDark)); // The whole background (in MediaStyle), not just icon background
        builder.setShowWhen(false);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(channel.getId());
        }

        return builder.build();
    }

    public static void close(Context context) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ArgMediaNotification.notificationId);
    }

    public void show() {
        mNotificationManager.notify(options.getNotificationId(), notification);
    }

    public void close() {
        if (contentView != null)
            mNotificationManager.cancel(options.getNotificationId());
    }

    public void startIsLoading(boolean hasNext, boolean hasPrev) {
        renew("Audio is loading...", 1, hasNext, hasPrev);
    }

    public boolean isEnabled() {
        return options.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        options.setEnabled(enabled);
        if (!enabled && isActive())
            close();
    }

    public boolean isActive() {
        return contentView != null;
    }





    public void renew(String name, int duration, boolean hasNext, boolean hasPrev) {
        /*
        if (options.isProgressEnabled()) {
            contentView.setTimeText(R.id.tvTimeTotalNotif, duration);
            contentView.setText(R.id.tvTimeNowNotif, "00:00");
            bigContentView.setTimeText(R.id.tvTimeTotalBigNotif, duration);
            bigContentView.setText(R.id.tvTimeNowBigNotif, "00:00");
            bigContentView.setText(R.id.tvAudioNameBigNotif, name);
            bigContentView.setImageResource(R.id.btnPlayPauseBigNotif, R.drawable.arg_pause);
            bigContentView.setVisibility(R.id.btnNextBigNotif, hasNext);
            bigContentView.setVisibility(R.id.btnPrevBigNotif, hasPrev);
        }

        contentView.setVisibility(R.id.btnNextNotif, hasNext);
        contentView.setText(R.id.tvAudioNameNotif, name);

        contentView.setImageResource(R.id.btnPlayPauseNotif, R.drawable.arg_pause);

         */
        show();
    }


}