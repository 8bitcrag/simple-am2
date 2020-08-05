package com.eightbit85.simple_am2.internal;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.core.util.ObjectsCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media2.common.MediaItem;
import androidx.media2.common.SessionPlayer;
import androidx.media2.player.MediaPlayer2;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;

@RestrictTo(LIBRARY_GROUP_PREFIX)
public class TaskCoordinator implements ExoPlayerWrapper.WrapperListener, AutoCloseable {

  //TODO: another way?
  private class PollBufferRunnable implements Runnable {
    @Override
    public void run() {
      pollBufferingState();
    }
  }

  public interface BufferListener {
    void setBufferingState(final MediaItem item, @SessionPlayer.BuffState final int state);
    void onTrackChanged(MediaItem item, int index);
    void onError(MediaItem item, int error);
    Integer convertStatus(int status);
  }

  private static final String logTag = "SMP2: TaskCoordinator";

  // Thread related
  @GuardedBy("lockForHandler")
  private HandlerThread handlerThread;
  @GuardedBy("lockForHandler")
  private Handler handler;
  private ExecutorService executor;

  // Task related
  @GuardedBy("lockForTaskQ")
  private ArrayDeque<MediaPlayerTask> taskQueue;
  private MediaPlayerTask currentTask;
  private PollBufferRunnable tokenForBufferPolling;
  private boolean isPolling;

  // ExoPlayer related
  private ExoPlayerWrapper exoplayer;
  private BufferListener bufferListener;

  // Locks
  private final Object lockForTaskQ;
  private final Object lockForHandler;
  private final Object lockForState;

  private static final int POLL_BUFFER_INTERVAL_MS = 1000;

  public TaskCoordinator(Context context, BufferListener listener, ExoWrapperFactory ExoFactory) {
    Log.d(logTag, "constructor");
    // Thread related
    handlerThread = new HandlerThread("SimpleAudioPlayer");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    executor = Executors.newFixedThreadPool(1);

    // Task related
    taskQueue = new ArrayDeque<>();
    tokenForBufferPolling = new PollBufferRunnable();

    // ExoPlayer related
    exoplayer = ExoFactory.getWrapper(context, handlerThread.getLooper(), this);
    bufferListener = listener;

    // Locks
    lockForTaskQ = new Object();
    lockForHandler = new Object();
    lockForState = new Object();

    reset();
  }

  // Task Related

  /**
   * Rests the player to it's uninitialised state. Removes tasks, cancels futures, resets the
   * exoplayer.
   */
  public void reset() {
    MediaPlayerTask curr;
    synchronized (lockForTaskQ) {
      taskQueue.forEach(action -> action.future.cancel(true));
      taskQueue.clear(); // remove any queue tasks
      curr = currentTask; // avoid current task changing on us from another thread
    }

    if (curr != null) {
      synchronized (currentTask) { // other threads can't interfere
        try {
          while (!curr.taskComplete) {
            curr.wait();
          }
        } catch (InterruptedException e) {
          // Ignore interruption
        }
      }
    }

    processNowAndWaitForResult((Callable<Void>) () -> {
      exoplayer.reset();
      return null;
    });

    synchronized (lockForState) {
      isPolling = false;
    }
  }

  /**
   * Adds a task to the back of the queue. Because the queue might be empty (i.e. on first start up),
   * ask to process tasks.
   * @param task MediaPlayerTask to be queued up.
   */
  private SettableFuture<SessionPlayer.PlayerResult> addTask(MediaPlayerTask task) {
    synchronized (lockForTaskQ) {
      taskQueue.add(task);
      processTask();
      return task.future;
    }
  }

  /**
   * Posts the next task to the handler, if appropriate. If a task is currently running, processTask
   * will be called at the end of it.
   */
  @GuardedBy("lockForTaskQ")
  private void processTask() {
    if (currentTask == null && !taskQueue.isEmpty()) {
      MediaPlayerTask task = taskQueue.removeFirst();
      currentTask = task;
      handler.post(task);
    }
  }

  /**
   * Executes a task on the handler as soon as possible and blocks while it waits for the result.
   * Throws an exception if the task isn't added to the looper successfully.
   * @param callable Callable operations to be run on the handler
   * @param <T> Generic return type of the Callable
   * @return <T> Result of the callable
   */
  private <T> T processNowAndWaitForResult(Callable<T> callable) {
    SettableFuture<T> future = SettableFuture.create();
    synchronized (lockForHandler) {
      Preconditions.checkNotNull(handlerThread);
      boolean success = handler.post(() -> {
        try {
          future.set(callable.call());
        } catch (Throwable e) {
          future.setException(e);
        }
      });
      Preconditions.checkState(success);
    }

    return getPlayerFuture(future);
  }

