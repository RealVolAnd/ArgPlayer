package com.arges.sepan.argmusicplayer.Models;

import androidx.annotation.RawRes;

import com.arges.sepan.argmusicplayer.Enums.AudioType;
import com.arges.sepan.argmusicplayer.R;

import java.util.concurrent.ThreadLocalRandom;

public class ArgAudio {
    private String singer, audioName, path;
    private int bitmapResId;
    private long duration;
    private boolean isPlaylist = false;
    private AudioType type;
    private int id = -1;

    int[] bkgIds = {
            R.drawable.image266680,
            R.drawable.image396168,
            R.drawable.image533998,
            R.drawable.image544064,
            R.drawable.image208815
    };

  //  int randomNum = ThreadLocalRandom.current().nextInt(0, 4 + 1);

    public ArgAudio(String singer, String audioName, String path, AudioType type) {
        this.singer = singer;
        this.audioName = audioName;
        this.path = path;
        this.type = type;
        this.bitmapResId = getRndBkg();
        this.duration = (3 * 60 + 41) * 1000;
    }

    public ArgAudio(int id, String singer, String audioName, String path, AudioType type) {
        this.singer = singer;
        this.audioName = audioName;
        this.path = path;
        this.type = type;
        this.id = id;
        this.bitmapResId = getRndBkg();
        this.duration = (3 * 60 + 41) * 1000;
    }

    private int getRndBkg(){
        return bkgIds[ThreadLocalRandom.current().nextInt(0, 4 + 1)];
    }

    public static ArgAudio createFromRaw(String singer, String audioName, @RawRes int rawId) {
        return new ArgAudio(singer, audioName, String.valueOf(rawId), AudioType.RAW);
    }

    public static ArgAudio createFromAssets(String singer, String audioName, String assetName) {
        return new ArgAudio(singer, audioName, assetName, AudioType.ASSETS);
    }

    public static ArgAudio createFromURL(String singer, String audioName, String url) {
        return new ArgAudio(singer, audioName, url, AudioType.URL);
    }

    public static ArgAudio createFromFilePath(String singer, String audioName, String filePath) {
        return new ArgAudio(singer, audioName, filePath, AudioType.FILE_PATH);
    }

    public ArgAudio cloneAudio() {
        return new ArgAudio(id, singer, audioName, path, type);
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public int getBkg() {
        return bitmapResId;
    }
    public long getDuration() {
        return duration;
    }

    public String getTitle() {
        return audioName;
    }

    public void setSinger(String singer) {
        this.singer = singer;
    }

    public String getSinger() {
        return singer;
    }

    public void setAudioName(String name) {
        this.audioName = name;
    }

    public String getAudioName() {
        return path;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public AudioType getType() {
        return type;
    }

    public void setType(AudioType type) {
        this.type = type;
    }

    public boolean isPlaylist() {
        return isPlaylist;
    }

    public ArgAudio convertToPlaylist() {
        isPlaylist = true;
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        else if (!(obj instanceof ArgAudio)) return false;
        else {
            ArgAudio a = (ArgAudio) obj;
            return this.getTitle().equals(a.getTitle())
                    && this.getType() == a.getType()
                    && this.getPath().equals(a.getPath())
                    && this.getId() == a.getId();
        }
    }
}
