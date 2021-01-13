package com.eightbit85.simple_am2.internal;


import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.media.AudioAttributesCompat;
import androidx.media2.common.MediaItem;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.AudioListener;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataOutput;
import com.google.common.base.Preconditions;

import java.util.List;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static com.eightbit85.simple_am2.internal.TaskCoordinator.MEDIA_ERROR_UNKNOWN;
@RestrictTo(LIBRARY_GROUP_PREFIX)
public class ExoPlayerWrapper {

  interface WrapperListener {
    void onPrepared(MediaItem mediaItem);
    void onTrackChanged(MediaItem item, int index);
    void onStartBufferPolling();
    void onStopBufferPolling();
    void onBufferingStarted(MediaItem mediaItem);
    void onBufferingUpdate(MediaItem mediaItem, int percent);
    void onBuffered(MediaItem mediaItem);
    void onSeekCompleted();
    void onError(MediaItem mediaItem, int error);
  }

  public static final long UNKNOWN_TIME = Long.MIN_VALUE;

  private static final String logTag = "SMP2: ExoPlayerWrapper";

  private SimpleExoPlayer exoPlayer;
  private Context context;
  private Looper looper;
  private MediaSourceManager mediaSourceManager;
  private WrapperListener listener;

  private boolean isPrepared;
  private boolean isBuffering;

  ExoPlayerWrapper(Context context, Looper looper, WrapperListener listener) {
    this.context = context.getApplicationContext();
    this.looper = looper;
    this.listener = listener;
  }

  // Class related

  public void reset() {
    if (exoPlayer != null) {
      exoPlayer.setPlayWhenReady(false); // stop any playback
      exoPlayer.release();
      mediaSourceManager.clear();
    }

    ExoEventListener exoListener = new ExoEventListener();
    /* TODO: investigate better options than defaults */
    exoPlayer = new SimpleExoPlayer.Builder(context)
      .setLooper(looper)
      .build();
    exoPlayer.addListener(exoListener);
    exoPlayer.addAudioListener(exoListener);
    exoPlayer.addMetadataOutput(exoListener);
    exoPlayer.addAnalyticsListener(exoListener);

    mediaSourceManager = new MediaSourceManager(context, "SimpleAudioPlayer");

    isPrepared = false;
    isBuffering = false;
  }

  public void close() {
    if (exoPlayer != null) {
      exoPlayer.release();
      exoPlayer = null;
      mediaSourceManager.clear();
    }
  }

  // Admin related

  public void setAudioAttributes(AudioAttributesCompat audioAttributes) {
    AudioAttributes aa = new AudioAttributes.Builder()
      .setContentType(audioAttributes.getContentType())
      .setFlags(audioAttributes.getFlags())
      .setUsage(audioAttributes.getUsage())
      .build();
    exoPlayer.setAudioAttributes(aa);
  }

  public AudioAttributesCompat getAudioAttributes() {
    AudioAttributes aa = exoPlayer.getAudioAttributes();
    return new AudioAttributesCompat.Builder()
      .setContentType(aa.contentType)
      .setFlags(aa.flags)
      .setUsage(aa.usage)
      .build();
  }

  public void prepare() {
    Preconditions.checkState(!isPrepared);
    exoPlayer.prepare(mediaSourceManager.getConcatMediaSource());
  }

  public void notifySomethingReady() {
    MediaItem mediaItem = mediaSourceManager.getCurrentMediaItem();
    if (!isPrepared) {
      isPrepared = true;
      listener.onPrepared(mediaItem);
    }

    if (isBuffering) {
      // stopped buffering
      isBuffering = false;
      listener.onBuffered(mediaItem);
    }
  }

  public void notifySomethingBuffering() {
    if (isPrepared && !isBuffering) {
      isBuffering = true;
      listener.onBufferingStarted(getCurrentMediaItem());
    }
  }

  void updateBuffering() {
    if (mediaSourceManager.isCurrentRemote()) {
      listener.onBufferingUpdate(getCurrentMediaItem(), exoPlayer.getBufferedPercentage());
    }
  }

  // Playlist Related

  public void setMediaItem(MediaItem mediaItem) {
    mediaSourceManager.setMediaItem(mediaItem);
  }

  public MediaItem getCurrentMediaItem() {
    return mediaSourceManager.getCurrentMediaItem();
  }

  public void setPlaylist(List<MediaItem> playlist) {
    mediaSourceManager.setMediaItems(playlist);
  }

  public List<MediaItem> getPlaylist() {
    return mediaSourceManager.getPlaylist();
  }

  public void addToPlaylist(int index, MediaItem item) {
    mediaSourceManager.addItem(index, item);
  }

