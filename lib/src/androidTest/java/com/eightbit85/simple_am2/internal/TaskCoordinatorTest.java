package com.eightbit85.simple_am2.internal;

import android.content.Context;
import android.os.Looper;

import androidx.media2.common.MediaItem;
import androidx.media2.common.SessionPlayer;
import androidx.media2.player.MediaPlayer2;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class TaskCoordinatorTest {

  @Mock
  Context mockContext;

  @Mock
  ExoPlayerWrapper mockExoWrapper = mock(ExoPlayerWrapper.class);

  @Mock
  MediaItem mockMediaItem = mock(MediaItem.class);

  private TaskCoordinator coord;
  private TaskCoordinator.BufferListener listener;

  public TaskCoordinatorTest() {
    listener = new TaskCoordinator.BufferListener() {
      @Override
      public void setBufferingState(MediaItem item, int state) {

      }

      @Override
      public void onTrackChanged(MediaItem item, int index) {

      }

      @Override
      public void onError(MediaItem item, int error) {

      }

      @Override
      public Integer convertStatus(int status) {
        if (status == MediaPlayer2.CALL_STATUS_NO_ERROR) return SessionPlayer.PlayerResult.RESULT_SUCCESS;
        return SessionPlayer.PlayerResult.RESULT_ERROR_UNKNOWN;
      }
    };

    ExoWrapperFactory fact = new ExoWrapperFactory() {
      @Override
      ExoPlayerWrapper getWrapper(Context context, Looper looper, ExoPlayerWrapper.WrapperListener listener) {
        return mockExoWrapper;
      }
    };

    coord = new TaskCoordinator(mockContext, listener, fact);

  }

  @Test
  public void test_callback_success() throws InterruptedException, ExecutionException, TimeoutException {

    doAnswer((Answer<Void>) invocation -> {
      new Thread() {
        public void run() { // simulate the wait for the callback by running on a separate thread
          try {
            Thread.sleep(10L);
            coord.onPrepared(mockMediaItem);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }.start();

      return null;
    }).when(mockExoWrapper).prepare();

    when(mockExoWrapper.getCurrentMediaItem()).thenReturn(mockMediaItem);

    ListenableFuture<SessionPlayer.PlayerResult> prepare = coord.prepare()
      .foreach((int status, MediaItem item) -> { });

    SessionPlayer.PlayerResult res = prepare.get(2, TimeUnit.SECONDS);
    assertEquals(SessionPlayer.PlayerResult.RESULT_SUCCESS, res.getResultCode());
  }

  @Test
  public void test_callback_fail() throws InterruptedException, ExecutionException, TimeoutException {
    doAnswer((Answer<Void>) invocation -> {
      new Thread() {
        public void run() { // simulate the wait for the callback by running on a separate thread
          try {
            Thread.sleep(10L);
            coord.onError(mockMediaItem, MediaPlayer2.MEDIA_ERROR_UNKNOWN);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }.start();

      return null;
    }).when(mockExoWrapper).prepare();

    when(mockExoWrapper.getCurrentMediaItem()).thenReturn(mockMediaItem);

    ListenableFuture<SessionPlayer.PlayerResult> prepare = coord.prepare()
      .foreach((int status, MediaItem item) -> { });

    SessionPlayer.PlayerResult res = prepare.get(2, TimeUnit.SECONDS);
    assertEquals(SessionPlayer.PlayerResult.RESULT_ERROR_UNKNOWN, res.getResultCode());
  }

  @Test
  public void test_action_fails() throws InterruptedException, ExecutionException, TimeoutException {
    doThrow(new IllegalStateException("Test Failure")).when(mockExoWrapper).skipForward();
    ListenableFuture<SessionPlayer.PlayerResult> task = coord.skipToNextPlaylistItem()
      .foreach((int status, MediaItem item) -> {});

    SessionPlayer.PlayerResult result = task.get(2L, TimeUnit.SECONDS);
    assertEquals(SessionPlayer.PlayerResult.RESULT_ERROR_UNKNOWN, result.getResultCode());
  }

  @Test
  public void test_blocking_call_success() {
    when(mockExoWrapper.getCurrentPosition()).thenReturn(10L);
    long pos = coord.getCurrentPosition();
    assertEquals(10L, pos);
  }

  @Test
  public void test_worst_case() throws InterruptedException, ExecutionException, TimeoutException {
    /*
     * a task is started that has a blocking after effect. Before the blocking call is made,
     * another task is started and after that another blocking request is made.
     *
     * Lastly, after a short delay a third task is added.
     *
     * looper: task1 -> task2 -> blocking1 -> blocking2 -> task3
     * executor: task1 -> task2 -> task3
     */

    ArrayBlockingQueue<String> looperResults = new ArrayBlockingQueue<>(10);
    ArrayBlockingQueue<String> executorResults = new ArrayBlockingQueue<>(10);

    doNothing().when(mockExoWrapper).reset();

    doAnswer((Answer<Void>) invocation -> {
      looperResults.put("task1 processing");
      return null;
    }).when(mockExoWrapper).setMediaItem(mockMediaItem);

    doAnswer((Answer<Void>) invocation -> {
      looperResults.put("task2 processing");
      Thread.sleep(10L); // enough time for blockingtask1 & 2 to be put on looper ahead of task3
      return null;
    }).when(mockExoWrapper).play();

    doAnswer((Answer<Void>) invocation -> {
      looperResults.put("task3 processing");
      return null;
    }).when(mockExoWrapper).pause();

    when(mockExoWrapper.getVolume()).then((Answer<Float>) invocation -> {
      looperResults.put("blockingtask1 processing");
      return 0.5f;
    });

    when(mockExoWrapper.getBufferedPosition()).then((Answer<Long>) invocation -> {
      looperResults.put("blockingtask2 processing");
      Thread.sleep(5L); // long running task
      return 1L;
    });

    when(mockExoWrapper.getCurrentMediaItem()).thenReturn(mockMediaItem);

    ListenableFuture<SessionPlayer.PlayerResult> task1 = coord.setMediaItem(mockMediaItem)
      .foreach((int status, MediaItem item) -> {
        try {
          Thread.sleep(5L); // give enough time for task2 to go on looper
          coord.getBufferedPosition(); // takes time to complete
          executorResults.put("task1 complete");
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      });

    coord.play()
      .foreach((int status, MediaItem item) -> {
        try {
          executorResults.put("task2 complete");
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      });

    coord.pause()
      .foreach((int status, MediaItem item) -> {
        try {
          executorResults.put("task3 complete");
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      });

    Thread.sleep(5L); // enough time for task2 to hit looper

    coord.getVolume();

    task1.get(10L, TimeUnit.SECONDS);

    assertEquals("task1 wasn't first to process", "task1 processing", looperResults.take());
    assertEquals("task2 wasn't second to process", "task2 processing", looperResults.take());
    assertEquals("blockingtask1 wasn't third to process", "blockingtask1 processing", looperResults.take());
    assertEquals("blockingtask2 wasn't fourth to process", "blockingtask2 processing", looperResults.take());
    assertEquals("task3 wasn't fifth to process", "task3 processing", looperResults.take());

    assertEquals("task1 wasn't first to complete", "task1 complete", executorResults.take());
    assertEquals("task2 wasn't second to complete", "task2 complete", executorResults.take());
    assertEquals("task3 wasn't third to complete", "task3 complete", executorResults.take());

  }

}