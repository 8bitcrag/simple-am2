package com.eightbit85.simple_am2.internal;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.core.util.ObjectsCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media2.common.MediaItem;
import androidx.media2.common.SessionPlayer;

import com.eightbit85.simple_am2.Monads.Eval;
import com.eightbit85.simple_am2.Monads.Later;
import com.eightbit85.simple_am2.Monads.MediaTask;
import com.eightbit85.simple_am2.Monads.Now;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SettableFuture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

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
    Integer convertStatus(@CallStatus int status);
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
  private final Object lockForOverride;

  // Other
  private int overrideStatus;

  // Error Codes
  public static final int MEDIA_ERROR_UNKNOWN = 1;

  // Status Codes - These codes directly mirror those in MediaPlayer2, which is currently inaccessible
  public static final int CALL_STATUS_NO_ERROR = 0;
  public static final int CALL_STATUS_ERROR_UNKNOWN = Integer.MIN_VALUE;
  public static final int CALL_STATUS_INVALID_OPERATION = 1;
  public static final int CALL_STATUS_BAD_VALUE = 2;
  public static final int CALL_STATUS_PERMISSION_DENIED = 3;
  public static final int CALL_STATUS_ERROR_IO = 4;
  public static final int CALL_STATUS_SKIPPED = 5;
  @IntDef(flag = false, /*prefix = "CALL_STATUS",*/ value = {
    CALL_STATUS_NO_ERROR,
    CALL_STATUS_ERROR_UNKNOWN,
    CALL_STATUS_INVALID_OPERATION,
    CALL_STATUS_BAD_VALUE,
    CALL_STATUS_PERMISSION_DENIED,
    CALL_STATUS_ERROR_IO,
    CALL_STATUS_SKIPPED})
  @Retention(RetentionPolicy.SOURCE)
  public @interface CallStatus {}

  private static final int POLL_BUFFER_INTERVAL_MS = 1000;

  public TaskCoordinator(Context context, BufferListener listener, ExoWrapperFactory ExoFactory) {
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
    lockForOverride = new Object();

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
   * Puts the MediaTask monad of instructions in a MediaPlayerTask for going on the looper.
   * @param mediaTask Monad representing the sequence of instructions for the exoplayer
   * @return Future representing the completion of the instructions
   */
  public SettableFuture<SessionPlayer.PlayerResult> submit(MediaTask<SessionPlayer.PlayerResult> mediaTask) {
    MediaPlayerTask mpt = new MediaPlayerTask(mediaTask);
    return addTask(mpt);
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

  // Convenience

  Function<Integer, SessionPlayer.PlayerResult> mapToResult = (i -> new SessionPlayer.PlayerResult(bufferListener.convertStatus(i), exoplayer.getCurrentMediaItem()));

  // SessionPlayer Implementation

  public @NonNull MediaTask<SessionPlayer.PlayerResult> play() {

    return new MediaTask<>(() -> {
      exoplayer.play();
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> pause() {

    return new MediaTask<>(() -> {
      exoplayer.pause();
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> prepare() {

    return new MediaTask<>(() -> {
      exoplayer.prepare();
      return new Later<>(() -> {
        int st;
        synchronized (lockForOverride) {
          st = overrideStatus;
        }
        return st;
      });
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> seekTo(long position) {

    return new MediaTask<>(() -> {
      exoplayer.seekTo(position);
      return new Later<>(() -> {
        int st;
        synchronized (lockForOverride) {
          st = overrideStatus;
        }
        return st;
      });
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setPlaybackSpeed(float playbackSpeed) {  throw new UnsupportedOperationException("Setting the PlaybackSpeed is not supported in this version"); }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setAudioAttributes(@NonNull AudioAttributesCompat attributes) {

    return new MediaTask<>(() -> {
      exoplayer.setAudioAttributes(attributes);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

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


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setPlaylist(@NonNull List<MediaItem> list) {

    return new MediaTask<>(() -> {
      exoplayer.setPlaylist(list);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @Nullable AudioAttributesCompat getAudioAttributes() {
//    Log.d(logTag, "getAudioAttributes");
    return processNowAndWaitForResult(() -> exoplayer.getAudioAttributes());
  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setMediaItem(@NonNull MediaItem item) {

    return new MediaTask<>(() -> {
      exoplayer.setMediaItem(item);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> addPlaylistItem(int index, @NonNull MediaItem item) {

    return new MediaTask<>(() -> {
      exoplayer.addToPlaylist(index, item);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> removePlaylistItem(@IntRange(from = 0) int index) {

    return new MediaTask<>(() -> {
      exoplayer.removeFromPlaylist(index);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> replacePlaylistItem(int index, @NonNull MediaItem item) {

    return new MediaTask<>(() -> {
      exoplayer.replacePlaylistItem(index, item);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }

  public @NonNull MediaTask<SessionPlayer.PlayerResult> movePlaylistItem(int from, int to) {

    return new MediaTask<>(() -> {
      exoplayer.movePlaylistItem(from, to);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> skipToPreviousPlaylistItem() {

    return new MediaTask<>(() -> {
      exoplayer.skipBackward();
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> skipToNextPlaylistItem() {

    return new MediaTask<>(() -> {
      exoplayer.skipForward();
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> skipToPlaylistItem(@IntRange(from = 0) int index) {

    return new MediaTask<>(() -> {
      exoplayer.skipToIndex(index);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setRepeatMode(@SessionPlayer.RepeatMode int repeatMode) {
    int mode = (repeatMode == SessionPlayer.REPEAT_MODE_GROUP) ? 2 : repeatMode;

    return new MediaTask<>(() -> {
      exoplayer.setRepeatMode(mode);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

  }

  public @NonNull MediaTask<SessionPlayer.PlayerResult> setShuffleMode(boolean enable) {

    return new MediaTask<>(() -> {
      exoplayer.setShuffleMode(enable);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

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


  public @NonNull MediaTask<SessionPlayer.PlayerResult> setVolume(float volume) {

    return new MediaTask<>(() -> {
      exoplayer.setVolume(volume);
      return new Now<>(CALL_STATUS_NO_ERROR);
    }).map(mapToResult);

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
      if (currentTask != null
        && ObjectsCompat.equals(currentTask.mediaItem, mediaItem)
        && currentTask.isWaiting()) {

        synchronized (lockForOverride) {
          overrideStatus = CALL_STATUS_NO_ERROR;
        }
        currentTask.sendCompleteNotification();

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
    synchronized (lockForTaskQ) {
      if (currentTask != null
        && currentTask.isWaiting()) {
        synchronized (lockForOverride) {
          overrideStatus = CALL_STATUS_NO_ERROR;
        }
        currentTask.sendCompleteNotification();
      }
    }
  }


  @Override
  public void onError(MediaItem mediaItem, int error) {
    synchronized (lockForTaskQ) {
      if (currentTask != null
        && currentTask.isWaiting()) {
        synchronized (lockForOverride) {
          overrideStatus = CALL_STATUS_ERROR_UNKNOWN;
        }
        currentTask.sendCompleteNotification();
      }
    }

    bufferListener.onError(mediaItem, error);
  }


  // Task Structure

//  @FunctionalInterface
//  public interface Op<A> {
//    A apply();// throws IOException;
//  }
//
//  @FunctionalInterface
//  public interface Map<B> {
//    B apply();// throws IOException;
//  }
//
//
//  @FunctionalInterface
//  public interface Operations {
//    void apply() throws IOException;
//  }
//
//  @FunctionalInterface
//  public interface Foreach {
//    void apply(int state, MediaItem item);
//  }
//
//  @FunctionalInterface
//  public interface FlatMap {
//    MediaPlayerTask apply(int state, MediaItem item);
//  }

//  public class MediaPlayerTask implements Runnable {
//
//    final SettableFuture<SessionPlayer.PlayerResult> future;
//    final boolean needToWaitForEventToComplete;
//    private MediaItem mediaItem;
//    @GuardedBy("this")
//    boolean taskComplete;
//
//    private final Operations instructions;
//    private final ArrayDeque<FlatMap> afterIntructions;
//    @Nullable
//    private Foreach finalInstructions;
//
//    MediaPlayerTask(Operations instructions, boolean needToWaitForEventToComplete) {
//      this.future = SettableFuture.create();
//      this.instructions = instructions;
//      this.afterIntructions = new ArrayDeque<>();
//      this.finalInstructions = null;
//      this.needToWaitForEventToComplete = needToWaitForEventToComplete;
//    }
//
//    @Override
//    public void run() {
//      int status = CALL_STATUS_NO_ERROR;
//
//
//      try {
//        instructions.apply();
//      } catch (IllegalStateException e) {
//        status = CALL_STATUS_INVALID_OPERATION;
//      } catch (IllegalArgumentException e) {
//        status = CALL_STATUS_BAD_VALUE;
//      } catch (SecurityException e) {
//        status = CALL_STATUS_PERMISSION_DENIED;
//      } catch (IOException e) {
//        status = CALL_STATUS_ERROR_IO;
//      } catch (Exception e) {
//        status = CALL_STATUS_ERROR_UNKNOWN;
//      }
//
//      mediaItem = exoplayer.getCurrentMediaItem();
//
//      if (!needToWaitForEventToComplete) {
//        sendCompleteNotification(status);
//      }
//
//      synchronized (this) {
//        taskComplete = true;
//        notifyAll();
//      }
//    }
//
//    void sendCompleteNotification(@CallStatus int status) {
//      Integer converted = bufferListener.convertStatus(status);
//      executor.execute(() -> {
//        if (!afterIntructions.isEmpty()) {
//          MediaPlayerTask nextTask = afterIntructions.remove().apply(converted, mediaItem);
//          nextTask.addQueue(afterIntructions);
//          future.setFuture(nextTask.foreach(finalInstructions));
//          return;
//        } else if (finalInstructions != null) {
//          finalInstructions.apply(converted, mediaItem);
//        }
//
//        future.set(new SessionPlayer.PlayerResult(converted, mediaItem));
//      });
//      clearCurrentAndProcess();
//    }
//
//    void addQueue(ArrayDeque<FlatMap> after) {
//      afterIntructions.addAll(after);
//    }
//
//    public MediaPlayerTask flatMap(FlatMap fm) {
//      afterIntructions.add(fm);
//      return this;
//    }
//
//    public ListenableFuture<SessionPlayer.PlayerResult> foreach(Foreach fe) {
//      finalInstructions = fe;
//      addTask(this);
//      return future;
//    }
//
//  }

  public class MediaPlayerTask implements Runnable {

    final SettableFuture<SessionPlayer.PlayerResult> future;
    private MediaItem mediaItem;
    @GuardedBy("this")
    boolean taskComplete;

    private final MediaTask<SessionPlayer.PlayerResult> instructions;
    private Eval<SessionPlayer.PlayerResult> procedure;

    MediaPlayerTask(MediaTask<SessionPlayer.PlayerResult> instructions) {
      this.future = SettableFuture.create();
      this.instructions = instructions;
      this.taskComplete = false;
    }

    public boolean isWaiting() {
      return this.procedure != null && !this.procedure.isNow();
    }

    @Override
    public void run() {
      procedure = this.instructions.run();
      mediaItem = exoplayer.getCurrentMediaItem();

      if (procedure.isNow()) {
        sendCompleteNotification();
      }

      synchronized (this) {
        taskComplete = true;
        notifyAll();
      }
    }

    void sendCompleteNotification() {
      if (!procedure.isNow()) {
        procedure = procedure.step(); // execute next instruction
        mediaItem = exoplayer.getCurrentMediaItem(); // instruction may change mediaItem
      }

      if (procedure.isNow()) {
        executor.execute(() -> {
          future.set(procedure.run());
        });
        clearCurrentAndProcess();
      }
    }

  }

}