  private void clearCurrentAndProcess() {
    synchronized (lockForTaskQ) {
      currentTask = null;
      processTask();
    }
  }

  private static <T> T getPlayerFuture(SettableFuture<T> future) {
    try {
      T result;
      boolean wasInterrupted = false;
      while (true) {
        try {
          result = future.get();
          break;
        } catch (InterruptedException e) {
          wasInterrupted = true;
        }
      }
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
      return result;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Log.e(logTag, "Internal player error", new RuntimeException(cause));
      throw new IllegalStateException(cause);
    }
  }

  private void pollBufferingState() {
    exoplayer.updateBuffering();
    handler.postDelayed(tokenForBufferPolling, POLL_BUFFER_INTERVAL_MS);
  }


  // SessionPlayer Implementation

  public @NonNull MediaPlayerTask play() {
    Log.d(logTag, "play!");

    return new MediaPlayerTask(
      () -> exoplayer.play(),
      false
    );

  }


  public @NonNull MediaPlayerTask pause() {
    Log.d(logTag, "pause");

    return new MediaPlayerTask(
      () -> exoplayer.pause(),
      false
    );

  }


  public @NonNull MediaPlayerTask prepare() {
    Log.d(logTag, "prepare");
    return new MediaPlayerTask(
      () -> exoplayer.prepare(),
      true // onPrepared
    );
  }


  public @NonNull MediaPlayerTask seekTo(long position) {
    Log.d(logTag, "seekTo");
    return new MediaPlayerTask(
      () -> exoplayer.seekTo(position),
      true  // onSeekCompleted
    );
  }


  public @NonNull MediaPlayerTask setPlaybackSpeed(float playbackSpeed) {  throw new UnsupportedOperationException("Setting the PlaybackSpeed is not supported in this version"); }


  public @NonNull MediaPlayerTask setAudioAttributes(@NonNull AudioAttributesCompat attributes) {
    Log.d(logTag, "setAudioattributes");
    return new MediaPlayerTask(
      () -> exoplayer.setAudioAttributes(attributes),
      false
    );
  }


  public long getCurrentPosition() {
    long pos = processNowAndWaitForResult(() -> exoplayer.getCurrentPosition());
//    Log.d(logTag, "getCurrentPosition - " + pos);
    if (pos == ExoPlayerWrapper.UNKNOWN_TIME) return SessionPlayer.UNKNOWN_TIME;
    return pos;
  }


  public long getDuration() {
    return processNowAndWaitForResult(() -> exoplayer.getDuration());
  }


  public long getBufferedPosition() {
    return processNowAndWaitForResult(() -> exoplayer.getBufferedPosition());
  }


  public float getPlaybackSpeed() {
    return processNowAndWaitForResult(() -> exoplayer.getPlaybackParams().speed);
  }


  public @NonNull MediaPlayerTask setPlaylist(@NonNull List<MediaItem> list) {
    Log.d(logTag, "setPlaylist");
    return new MediaPlayerTask(
      () -> exoplayer.setPlaylist(list),
      false
    );
  }


  public @Nullable AudioAttributesCompat getAudioAttributes() {
//    Log.d(logTag, "getAudioAttributes");
    return processNowAndWaitForResult(() -> exoplayer.getAudioAttributes());
  }


  public @NonNull MediaPlayerTask setMediaItem(@NonNull MediaItem item) {
    Log.d(logTag, "setMediaItem");
    return new MediaPlayerTask(
      () -> exoplayer.setMediaItem(item),
      false
    );
  }


  public @NonNull MediaPlayerTask addPlaylistItem(int index, @NonNull MediaItem item) {
    Log.d(logTag, "addPlaylistItem");
    return new MediaPlayerTask(
      () -> exoplayer.addToPlaylist(index, item),
      false
    );
  }


  public @NonNull MediaPlayerTask removePlaylistItem(@IntRange(from = 0) int index) {
    Log.d(logTag, "removePlaylistItem");
    return new MediaPlayerTask(
      () -> exoplayer.removeFromPlaylist(index),
      false
    );
  }


