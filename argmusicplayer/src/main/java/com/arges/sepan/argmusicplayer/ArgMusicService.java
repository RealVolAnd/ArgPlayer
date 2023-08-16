package com.arges.sepan.argmusicplayer;

import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.AudioManager.STREAM_MUSIC;
import static com.arges.sepan.argmusicplayer.Enums.AudioState.NO_ACTION;
import static com.arges.sepan.argmusicplayer.Enums.AudioState.PAUSED;
import static com.arges.sepan.argmusicplayer.Enums.AudioState.PLAYING;
import static com.arges.sepan.argmusicplayer.Enums.AudioState.STOPPED;
import static com.arges.sepan.argmusicplayer.Enums.AudioType.URL;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

import com.arges.sepan.argmusicplayer.Callbacks.OnCompletedListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnEmbeddedImageReadyListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnErrorListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnPausedListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnPlayingListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnPlaylistAudioChangedListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnPlaylistStateChangedListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnPreparedListener;
import com.arges.sepan.argmusicplayer.Callbacks.OnTimeChangeListener;
import com.arges.sepan.argmusicplayer.Enums.AudioState;
import com.arges.sepan.argmusicplayer.Enums.AudioType;
import com.arges.sepan.argmusicplayer.Enums.ErrorType;
import com.arges.sepan.argmusicplayer.Models.ArgAudio;
import com.arges.sepan.argmusicplayer.Models.ArgAudioList;
import com.arges.sepan.argmusicplayer.Notification.ArgNotification;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ArgMusicService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnErrorListener {
    private final Context context;

    private final int NOTIFICATION_ID = 404;
    private final String NOTIFICATION_DEFAULT_CHANNEL_ID = "default_channel";
    private final AudioManager audioManager;
    private final IBinder binder = new ArgMusicServiceBinder();
    protected AudioState audioState = NO_ACTION;
    protected String progressMessage = "Audio is loading..";
    protected boolean progressCancellation = false;
    protected boolean errorViewCancellation = false;
    protected boolean nextPrevButtons = true;
    protected int playButtonResId = R.drawable.arg_play;
    protected int pauseButtonResId = R.drawable.arg_pause;
    protected int repeatButtonResId = R.drawable.arg_repeat;
    protected int repeatNotButtonResId = R.drawable.arg_repeat_not;
    // After an incoming call or a notification, service gains audio focus. If player was paused, it should not play audio.
    // We have to check this situation to prevent unexpected playbacks
    boolean wasPlayingBeforeFocusLoss = false;
    private MediaPlayer mediaPlayer;
    private boolean isPlaylistActive = false;
    private boolean isRepeatPlaylist = false;
    private boolean playlistError = true;
    private final ArgAudioList currentPlaylist = new ArgAudioList(true, "ArgCih FromMusicService");
    private ArgAudio currentAudio;
    private OnPreparedListener onPreparedListener;
    private OnTimeChangeListener onTimeChangeListener;
    private OnPausedListener onPausedListener;
    private OnCompletedListener onCompletedListener;
    private OnErrorListener onErrorListener;
    private OnPlayingListener onPlayingListener;
    private OnPlaylistAudioChangedListener onPlaylistAudioChangedListener;
    private OnPlaylistStateChangedListener onPlaylistStateChangedListener;
    private OnEmbeddedImageReadyListener onEmbeddedImageReadyListener;
    private int playAudioPercent = 50;
    private boolean audioFocusHasRequested = false;
    private Activity currentActivity;
    MediaSessionCompat.Token currentMediaToken;

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AUDIOFOCUS_GAIN:
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                    mediaPlayer.setVolume(1f, 1f);
                    //long timeDiffSinceLastPause = System.currentTimeMillis() - timeWhenPaused;
                    //if audio has been paused 10 or more minutes ago, do not resume
                    if (wasPlayingBeforeFocusLoss/* && timeDiffSinceLastPause < 10*60*1000*/)

                        continuePlaying();
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (audioState == PLAYING) mediaPlayer.setVolume(0.1f, 0.1f);
                    wasPlayingBeforeFocusLoss = audioState == PLAYING;
                    break;

                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    wasPlayingBeforeFocusLoss = audioState == PLAYING;
                    pause();
                    break;

                case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                    break;
                default:
            }
        }
    };

    private final MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();

    private final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_STOP
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    );

    private MediaSessionCompat mediaSession;
    private boolean audioFocusRequested = false;
    private AudioFocusRequest audioFocusRequest;

    private final MusicRepository musicRepository = new MusicRepository();

    private MediaSessionCompat.Callback mediaSessionCallback = new MediaSessionCompat.Callback() {

        private Uri currentUri;
        int currentState = PlaybackStateCompat.STATE_STOPPED;

        @Override
        public void onPlay() {
            if (!mediaPlayer.isPlaying()) {
               //startService(new Intent(getApplicationContext(), PlayerService.class));

                MusicRepository.Track track = musicRepository.getCurrent();
                updateMetadataFromTrack(track);

            //    prepareToPlay(track.getUri());

                if (!audioFocusRequested) {
                    audioFocusRequested = true;

                    int audioFocusResult;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioFocusResult = audioManager.requestAudioFocus(audioFocusRequest);
                    } else {
                        audioFocusResult = audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    }
                    if (audioFocusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        return;
                }

                mediaSession.setActive(true); // Сразу после получения фокуса

             //   registerReceiver(becomingNoisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

               // exoPlayer.setPlayWhenReady(true);
                mediaPlayer.start();
            }

            mediaSession.setPlaybackState(stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1).build());
            currentState = PlaybackStateCompat.STATE_PLAYING;

            refreshNotificationAndForegroundStatus(currentState);
        }

        @Override
        public void onPause() {
            if (audioState == PLAYING)
                pause();

            mediaSession.setPlaybackState(stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1).build());
            currentState = PlaybackStateCompat.STATE_PAUSED;

            refreshNotificationAndForegroundStatus(currentState);
        }

        @Override
        public void onStop() {
            if (mediaPlayer.isPlaying()) {
                if (audioState == PLAYING)
                    pause();
               // unregisterReceiver(becomingNoisyReceiver);
            }

            if (audioFocusRequested) {
                audioFocusRequested = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(audioFocusChangeListener);
                }
            }

            mediaSession.setActive(false);

            mediaSession.setPlaybackState(stateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1).build());
            currentState = PlaybackStateCompat.STATE_STOPPED;

            refreshNotificationAndForegroundStatus(currentState);

            stopSelf();
        }

        @Override
        public void onSkipToNext() {
            MusicRepository.Track track = musicRepository.getNext();
            updateMetadataFromTrack(track);

            refreshNotificationAndForegroundStatus(currentState);

          //  prepareToPlay(track.getUri());
        }

        @Override
        public void onSkipToPrevious() {
            MusicRepository.Track track = musicRepository.getPrevious();
            updateMetadataFromTrack(track);

            refreshNotificationAndForegroundStatus(currentState);

           // prepareToPlay(track.getUri());

        }

        /*
        private void prepareToPlay(Uri uri) {
            if (!uri.equals(currentUri)) {
                currentUri = uri;
                ExtractorMediaSource mediaSource = new ExtractorMediaSource(uri, dataSourceFactory, extractorsFactory, null, null);
               mediaPlayer.prepare(mediaSource);
            }
        }
*/
        private void updateMetadataFromTrack(MusicRepository.Track track) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, BitmapFactory.decodeResource(context.getResources(), track.getBitmapResId()));
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle());
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.getArtist());
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getArtist());
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.getDuration());
            mediaSession.setMetadata(metadataBuilder.build());
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return new PlayerServiceBinder();
    }

    public class PlayerServiceBinder extends Binder {
        public MediaSessionCompat.Token getMediaSessionToken() {
            return mediaSession.getSessionToken();
        }
    }

    public ArgMusicService() {
        this.context = App.getAppContext();
        audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);

        currentPlaylist.setOnAudioAddedToPlaylistListener((audio, wasRemoved) -> onPlaylistAudioChangedListener.onPlaylistAudioChanged(currentPlaylist, currentPlaylist.getCurrentIndex()));
    }

    // region <setters-getters>
    protected void setMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @SuppressLint("WrongConstant") NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_DEFAULT_CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManagerCompat.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setAudioAttributes(audioAttributes)
                    .build();
        }
        mediaSession = new MediaSessionCompat(context, "PlayerService");

        // FLAG_HANDLES_MEDIA_BUTTONS - хотим получать события от аппаратных кнопок
        // (например, гарнитуры)
        // FLAG_HANDLES_TRANSPORT_CONTROLS - хотим получать события от кнопок
        // на окне блокировки
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // Отдаем наши коллбэки
        mediaSession.setCallback(mediaSessionCallback);

       // Context appContext = getApplicationContext();

        // Укажем activity, которую запустит система, если пользователь
        // заинтересуется подробностями данной сессии


        Intent activityIntent = new Intent(context, currentActivity.getClass());
        mediaSession.setSessionActivity(
                PendingIntent.getActivity(context, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE));
    }
    protected void setOnPreparedListener(OnPreparedListener onPreparedListener) {
        this.onPreparedListener = onPreparedListener;
    }

    protected void setOnTimeChangeListener(OnTimeChangeListener onTimeChangeListener) {
        this.onTimeChangeListener = onTimeChangeListener;
    }

    protected void setOnPausedListener(OnPausedListener onPausedListener) {
        this.onPausedListener = onPausedListener;
    }

    protected void setOnCompletedListener(OnCompletedListener onCompletedListener) {
        this.onCompletedListener = onCompletedListener;
    }

    protected void setOnErrorListener(OnErrorListener onErrorListener) {
        this.onErrorListener = onErrorListener;
    }

    protected void setOnPlayingListener(OnPlayingListener onPlayingListener) {
        this.onPlayingListener = onPlayingListener;
    }

    protected void setOnPlaylistAudioChangedListener(OnPlaylistAudioChangedListener onPlaylistAudioChangedListener) {
        this.onPlaylistAudioChangedListener = onPlaylistAudioChangedListener;
    }

    protected void setOnPlaylistStateChangedListener(OnPlaylistStateChangedListener onPlaylistStateChangedListener) {
        this.onPlaylistStateChangedListener = onPlaylistStateChangedListener;
    }

    protected void setOnEmbeddeImageReadyListener(OnEmbeddedImageReadyListener onEmbeddedImageReadyListener) {
        this.onEmbeddedImageReadyListener = onEmbeddedImageReadyListener;
    }

    protected long getDuration() {
        return mediaPlayer != null && audioState != NO_ACTION ? mediaPlayer.getDuration() : -1;
    }

    protected long getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : -1;
    }

    protected ArgAudio getCurrentAudio() {
        return currentAudio;
    }

    protected void setCurrentAudio(@NonNull ArgAudio audio) {
        this.currentAudio = audio;
    }

    protected ArgAudioList getCurrentPlaylist() {
        return this.currentPlaylist;
    }

    protected void setCurrentPlaylist(ArgAudioList argAudioList) {
        currentPlaylist.clear();
        currentPlaylist.addAll(argAudioList.getAll());
    }

    protected boolean getRepeatPlaylist() {
        return isRepeatPlaylist;
    }

    protected void setRepeatPlaylist(boolean repeatPlaylist) {
        this.isRepeatPlaylist = repeatPlaylist;
        currentPlaylist.setRepeat(repeatPlaylist);
    }

    protected boolean getPlaylistError() {
        return playlistError;
    }

    protected void setPlaylistError(boolean playlistError) {
        this.playlistError = playlistError;
    }
    //endregion </setters-getters>

    protected void playAudioAfterPercent(int percent) {
        this.playAudioPercent = percent;
    }

    protected AudioState getAudioState() {
        return audioState;
    }
    // </checkers>

    public void setAudioState(AudioState audioState) {
        this.audioState = audioState;
    }

    protected boolean isPlaylist() {
        return isPlaylistActive;
    }
    //endregion </ServiceOverrides>

    protected boolean isCurrentAudio(ArgAudio audio) {
        return audio != null && audio.equals(currentAudio);
    }

    private boolean isAudioValid(String path, AudioType type) {
        switch (type) {
            case ASSETS:
                try {
                    return context.getAssets().openFd(path) != null;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            case RAW:
                return context.getResources().getIdentifier(path, "raw", context.getPackageName()) != 0;
            case URL:
                return path.startsWith("http") || path.startsWith("https");
            case FILE_PATH:
                return new File(path).exists();
            default:
                return false;
        }
    }





    //region  <ServiceOverrides>
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action != null)
            switch (action) {
                case "com.arges.intent.service.PLAYPAUSE":
                    if (audioState == PLAYING)
                        pause();
                    else
                        continuePlaying();
                    break;
                case "com.arges.intent.service.STOP":
                    stop();
                    break;
                case "com.arges.intent.service.NEXT":
                    playNextAudio();
                    break;
                case "com.arges.intent.service.PREV":
                    playPrevAudio();
                    break;
                case "com.arges.intent.service.CONTINUE":
                    continuePlaying();
                    break;
            }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate(){
        int s = 1;
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.release();
    }

    protected boolean preparePlaylistToPlay(@NonNull ArgAudioList playlist) {
        final String temp = currentPlaylist.getStringForComparison();

        playlist.setOnAudioAddedToPlaylistListener((audio, wasRemoved) -> currentPlaylist.add(audio));

        currentPlaylist.clear();
        currentPlaylist.addAll(playlist.getAll());

        if (currentPlaylist.size() == 0) {
            killMediaPlayer();
            publishError(ErrorType.EMPTY_PLAYLIST, "Seems you have loaded an empty playlist!");
            return false;
        }

        if (currentPlaylist.getStringForComparison().equals(temp))
            return false;

        isPlaylistActive = true;
        currentPlaylist.setRepeat(getRepeatPlaylist());
        onPlaylistStateChangedListener.onPlaylistStateChanged(true, currentPlaylist);
        return true;
    }

    protected void setRootActivity(Activity rootActivity) {
        currentActivity = rootActivity;
    }
    protected MediaSessionCompat.Token getCurrentMediaToken() {
        return mediaSession.getSessionToken();
    }

    protected void playAudio(ArgAudio audio) {
        ArgAudio temp = currentAudio;
        currentAudio = audio;
        if (audio == null) {
            killMediaPlayer();
            publishError(ErrorType.NO_AUDIO_SET, "Seems you haven't not loaded an audio yet!");
        } else {
            if (audio.equals(temp)) return;
            if (isAudioValid(audio.getPath(), audio.getType())) {
                try {

                    killMediaPlayer();
                    mediaPlayer = getLoadedMediaPlayer(context, audio);
                    mediaPlayer.setOnPreparedListener(this);
                    mediaPlayer.setOnBufferingUpdateListener(this);
                    mediaPlayer.setOnCompletionListener(this);
                    mediaPlayer.setOnErrorListener(this);

                    if (audio.getType() == URL)
                        mediaPlayer.prepareAsync();
                    else
                        mediaPlayer.prepare();

                    mediaPlayerTimeOutCheck();
                    mediaSessionCallback.onPlay();
                    // Other actions will be performed in onBufferingUpdate and OnPrepared methods
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                publishInvalidFileError(audio.getType(), audio.getPath());
            }
        }
    }

    protected void playPlaylistItem(int index) {
        if (index == currentPlaylist.getCurrentIndex())
            return;
        if (isPlaylist() && !(index < 0 || index >= currentPlaylist.size())) {
            pauseMediaPlayer();
            //setAudioState(NO_ACTION);
            currentPlaylist.goTo(index);
            onPlaylistAudioChangedListener.onPlaylistAudioChanged(currentPlaylist, currentPlaylist.getCurrentIndex());
        } else {
            publishError(ErrorType.NO_AUDIO_SET, "Invalid index or Empty Playlist");
        }
    }

    protected void playSingleAudio(ArgAudio audio) {   // Use when play new single audio, not for resuming a paused audio
        isPlaylistActive = false;
        currentPlaylist.clear();
        onPlaylistStateChangedListener.onPlaylistStateChanged(false, null);
        playAudio(audio);
    }

    protected void pause() {
        if (mediaPlayer != null) {
            pauseMediaPlayer();
            onPausedListener.onPaused();
        }
    }

    protected void continuePlaying() {
        if (mediaPlayer != null) {

            startMediaPlayer();
            updateTimeThread();
            onPlayingListener.onPlaying();
        }
    }

    protected void replayAudio(ArgAudio audio) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(0);
            mediaPlayer.start();
        } else {
            playAudio(audio);
        }
    }

    protected void playNextAudio() {
        playPlaylistItem(currentPlaylist.getNextIndex());
    }

    protected void playPrevAudio() {
        playPlaylistItem(currentPlaylist.getPrevIndex());
    }

    protected void stop() {
        if (mediaPlayer != null) {
            pauseMediaPlayer();
            mediaPlayer.seekTo(0);
        }
    }

    protected boolean seekTo(int time) {
        if (mediaPlayer != null && time <= getDuration()) {
            mediaPlayer.seekTo(time);
            return true;
        }
        return false;
    }

    protected boolean forward(int milliSec, boolean willPlay) {
        if (mediaPlayer != null) {
            int seekTime = mediaPlayer.getCurrentPosition() + milliSec;
            if (seekTime > getDuration())
                return false;

            seekTo(seekTime);
            if (willPlay) continuePlaying();
            return true;
        }
        return false;
    }

    protected boolean backward(int milliSec, boolean willPlay) {
        if (mediaPlayer != null) {
            int seekTime = mediaPlayer.getCurrentPosition() - milliSec;
            if (seekTime < 0) return false;
            seekTo(seekTime);
            if (willPlay) continuePlaying();
            return true;
        }
        return false;
    }

    private void mediaPlayerTimeOutCheck() {
        new Handler().postDelayed(() -> {
            if (audioState == NO_ACTION)
                if (playlistError)
                    publishError(ErrorType.MEDIAPLAYER_TIMEOUT, "Url resource has not been prepared in 30 seconds");
                else
                    playNextAudio();
        }, 30000);
    }



    //region <MediaPlayerOverrides>
    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        if (percent > playAudioPercent && audioState == NO_ACTION) {
            startMediaPlayer();
            updateTimeThread();
            onPlayingListener.onPlaying();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        String errDescription = "MediaPlayer.OnError: \nwhat:" + what + ",\nextra:" + extra;
        publishError(ErrorType.MEDIAPLAYER_ERROR, errDescription);
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (currentAudio == null)
            return;

        if (currentAudio.getType() != URL) {
            onPlayingListener.onPlaying();
            startMediaPlayer();
            updateTimeThread();
        }
        onPreparedListener.onPrepared(currentAudio, mediaPlayer.getDuration());
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        stopMediaPlayer();
        if (isPlaylist() && currentPlaylist.hasNext()) {
            currentPlaylist.goToNext();
            onPlaylistAudioChangedListener.onPlaylistAudioChanged(currentPlaylist, currentPlaylist.getCurrentIndex());
        } else
            onCompletedListener.onCompleted();
    }
    //endregion </MediaPlayerOverrides>

    private void publishInvalidFileError(AudioType type, String path) {
        switch (type) {
            case ASSETS:
                publishError(ErrorType.INVALID_AUDIO, "The file is not an assets file. Assets Id:" + path);
                break;
            case RAW:
                publishError(ErrorType.INVALID_AUDIO, "The raw id is not valid. Raw Id:" + path);
                break;
            case URL:
                publishError(ErrorType.INVALID_AUDIO, "Url not valid. Url:" + path);
                break;
            case FILE_PATH:
                publishError(ErrorType.INVALID_AUDIO, "The file path is not valid. File Path:" + path + "\n Have you add File Access Permission to your project?");
                break;
            default:
                break;
        }
    }

    private void publishError(ErrorType type, String description) {
        killMediaPlayer();
        onErrorListener.onError(type, description);
    }

    protected void updateTimeThread() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(
                () -> {
                    if(audioState == PAUSED)
                        executor.shutdown();
                    else
                        onTimeChangeListener.onTimeChanged(mediaPlayer.getCurrentPosition());
                },
                0,
                1000,
                TimeUnit.MILLISECONDS
        );
    }

    private MediaPlayer getLoadedMediaPlayer(Context context, ArgAudio audio) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        AssetFileDescriptor descriptor;
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        switch (audio.getType()) {
            case ASSETS:
                descriptor = context.getAssets().openFd(audio.getPath());
                player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                retriever.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                descriptor.close();
                break;
            case RAW:
                descriptor = context.getResources().openRawResourceFd(Integer.parseInt(audio.getPath()));
                player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                retriever.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                descriptor.close();
                break;
            case URL:
                player.setDataSource(audio.getPath());
                break;
            case FILE_PATH:
                player.setDataSource(context, Uri.parse(audio.getPath()));
                retriever.setDataSource(context, Uri.parse(audio.getPath()));
                break;
            default:
                break;
        }

        onEmbeddedImageReadyListener.onEmbeddedImageReady(audio.getType() != AudioType.URL ? retriever.getEmbeddedPicture() : null);

        return player;
    }

    private void startMediaPlayer() {
        requestAudioFocus();
        if (audioFocusHasRequested) {
            mediaPlayer.start();
            setAudioState(PLAYING);
        }
    }

    private void pauseMediaPlayer() {
        if (audioState == PLAYING) {
            mediaPlayer.pause();
            setAudioState(PAUSED);
        }
    }

    private void stopMediaPlayer() {
        if (audioState == PLAYING) {
            mediaPlayer.stop();
            setAudioState(STOPPED);
            abandonAudioFocus();
        }
    }

    private void killMediaPlayer() {
        setAudioState(NO_ACTION);
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFocusRequest afr = new AudioFocusRequest.Builder(AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAudioAttributes(attributes)
                    .build();
            audioFocusHasRequested = audioManager.requestAudioFocus(afr) == AUDIOFOCUS_REQUEST_GRANTED;
        } else
            audioFocusHasRequested = audioManager.requestAudioFocus(audioFocusChangeListener, STREAM_MUSIC, AUDIOFOCUS_GAIN) == AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        audioFocusHasRequested = audioManager.abandonAudioFocus(audioFocusChangeListener) != AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ArgNotification.close(context);
        super.onTaskRemoved(rootIntent);
    }

    private void refreshNotificationAndForegroundStatus(int playbackState) {
        switch (playbackState) {
            case PlaybackStateCompat.STATE_PLAYING: {
               this.startForeground(NOTIFICATION_ID, getNotification(playbackState));
                break;
            }
            case PlaybackStateCompat.STATE_PAUSED: {
                NotificationManagerCompat.from(ArgMusicService.this).notify(NOTIFICATION_ID, getNotification(playbackState));
                stopForeground(false);
                break;
            }
            default: {
                stopForeground(true);
                break;
            }
        }
    }

    private Notification getNotification(int playbackState) {
        NotificationCompat.Builder builder = MediaStyleHelper.from(context, mediaSession);
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
        builder.setChannelId(NOTIFICATION_DEFAULT_CHANNEL_ID);

        return builder.build();
    }

    public class ArgMusicServiceBinder extends Binder {
        ArgMusicService getService() {
            return ArgMusicService.this;
        }
    }
}
