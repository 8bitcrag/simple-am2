package com.eightbit85.simple_am2;


import android.content.Context;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;
import androidx.core.util.Pair;
import androidx.media.AudioAttributesCompat;
import androidx.media2.common.BaseResult;
import androidx.media2.common.MediaItem;
import androidx.media2.common.MediaMetadata;
import androidx.media2.common.SessionPlayer;

import com.eightbit85.simple_am2.internal.ExoWrapperFactory;
import com.eightbit85.simple_am2.internal.TaskCoordinator;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static androidx.media2.common.BaseResult.RESULT_ERROR_BAD_VALUE;
import static androidx.media2.common.BaseResult.RESULT_ERROR_INVALID_STATE;
import static androidx.media2.common.BaseResult.RESULT_ERROR_IO;
import static androidx.media2.common.BaseResult.RESULT_ERROR_PERMISSION_DENIED;
import static androidx.media2.common.BaseResult.RESULT_ERROR_UNKNOWN;
import static androidx.media2.common.BaseResult.RESULT_INFO_SKIPPED;
import static androidx.media2.common.BaseResult.RESULT_SUCCESS;


public class SimpleAudioPlayer extends SessionPlayer implements TaskCoordinator.BufferListener {

  interface SessionPlayerCallbackNotifier {
    void callCallback(PlayerCallback callback);
  }

  private static final String logTag = "OPT/ SMP2: SimpleAudioPlayer";

  // ExoPlayer related
  protected MediaMetadata playlistMetaData;
  protected int repeatMode;
  protected int shuffleMode;
  protected int state;
  protected int currentIndex;
  protected int currentSize;
  protected Map<MediaItem, Integer> bufferingStates = new HashMap<>();
  protected AudioFocusHandler audioFocusHandler;

  // Thread related
  protected TaskCoordinator taskCoordinator;
  protected final Object lockForState;

  // Status code related