  public @NonNull MediaPlayerTask replacePlaylistItem(int index, @NonNull MediaItem item) {
    Log.d(logTag, "replacePlaylistItem");
    return new MediaPlayerTask(
      () -> exoplayer.replacePlaylistItem(index, item),
      false
    );
  }

  public @NonNull MediaPlayerTask movePlaylistItem(int from, int to) {
    Log.d(logTag, "movePlaylistItem");
    return new MediaPlayerTask(
      () -> exoplayer.movePlaylistItem(from, to),
      false
);
  }


  public @NonNull MediaPlayerTask skipToPreviousPlaylistItem() {
    Log.d(logTag, "skipToPrev");
    return new MediaPlayerTask(
      () -> exoplayer.skipBackward(),
      false
    );
  }


  public @NonNull MediaPlayerTask skipToNextPlaylistItem() {
    Log.d(logTag, "skipToNext");
    return new MediaPlayerTask(
      () -> exoplayer.skipForward(),
      false
);
  }


  public @NonNull MediaPlayerTask skipToPlaylistItem(@IntRange(from = 0) int index) {
    Log.d(logTag, "skipToPlaylistItem");
    return new MediaPlayerTask(
      () -> exoplayer.skipToIndex(index),
      false
    );
  }


  public @NonNull MediaPlayerTask setRepeatMode(@SessionPlayer.RepeatMode int repeatMode) {
    Log.d(logTag, "setRepeatMode");
    int mode = (repeatMode == SessionPlayer.REPEAT_MODE_GROUP) ? 2 : repeatMode;

    return new MediaPlayerTask(
      () -> exoplayer.setRepeatMode(mode),
      false
    );
  }

  public @NonNull MediaPlayerTask setShuffleMode(boolean enable) {
    Log.d(logTag, "setShuffleMode");

    return new MediaPlayerTask(
      () -> exoplayer.setShuffleMode(enable),
      false
    );
  }

  /**
   * Returns the list of current items if a playlist has been set. If there is no playlist meta data
   * then the contents of the player are not an actual playlist.
   * @return List of mediaitems or null
   */
  public List<MediaItem> getPlaylist() {
//    Log.d(logTag, "getPlaylist - " + exoplayer.getPlaylist());
    return exoplayer.getPlaylist();
  }

  public @Nullable MediaItem getCurrentMediaItem() {
    return exoplayer.getCurrentMediaItem();
  }


  public @NonNull MediaPlayerTask setVolume(float volume) {
    return new MediaPlayerTask(
      () -> exoplayer.setVolume(volume),
      false
    );
  }

  public float getVolume() {
    return processNowAndWaitForResult(() -> exoplayer.getVolume());
  }


  @Override
  public void close() {
    reset();

    HandlerThread hthread;
    synchronized (lockForHandler) {
      hthread = handlerThread;
      if (hthread == null) return;
      handlerThread = null;

      handler.removeCallbacks(tokenForBufferPolling);

      SettableFuture<Boolean> future = SettableFuture.create();
      handler.post(() -> {
        exoplayer.close();
        future.set(true);
      });
      getPlayerFuture(future);
      hthread.quit();
      executor.shutdown();
    }
  }

  // ExoWrapper.WrapperListener Implementation

  @Override
  public void onPrepared(MediaItem mediaItem) {
    synchronized (lockForTaskQ) {
      Log.d(logTag, "onPrepared");
      if (currentTask != null
        && ObjectsCompat.equals(currentTask.mediaItem, mediaItem)
        && currentTask.needToWaitForEventToComplete) {
        currentTask.sendCompleteNotification(MediaPlayer2.CALL_STATUS_NO_ERROR);
      }
    }
  }


  @Override
  public void onTrackChanged(MediaItem item, int index) {
    executor.execute(() -> bufferListener.onTrackChanged(item, index));
  }


  @Override
  public void onStartBufferPolling() {
    if (!isPolling) {
      isPolling = true;
      handler.post(tokenForBufferPolling);
    }
  }


  @Override
  public  void onStopBufferPolling() {
    if (isPolling) {
      isPolling = false;
      handler.removeCallbacks(tokenForBufferPolling);
    }
  }


  @Override
  public void onBufferingStarted(MediaItem mediaItem) {
    bufferListener.setBufferingState(mediaItem, SessionPlayer.BUFFERING_STATE_BUFFERING_AND_STARVED);
  }


