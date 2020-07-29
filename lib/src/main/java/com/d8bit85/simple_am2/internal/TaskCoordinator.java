package com.d8bit85.simple_am2.internal;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

  public TaskCoordinator(Context context, BufferListener listener) {
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
    exoplayer = new ExoPlayerWrapper(context, handlerThread.getLooper(), this);
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

  public @NonNull
  ListenableFuture<SessionPlayer.PlayerResult> play(PostOp post) {
    Log.d(logTag, "play!");

    return addTask(new MediaPlayerTask(
      () -> exoplayer.play(),
      post,
      false
    ));

  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> pause(PostOp post) {
    Log.d(logTag, "pause");

    return addTask(new MediaPlayerTask(
      () -> exoplayer.pause(),
      post,
      false
    ));

  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> prepare(PostOp post) {
    Log.d(logTag, "prepare");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.prepare(),
      post,
      true // onPrepared
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> seekTo(long position, PostOp post) {
    Log.d(logTag, "seekTo");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.seekTo(position),
      post,
      true  // onSeekCompleted
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setPlaybackSpeed(float playbackSpeed) {  throw new UnsupportedOperationException("Setting the PlaybackSpeed is not supported in this version"); }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setAudioAttributes(@NonNull AudioAttributesCompat attributes, PostOp post) {
    Log.d(logTag, "setAudioattributes");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.setAudioAttributes(attributes),
      post,
      false
    ));
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


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setPlaylist(@NonNull List<MediaItem> list, PostOp post) {
    Log.d(logTag, "setPlaylist");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.setPlaylist(list),
      post,
      false)
    );
  }


  public @Nullable AudioAttributesCompat getAudioAttributes() {
//    Log.d(logTag, "getAudioAttributes");
    return processNowAndWaitForResult(() -> exoplayer.getAudioAttributes());
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setMediaItem(@NonNull MediaItem item, PostOp post) {
    Log.d(logTag, "setMediaItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.setMediaItem(item),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> addPlaylistItem(int index, @NonNull MediaItem item, PostOp post) {
    Log.d(logTag, "addPlaylistItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.addToPlaylist(index, item),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> removePlaylistItem(@IntRange(from = 0) int index, PostOp post) {
    Log.d(logTag, "removePlaylistItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.removeFromPlaylist(index),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> replacePlaylistItem(int index, @NonNull MediaItem item, PostOp post) {
    Log.d(logTag, "replacePlaylistItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.replacePlaylistItem(index, item),
      post,
      false
    ));
  }

  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> movePlaylistItem(int from, int to, PostOp post) {
    Log.d(logTag, "movePlaylistItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.movePlaylistItem(from, to),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> skipToPreviousPlaylistItem(PostOp post) {
    Log.d(logTag, "skipToPrev");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.skipBackward(),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> skipToNextPlaylistItem(PostOp post) {
    Log.d(logTag, "skipToNext");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.skipForward(),
      post,
      false
    ));
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> skipToPlaylistItem(@IntRange(from = 0) int index, PostOp post) {
    Log.d(logTag, "skipToPlaylistItem");
    return addTask(new MediaPlayerTask(
      () -> exoplayer.skipToIndex(index),
      post,
      false)
    );
  }


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setRepeatMode(@SessionPlayer.RepeatMode int repeatMode, PostOp post) {
    Log.d(logTag, "setRepeatMode");
    int mode = (repeatMode == SessionPlayer.REPEAT_MODE_GROUP) ? 2 : repeatMode;

    return addTask(new MediaPlayerTask(
      () -> exoplayer.setRepeatMode(mode),
      post,
      false
    ));
  }

  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setShuffleMode(boolean enable, PostOp post) {
    Log.d(logTag, "setShuffleMode");

    return addTask(new MediaPlayerTask(
      () -> exoplayer.setShuffleMode(enable),
      post,
      false
    ));
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


  public @NonNull ListenableFuture<SessionPlayer.PlayerResult> setVolume(float volume, PostOp post) {
    return addTask(new MediaPlayerTask(
      () -> exoplayer.setVolume(volume),
      post,
      false
    ));
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
    Log.d(logTag, "onPrepared");
    synchronized (lockForTaskQ) {
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
  public interface PostOp {
    void apply(int state, MediaItem item);
  }


  private class MediaPlayerTask implements Runnable {

    final SettableFuture<SessionPlayer.PlayerResult> future;
    final boolean needToWaitForEventToComplete;
    private MediaItem mediaItem;
    @GuardedBy("this")
    boolean taskComplete;

    private final Operations instructions;
    private final PostOp afterIntructions;

    MediaPlayerTask(Operations instructions, PostOp afterInstructions, boolean needToWaitForEventToComplete) {
      this.future = SettableFuture.create();
      this.instructions = instructions;
      this.afterIntructions = afterInstructions;
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

    void sendCompleteNotification(final int status) {
      executor.execute(() -> {
        afterIntructions.apply(status, mediaItem);
        future.set(new SessionPlayer.PlayerResult(status, mediaItem));
      });
      clearCurrentAndProcess();
    }

  }

}
