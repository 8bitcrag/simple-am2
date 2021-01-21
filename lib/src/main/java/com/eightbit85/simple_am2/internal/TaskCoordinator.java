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
import androidx.media2.common.SessionPlayer.PlayerResult;

import com.eightbit85.simple_am2.Monads.Bad;
import com.eightbit85.simple_am2.Monads.Either;
import com.eightbit85.simple_am2.Monads.Eval;
import com.eightbit85.simple_am2.Monads.Good;
import com.eightbit85.simple_am2.Monads.Later;
import com.eightbit85.simple_am2.Monads.Now;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SettableFuture;

import java.io.IOException;
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
  @GuardedBy("lockForTaskHandler")
  private HandlerThread taskHandlerThread;
  @GuardedBy("lockForTaskHandler")
  private final Handler taskHandler;
  @GuardedBy("lockForExoHandler")
  private HandlerThread exoHandlerThread;
  @GuardedBy("lockForExoHandler")
  private final Handler exoHandler;

  // Task related
  @GuardedBy("lockForTaskQ")
  private final ArrayDeque<MediaPlayerTask> taskQueue;
  private MediaPlayerTask currentTask;
  private final PollBufferRunnable tokenForBufferPolling;
  private boolean isPolling;

  // ExoPlayer related
  private ExoPlayerWrapper exoplayer;
  private BufferListener bufferListener;

  // Locks
  private final Object lockForTaskQ;
  private final Object lockForTaskHandler;
  private final Object lockForExoHandler;
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
    taskHandlerThread = new HandlerThread("SimpleAudioPlayer");
    taskHandlerThread.start();
    taskHandler = new Handler(taskHandlerThread.getLooper());

    exoHandlerThread = new HandlerThread("SimpleAudioPlayerExo");
    exoHandlerThread.start();
    exoHandler = new Handler(exoHandlerThread.getLooper());

    // Task related
    taskQueue = new ArrayDeque<>();
    tokenForBufferPolling = new PollBufferRunnable();

    // ExoPlayer related
    exoplayer = ExoFactory.getWrapper(context, exoHandlerThread.getLooper(), this);
    bufferListener = listener;

    // Locks
    lockForTaskQ = new Object();
    lockForTaskHandler = new Object();
    lockForExoHandler = new Object();
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
  public SettableFuture<SessionPlayer.PlayerResult> submit(MediaTask<Integer, PlayerResult> mediaTask) {
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
      taskHandler.post(task);
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
    synchronized (lockForExoHandler) {
      Preconditions.checkNotNull(exoHandlerThread);
      boolean success = exoHandler.post(() -> {
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
    exoHandler.postDelayed(tokenForBufferPolling, POLL_BUFFER_INTERVAL_MS);
  }

  // Convenience

    @FunctionalInterface
    public interface Op<A> {
      A get() throws IOException;
    }


  private final Function<Integer, SessionPlayer.PlayerResult> mapToResult = (i -> new SessionPlayer.PlayerResult(bufferListener.convertStatus(i), exoplayer.getCurrentMediaItem()));

  private Either<Integer, Integer> processInstruction(Op<Integer> op) {
    Either<Integer, Integer> status;

    try {
      status = new Good<>(op.get());
    } catch (IllegalStateException e) {
      status = new Bad<>(CALL_STATUS_INVALID_OPERATION);
    } catch (IllegalArgumentException e) {
      status = new Bad<>(CALL_STATUS_BAD_VALUE);
    } catch (SecurityException e) {
      status = new Bad<>(CALL_STATUS_PERMISSION_DENIED);
    } catch (IOException e) {
      status = new Bad<>(CALL_STATUS_ERROR_IO);
    } catch (Exception e) {
      status = new Bad<>(CALL_STATUS_ERROR_UNKNOWN);
    }

    return status;
  }

  private MediaTask<Integer, PlayerResult> mediaTaskWithErrorHandling(Op<Integer> op) {
    return mediaTaskWithErrorHandling(op, true);
  }

  private MediaTask<Integer, PlayerResult> mediaTaskWithErrorHandling(Op<Integer> op, boolean isImmediate) {
    return new MediaTask<>(() -> {
      exoHandler.post(() -> {
        Either<Integer, Integer> status = processInstruction(op);
        if (isImmediate || !status.isGood()) {
          synchronized (lockForOverride) {
            overrideStatus = status.isGood() ? status.getValue() : status.getErrorValue();
          }
          currentTask.sendCompleteNotification();
        }
      });

      return new Later<>(() -> {
        Either<Integer, Integer> st;
        synchronized (lockForOverride) {
          if (overrideStatus == CALL_STATUS_NO_ERROR) {
            st = new Good<>(overrideStatus);
          } else {
            st = new Bad<>(overrideStatus); // short circuit the rest of the sequence
          }
        }
        return st;
      });

    }).map(mapToResult);
  }

  // SessionPlayer Implementation

  public @NonNull MediaTask<Integer, PlayerResult> play() {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.play();
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> pause() {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.pause();
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> prepare() {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.prepare();
      return CALL_STATUS_NO_ERROR;
    }, false);

  }


  public @NonNull MediaTask<Integer, PlayerResult> seekTo(long position) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.seekTo(position);
      return CALL_STATUS_NO_ERROR;
    }, false);

  }


  public @NonNull MediaTask<Integer, PlayerResult> setPlaybackSpeed(float playbackSpeed) {  throw new UnsupportedOperationException("Setting the PlaybackSpeed is not supported in this version"); }


  public @NonNull MediaTask<Integer, PlayerResult> setAudioAttributes(@NonNull AudioAttributesCompat attributes) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setAudioAttributes(attributes);
      return CALL_STATUS_NO_ERROR;
    });

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


  public @NonNull MediaTask<Integer, PlayerResult> setPlaylist(@NonNull List<MediaItem> list) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setPlaylist(list);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @Nullable AudioAttributesCompat getAudioAttributes() {
//    Log.d(logTag, "getAudioAttributes");
    return processNowAndWaitForResult(() -> exoplayer.getAudioAttributes());
  }


  public @NonNull MediaTask<Integer, PlayerResult> setMediaItem(@NonNull MediaItem item) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setMediaItem(item);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> addPlaylistItem(int index, @NonNull MediaItem item) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.addToPlaylist(index, item);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> removePlaylistItem(@IntRange(from = 0) int index) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.removeFromPlaylist(index);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> replacePlaylistItem(int index, @NonNull MediaItem item) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.replacePlaylistItem(index, item);
      return CALL_STATUS_NO_ERROR;
    });

  }

  public @NonNull MediaTask<Integer, PlayerResult> movePlaylistItem(int from, int to) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.movePlaylistItem(from, to);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> skipToPreviousPlaylistItem() {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.skipBackward();
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> skipToNextPlaylistItem() {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.skipForward();
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> skipToPlaylistItem(@IntRange(from = 0) int index) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.skipToIndex(index);
      return CALL_STATUS_NO_ERROR;
    });

  }


  public @NonNull MediaTask<Integer, PlayerResult> setRepeatMode(@SessionPlayer.RepeatMode int repeatMode) {
    int mode = (repeatMode == SessionPlayer.REPEAT_MODE_GROUP) ? 2 : repeatMode;

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setRepeatMode(mode);
      return CALL_STATUS_NO_ERROR;
    });

  }

  public @NonNull MediaTask<Integer, PlayerResult> setShuffleMode(boolean enable) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setShuffleMode(enable);
      return CALL_STATUS_NO_ERROR;
    });

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


  public @NonNull MediaTask<Integer, PlayerResult> setVolume(float volume) {

    return mediaTaskWithErrorHandling(() -> {
      exoplayer.setVolume(volume);
      return CALL_STATUS_NO_ERROR;
    });

  }

  public float getVolume() {
    return processNowAndWaitForResult(() -> {
      Log.d("testing", "GETTING VOLUME");
      return exoplayer.getVolume();
    });
  }


  @Override
  public void close() {
    reset();

    HandlerThread hthread;
    synchronized (lockForExoHandler) {
      hthread = exoHandlerThread;
      if (hthread != null) {
        exoHandlerThread = null;

        exoHandler.removeCallbacks(tokenForBufferPolling);

        SettableFuture<Boolean> future = SettableFuture.create();
        exoHandler.post(() -> {
          exoplayer.close();
          future.set(true);
        });
        getPlayerFuture(future);

        hthread.quit();
      }
    }

    synchronized (lockForTaskHandler) {
      hthread = taskHandlerThread;
      if (hthread == null) return;
      taskHandlerThread = null;
      hthread.quit();
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
    //TODO: postAtFrontOfQueue is used to ensure track changes are observed quickly,
    // but it should be checked that this doesn't have unintended side-effects
    taskHandler.postAtFrontOfQueue(() -> bufferListener.onTrackChanged(item, index));
  }


  @Override
  public void onStartBufferPolling() {
    if (!isPolling) {
      isPolling = true;
      exoHandler.post(tokenForBufferPolling);
    }
  }


  @Override
  public  void onStopBufferPolling() {
    if (isPolling) {
      isPolling = false;
      exoHandler.removeCallbacks(tokenForBufferPolling);
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

  public class MediaPlayerTask implements Runnable {

    final SettableFuture<SessionPlayer.PlayerResult> future;
    private MediaItem mediaItem;
    @GuardedBy("this")
    boolean taskComplete;

    private final MediaTask<Integer, PlayerResult> instructions;
    private Eval<Integer, SessionPlayer.PlayerResult> procedure;

    MediaPlayerTask(MediaTask<Integer, PlayerResult> instructions) {
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
        finish(procedure.run());
      }

      synchronized (this) {
        taskComplete = true;
        notifyAll();
      }
    }

    void sendCompleteNotification() {
      taskHandler.post(() -> { // sendCompleteNotification is called from exo thread, move back on to task thread
        procedure = procedure.step(); // execute next instruction
        mediaItem = exoplayer.getCurrentMediaItem(); // instruction may change mediaItem
        if (procedure.isNow()) { // Have reached the end
          finish(procedure.run());
        }
      });
    }

    private void finish(Either<Integer, PlayerResult> result) {
      if (result.isGood()) {
        future.set(result.getValue());
      } else {
        int st = bufferListener.convertStatus(result.getErrorValue());
        PlayerResult er = new PlayerResult(st, mediaItem);
        future.set(er);
      }
      clearCurrentAndProcess();
    }

  }

}