  @Override
  public void onBufferingUpdate(MediaItem mediaItem, int percent) {
    if (percent >= 100) bufferListener.setBufferingState(mediaItem, SessionPlayer.BUFFERING_STATE_COMPLETE);
  }


  @Override
  public void onBuffered(MediaItem mediaItem) {
    bufferListener.setBufferingState(mediaItem, SessionPlayer.BUFFERING_STATE_BUFFERING_AND_PLAYABLE);
//    changeState(state); // trigger notification update
  }


  @Override
  public void onSeekCompleted() {
    Log.d(logTag, "onSeekCompleted");
    synchronized (lockForTaskQ) {
      if (currentTask != null
        && currentTask.needToWaitForEventToComplete) {

        currentTask.sendCompleteNotification(MediaPlayer2.CALL_STATUS_NO_ERROR);
      }
    }
  }


  @Override
  public void onError(MediaItem mediaItem, int error) {
    synchronized (lockForTaskQ) {
      if (currentTask != null
        && currentTask.needToWaitForEventToComplete) {

        currentTask.sendCompleteNotification(MediaPlayer2.CALL_STATUS_ERROR_UNKNOWN);
      }
    }

    bufferListener.onError(mediaItem, error);
  }


  // Task Structure


  @FunctionalInterface
  public interface Operations {
    void apply() throws IOException, MediaPlayer2.NoDrmSchemeException;
  }

  @FunctionalInterface
  public interface Foreach {
    void apply(int state, MediaItem item);
  }

  @FunctionalInterface
  public interface FlatMap {
    MediaPlayerTask apply(int state, MediaItem item);
  }

  public class MediaPlayerTask implements Runnable {

    final SettableFuture<SessionPlayer.PlayerResult> future;
    final boolean needToWaitForEventToComplete;
    private MediaItem mediaItem;
    @GuardedBy("this")
    boolean taskComplete;

    private final Operations instructions;
    private final ArrayDeque<FlatMap> afterIntructions;
    @Nullable
    private Foreach finalInstructions;

    MediaPlayerTask(Operations instructions, boolean needToWaitForEventToComplete) {
      this.future = SettableFuture.create();
      this.instructions = instructions;
      this.afterIntructions = new ArrayDeque<>();
      this.finalInstructions = null;
      this.needToWaitForEventToComplete = needToWaitForEventToComplete;
    }

    @Override
    public void run() {
      int status = MediaPlayer2.CALL_STATUS_NO_ERROR;


      try {
        instructions.apply();
      } catch (IllegalStateException e) {
        status = MediaPlayer2.CALL_STATUS_INVALID_OPERATION;
      } catch (IllegalArgumentException e) {
        status = MediaPlayer2.CALL_STATUS_BAD_VALUE;
      } catch (SecurityException e) {
        status = MediaPlayer2.CALL_STATUS_PERMISSION_DENIED;
      } catch (IOException e) {
        status = MediaPlayer2.CALL_STATUS_ERROR_IO;
      } catch (Exception e) {
        status = MediaPlayer2.CALL_STATUS_ERROR_UNKNOWN;
      }

      mediaItem = exoplayer.getCurrentMediaItem();

      if (!needToWaitForEventToComplete) {
        sendCompleteNotification(status);
      }

      synchronized (this) {
        taskComplete = true;
        notifyAll();
      }
    }

    void sendCompleteNotification(int status) {
      Integer converted = bufferListener.convertStatus(status);
      executor.execute(() -> {
        if (!afterIntructions.isEmpty()) {
          MediaPlayerTask nextTask = afterIntructions.remove().apply(converted, mediaItem);
          nextTask.addQueue(afterIntructions);
          future.setFuture(nextTask.foreach(finalInstructions));
          return;
        } else if (finalInstructions != null) {
          finalInstructions.apply(converted, mediaItem);
        }

        future.set(new SessionPlayer.PlayerResult(converted, mediaItem));
      });
      clearCurrentAndProcess();
    }

    void addQueue(ArrayDeque<FlatMap> after) {
      afterIntructions.addAll(after);
    }

    public MediaPlayerTask flatMap(FlatMap fm) {
      afterIntructions.add(fm);
      return this;
    }

    public ListenableFuture<SessionPlayer.PlayerResult> foreach(Foreach fe) {
      finalInstructions = fe;
      addTask(this);
      return future;
    }

  }

}