  public void removeFromPlaylist(int index) {
    mediaSourceManager.removeItem(index);
  }

  public void replacePlaylistItem(int index, MediaItem item) {
    mediaSourceManager.replaceItem(index, item);
  }

  public void movePlaylistItem(int from, int to) {
    mediaSourceManager.moveItem(from, to);
  }

  // Playback related

  public void play() {
    if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
      exoPlayer.seekTo(0, 0);
    }
    exoPlayer.setPlayWhenReady(true);
  }

  public void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  public void skipForward() {
    int next = exoPlayer.getNextWindowIndex();
    if (next >= 0) {
      exoPlayer.seekToDefaultPosition(exoPlayer.getNextWindowIndex());
    }
  }

  public void skipBackward() {
    int prev = exoPlayer.getPreviousWindowIndex();
    if (prev >= 0) {
      exoPlayer.seekToDefaultPosition(exoPlayer.getPreviousWindowIndex());
    } else {
      exoPlayer.seekTo(0);
    }
  }

  public void skipToIndex(int index) {
    Preconditions.checkElementIndex(index, mediaSourceManager.getPlaylistSize());
    exoPlayer.seekToDefaultPosition(index);
  }

  public void setRepeatMode(int repeatMode) {
    exoPlayer.setRepeatMode(repeatMode);
  }

  public int getRepeatMode() {
    return exoPlayer.getRepeatMode();
  }

  public void seekTo(long position) {
    exoPlayer.seekTo(position);
  }

  public void setShuffleMode(boolean enabled) {
    exoPlayer.setShuffleModeEnabled(enabled);
  }

  // Info related

  public long getCurrentPosition() {
    return Math.max(0, exoPlayer.getCurrentPosition());
  }

  public long getBufferedPosition() {
    return exoPlayer.getBufferedPosition();
  }

  public long getDuration() {
    long duration = exoPlayer.getDuration();
    return duration == C.TIME_UNSET ? -1 : duration;
  }

  public PlaybackParameters getPlaybackParams() {
    return exoPlayer.getPlaybackParameters();
  }

  public void setVolume(float volume) {
    exoPlayer.setVolume(volume);
  }

  public float getVolume() {
    return exoPlayer.getVolume();
  }



  /******************** Divider *********************/



  public class ExoEventListener implements Player.EventListener, AudioListener, AnalyticsListener, MetadataOutput {

    // Player.EventListener

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
      if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
        listener.onStartBufferPolling();
      } else {
        listener.onStopBufferPolling();
      }

      switch (state) {
        case Player.STATE_READY:
          Log.d(logTag, "STATE - READY");
          notifySomethingReady();
          break;
        case Player.STATE_ENDED:
          Log.d(logTag, "STATE - ENDED");
          if (isPrepared) exoPlayer.setPlayWhenReady(true); // we are changing playlists
          break;
        case Player.STATE_BUFFERING:
          Log.d(logTag, "STATE - BUFFERING");
          notifySomethingBuffering();
          break;
        case Player.STATE_IDLE:
          // Nothing yet
        default:
          Log.d(logTag, "OTHER STATE - " + state);
//          throw new IllegalStateException();
      }
    }

    @Override
    public void onSeekProcessed() {
      listener.onSeekCompleted();
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
      Log.d(logTag, "Position Discontinuity " + reason);

      switch (reason) {
        case Player.DISCONTINUITY_REASON_PERIOD_TRANSITION:
        case Player.DISCONTINUITY_REASON_SEEK:
          boolean trackChanged = mediaSourceManager.onPlayerDiscontinuity(true, exoPlayer.getCurrentWindowIndex());
          if (trackChanged) {
            listener.onTrackChanged(getCurrentMediaItem(), mediaSourceManager.getCurrentIndex());
          }
          break;
        default:
          // nothing yet
          break;
      }
    }

    //TODO: get meaningful error information for user
    @Override
    public void onPlayerError(ExoPlaybackException error) {
      listener.onError(getCurrentMediaItem(), MEDIA_ERROR_UNKNOWN);
    }

    // AudioListener

    @Override
    public void onAudioSessionId(int audioSessionId) {

    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes audioAttributes) {

    }

    @Override
    public void onVolumeChanged(float volume) {

    }

    // AnalyticsListener

    @Override
    public void onAudioSessionId(EventTime eventTime, int audioSessionId) {
      Log.d(logTag, "AudioSessionId changed to " + audioSessionId);
    }

    // MetaDataOutput

    @Override
    public void onMetadata(@NonNull Metadata metadata) {

    }

  }

}