  static ArrayMap<Integer, Integer> sResultCodeMap;
  static {
    sResultCodeMap = new ArrayMap<>();
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_NO_ERROR, RESULT_SUCCESS);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_ERROR_UNKNOWN, RESULT_ERROR_UNKNOWN);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_INVALID_OPERATION, RESULT_ERROR_INVALID_STATE);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_BAD_VALUE, RESULT_ERROR_BAD_VALUE);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_PERMISSION_DENIED, RESULT_ERROR_PERMISSION_DENIED);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_ERROR_IO, RESULT_ERROR_IO);
    sResultCodeMap.put(TaskCoordinator.CALL_STATUS_SKIPPED, RESULT_INFO_SKIPPED);
  }

  SimpleAudioPlayer(@NonNull Context context) {

    taskCoordinator = new TaskCoordinator(context.getApplicationContext(), this, ExoWrapperFactory.getDefaultFactory());

    lockForState = new Object();

    reset();
  }

  // SimpleAudioPlayer Specifics

  public void registerFocusHandler(AudioFocusHandler handler) {
    audioFocusHandler = handler;
  }

  /**
   * Rests the player to it's uninitialised state. Removes tasks, cancels futures, resets the
   * exoplayer.
   */
  public void reset() {
    taskCoordinator.reset();
    synchronized (lockForState) {
      state = SessionPlayer.PLAYER_STATE_IDLE;
      repeatMode = SessionPlayer.REPEAT_MODE_NONE;
      currentIndex = -1;
      currentSize = 0;
      shuffleMode = SessionPlayer.SHUFFLE_MODE_NONE;
      bufferingStates.clear();
    }
  }

  /**
   * Notifies the same callback on each registered SessionPlayer.PlayerCallback (i.e. for each
   * controller connected to the session).
   * @param notifier SessionPlayerCallbackNotifier that wraps the call to the specified callback.
   */
  protected void notifySessionPlayerCallback(final SimpleAudioPlayer.SessionPlayerCallbackNotifier notifier) {
    List<Pair<PlayerCallback, Executor>> callbacks = getCallbacks();
    for (Pair<PlayerCallback, Executor> pair : callbacks) {
      final PlayerCallback callback = pair.first;
      if (pair.second != null) pair.second.execute(() -> notifier.callCallback(callback));
    }
  }

  /**
   * Maybe changes the state of the player. It may seem odd that it notifies the session of state
   * changes even when there isn't a change, but this is because the notification is only updated
   * when this is triggered.
   * @param newState int of the state to change to
   */
  protected void changeState(int newState) {
    synchronized (lockForState) {
      state = newState;
      notifySessionPlayerCallback(callback -> callback.onPlayerStateChanged(this, state));
    }
  }

  // TaskCoordinator.BufferListener implementation

  @Override
  public void onTrackChanged(MediaItem item, int index) {
    currentIndex = index;
    notifySessionPlayerCallback(callback -> callback.onCurrentMediaItemChanged(this, item));
  }

  @Override
  public void setBufferingState(final MediaItem item, @BuffState final int state) {
    Integer previousState;
    synchronized (lockForState) {
      previousState = bufferingStates.put(item, state);
    }
    if (previousState == null || previousState != state) {
      notifySessionPlayerCallback(callback -> callback.onBufferingStateChanged(this, item, state));
    }
  }

  @Override
  public void onError(MediaItem item, int error) {
    changeState(PLAYER_STATE_ERROR);
  }

  @Override
  public Integer convertStatus(int status) {
    return sResultCodeMap.containsKey(status) ? sResultCodeMap.get(status) : RESULT_ERROR_UNKNOWN;
  }

  // SessionPlayer Implementation

  public @NonNull ListenableFuture<PlayerResult> play() {

    if (audioFocusHandler == null || audioFocusHandler.onPlay()) {
      return taskCoordinator.submit(taskCoordinator.play()
        .foreach(pr -> changeState(SessionPlayer.PLAYER_STATE_PLAYING)));
    }

    SettableFuture<PlayerResult> fut = SettableFuture.create();
    fut.set(new PlayerResult(PlayerResult.RESULT_ERROR_PERMISSION_DENIED, null)); // Was not allowed to play
    return fut;
  }


  public @NonNull ListenableFuture<PlayerResult> pause() {
    return taskCoordinator.submit(taskCoordinator.pause()
      .foreach(pr -> changeState(SessionPlayer.PLAYER_STATE_PAUSED)));
  }


  public @NonNull ListenableFuture<PlayerResult> prepare() {
    return taskCoordinator.submit(taskCoordinator.prepare()
      .foreach(pr -> changeState(SessionPlayer.PLAYER_STATE_PAUSED)));
  }


  public @NonNull ListenableFuture<PlayerResult> seekTo(long position) {
    return taskCoordinator.submit(taskCoordinator.seekTo(position)
      .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onSeekCompleted(this, position))));
  }


  public @NonNull ListenableFuture<PlayerResult> setPlaybackSpeed(float playbackSpeed) {  throw new UnsupportedOperationException("Setting the PlaybackSpeed is not supported in this version"); }


  public @NonNull ListenableFuture<PlayerResult> setAudioAttributes(@NonNull AudioAttributesCompat attributes) {
    return taskCoordinator.submit(taskCoordinator.setAudioAttributes(attributes)
      .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onAudioAttributesChanged(this, attributes))));
  }


  public @PlayerState
  int getPlayerState() {
//    Log.d(logTag, "getPlayerState");
    int s;
    synchronized (lockForState) {
      s = state;
    }
    return s;
  }


  public long getCurrentPosition() {
    return taskCoordinator.getCurrentPosition();
  }


  public long getDuration() {
    return taskCoordinator.getDuration();
  }


  public long getBufferedPosition() {
    return taskCoordinator.getBufferedPosition();
  }


  public @BuffState
  int getBufferingState() {
    Integer buffState;
    synchronized (lockForState) {
      buffState = bufferingStates.get(taskCoordinator.getCurrentMediaItem());
    }
    return buffState == null ? SessionPlayer.BUFFERING_STATE_UNKNOWN : buffState;
  }



  public float getPlaybackSpeed() {
    return taskCoordinator.getPlaybackSpeed();
  }


  public @NonNull ListenableFuture<PlayerResult> setPlaylist(@NonNull List<MediaItem> list, @Nullable MediaMetadata metadata) {
    playlistMetaData = metadata;
    currentSize = list.size();

    return taskCoordinator.submit(taskCoordinator.setPlaylist(list)
      .foreach(pr -> {
        notifySessionPlayerCallback(callback -> callback.onPlaylistChanged(this, list, metadata));
        onTrackChanged(pr.getMediaItem(), 0);
      }
    ));
  }


  public @Nullable AudioAttributesCompat getAudioAttributes() {
    return taskCoordinator.getAudioAttributes();
  }


  public @NonNull ListenableFuture<PlayerResult> setMediaItem(@NonNull MediaItem item) {
    playlistMetaData = null;
    currentSize = 1;

    return taskCoordinator.submit(taskCoordinator.setMediaItem(item)
      .foreach(pr -> onTrackChanged(item, 0)));
  }


  public @NonNull ListenableFuture<PlayerResult> addPlaylistItem(int index, @NonNull MediaItem item) {
    int i = Math.min(index, currentSize); // if index is greater than current size, put item on the end
    return taskCoordinator.submit(taskCoordinator.addPlaylistItem(i, item)
      .foreach(pr -> {
        currentSize++;
        notifySessionPlayerCallback(callback -> callback.onPlaylistChanged(this, taskCoordinator.getPlaylist(), playlistMetaData));
      }));
  }


  public @NonNull ListenableFuture<PlayerResult> removePlaylistItem(@IntRange(from = 0) int index) {
    return taskCoordinator.submit(taskCoordinator.removePlaylistItem(index)
      .foreach(pr -> {
        currentSize--;
        notifySessionPlayerCallback(callback -> callback.onPlaylistChanged(this, taskCoordinator.getPlaylist(), playlistMetaData));
      }));
  }


  public @NonNull ListenableFuture<PlayerResult> replacePlaylistItem(int index, @NonNull MediaItem item) {
    return taskCoordinator.submit(taskCoordinator.replacePlaylistItem(index, item)
      .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onPlaylistChanged(this, taskCoordinator.getPlaylist(), playlistMetaData))));
  }


  public @NonNull ListenableFuture<PlayerResult> movePlaylistItem(int from, int to) {
    return taskCoordinator.submit(taskCoordinator.movePlaylistItem(to, from)
      .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onPlaylistChanged(this, taskCoordinator.getPlaylist(), playlistMetaData))));
  }


  public @NonNull ListenableFuture<PlayerResult> skipToPreviousPlaylistItem() {
    return taskCoordinator.submit(taskCoordinator.skipToPreviousPlaylistItem());
  }


  public @NonNull ListenableFuture<PlayerResult> skipToNextPlaylistItem() {
    return taskCoordinator.submit(taskCoordinator.skipToNextPlaylistItem());
  }


  public @NonNull ListenableFuture<PlayerResult> skipToPlaylistItem(@IntRange(from = 0) int index) {
    return taskCoordinator.submit(taskCoordinator.skipToPlaylistItem(index));
  }


  public @NonNull ListenableFuture<PlayerResult> updatePlaylistMetadata(@Nullable MediaMetadata metadata) {
    SettableFuture<PlayerResult> future = SettableFuture.create();
    playlistMetaData = metadata;
    notifySessionPlayerCallback(callback -> callback.onPlaylistMetadataChanged(SimpleAudioPlayer.this, playlistMetaData));
    future.set(new PlayerResult(BaseResult.RESULT_SUCCESS, taskCoordinator.getCurrentMediaItem()));
    return future;
  }


  public @NonNull ListenableFuture<PlayerResult> setRepeatMode(@RepeatMode int mode) {
    repeatMode = (mode == SessionPlayer.REPEAT_MODE_GROUP) ? 2 : mode;
    return taskCoordinator.submit(taskCoordinator.setRepeatMode(repeatMode)
      .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onRepeatModeChanged(this, repeatMode))));
  }


  public @NonNull ListenableFuture<PlayerResult> setShuffleMode(@ShuffleMode int mode) {
    if (mode != shuffleMode) {
      shuffleMode = mode;
      boolean enable = shuffleMode != SessionPlayer.SHUFFLE_MODE_NONE;
      return taskCoordinator.submit(taskCoordinator.setShuffleMode(enable)
        .foreach(pr -> notifySessionPlayerCallback(callback -> callback.onShuffleModeChanged(this, shuffleMode))));
    }

    SettableFuture<PlayerResult> fut = SettableFuture.create();
    fut.set(new PlayerResult(PlayerResult.RESULT_SUCCESS, null)); // No need to consider it an error, operation was a success, it just didn't change anything
    return fut;
  }


  public @Nullable List<MediaItem> getPlaylist() {
    return playlistMetaData == null ? null : taskCoordinator.getPlaylist();
  }


  public @Nullable MediaMetadata getPlaylistMetadata() {
    return playlistMetaData;
  }


  public @RepeatMode int getRepeatMode() {
    return repeatMode;
  }


  public @ShuffleMode int getShuffleMode() {
    return shuffleMode;
  }


  public @Nullable MediaItem getCurrentMediaItem() {
    return taskCoordinator.getCurrentMediaItem();
  }


  public @IntRange(from = SessionPlayer.INVALID_ITEM_INDEX) int getCurrentMediaItemIndex() {
    return currentIndex;
  }


  public @IntRange(from = SessionPlayer.INVALID_ITEM_INDEX) int getPreviousMediaItemIndex() {
    int prev = currentIndex - 1;
    switch (repeatMode) {
      case SessionPlayer.REPEAT_MODE_NONE:
        prev = prev == -1 ? 0 : prev;
        break;
      case SessionPlayer.REPEAT_MODE_ONE:
        prev = currentIndex;
        break;
      case SessionPlayer.REPEAT_MODE_GROUP:
      case SessionPlayer.REPEAT_MODE_ALL:
        prev = prev == -1 ? currentSize - 1 : prev;
        break;
    }

    return prev;
  }


  public @IntRange(from = SessionPlayer.INVALID_ITEM_INDEX) int getNextMediaItemIndex() {
    int next = currentIndex + 1;
    switch (repeatMode) {
      case SessionPlayer.REPEAT_MODE_NONE:
        next = next == currentSize ? currentIndex : next;
        break;
      case SessionPlayer.REPEAT_MODE_ONE:
        next = currentIndex;
        break;
      case SessionPlayer.REPEAT_MODE_GROUP:
      case SessionPlayer.REPEAT_MODE_ALL:
        next = next == currentSize ? 0 : next;
        break;
    }

    return next;
  }


  public @NonNull ListenableFuture<PlayerResult> setVolume(float volume) {
    return taskCoordinator.submit(taskCoordinator.setVolume(volume));
  }

  public float getVolume() {
    return taskCoordinator.getVolume();
  }

  @Override
  public void close() {
    super.close();
    reset();
    synchronized (lockForState) {
      if (audioFocusHandler != null) audioFocusHandler.close();
      taskCoordinator.close();
    }
  }

  // Builder

  public static final class Builder {

    private Context ctx;
    private Function<SimpleAudioPlayer, AudioFocusHandler> focusFactory;
    private Executor ec;
    private PlayerCallback cb;
    private boolean hasCb;

    public Builder(Context context) {
      ctx = context;
      hasCb = false;
    }

    @NonNull
    public SimpleAudioPlayer.Builder setAudioFocusHandler(Function<SimpleAudioPlayer, AudioFocusHandler> factory) {
      focusFactory = factory;
      return this;
    }

    @NonNull
    public SimpleAudioPlayer.Builder setCallbacks(Executor executor, PlayerCallback callback) {
      ec = executor;
      cb = callback;
      hasCb = true;
      return this;
    }

    public SimpleAudioPlayer build() {
      SimpleAudioPlayer plyr = new SimpleAudioPlayer(ctx);
      if (focusFactory != null)  plyr.registerFocusHandler(focusFactory.apply(plyr));
      if (hasCb) plyr.registerPlayerCallback(ec, cb);
      return plyr;
    }

  }

}

