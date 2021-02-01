# simple-am2 (Simple Audio Media2)

1. [Overview](#overview)
2. [Installation](#installation)
3. [Creation](#creation)
4. [Usage](#usage)
5. [Extending](#extending)
6. [Contributing](#contributing)

## Overview
This is a reworking of the original [SessionPlayer implementation](https://developer.android.com/reference/androidx/media2/player/MediaPlayer) ([view source](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-master-dev:media2/player/src/main/java/androidx/media2/player/MediaPlayer.java)) by google. As such code from the original remains in this version so credit must go the authors and you can find the OWNERS file [here](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-master-dev:media2/OWNERS). 

Currently this implementaion is focused on audio only and the main goals of this version are:

**Exoplayer as a dependency** - You can use gradle (or your prefered build tool) to override the version of exo. This is useful if you depend on a certain version or if you are struggling with a bug/issue.

**Extendable** - Although still limited to what functionality the wrappers expose (for threading reasons), it should be possible to compose and use the functionality in any way you need.

For the most part usage will be the same as [MediaPlayer](https://developer.android.com/reference/androidx/media2/player/MediaPlayer), with the one critical exception being that you need to take care of [audio focus](#audiofocushandler) yourself.

## Installation
Download the latest `.aar` from releases and add it as a module to your app. Instructions for Android Studio can be found [here](https://developer.android.com/studio/projects/android-library#AddDependency).

## Creation
An instance of the player is created using the Builder class that accompanies it, a basic example might be:
```java
public void onCreate() {
  
  // ...
  
  myPlayer = new SimpleAudioPlayer.Builder(myContext)
                  .setAudioFocusHandler(plyr -> new MyAudioFocusHandler(this, plyr))
                  .setCallbacks(executor, myCallbacks)
                  .build();
}
```

### AudioFocusHandler
Technically audio focus handling is optional but it is really *really* **really** recommended that you handle it appropriately. When using Android's MediaPlayer audio focus is handled for you, but to give users more flexibility and to limit distance from the Media2 APIs, it's been omitted from this library.

For an excellent example and reference on writing a handler, take a look at [Android's own implementation](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-master-dev:media2/player/src/main/java/androidx/media2/player/AudioFocusHandler.java). For an explanation of the prinicples see [Managing Audio Focus](https://developer.android.com/guide/topics/media-apps/audio-focus) on Android developers.

The only requirement is that your handler implements the `AudioFocusHandler` interface which looks like the following:
```java
public interface AudioFocusHandler {
  boolean onPlay();
  void close();
}
```
This mimics the behavior of the original API to keep it consistent.

### Callbacks 
You can use [SessionPlayer.registerPlayerCallback](https://developer.android.com/reference/androidx/media2/common/SessionPlayer#registerPlayerCallback(java.util.concurrent.Executor,%20androidx.media2.common.SessionPlayer.PlayerCallback)) as normal, the builder simply provides `setCallbacks` as a convenience.

## Usage
Because it implements `SessionPlayer`, simple-am2 can be used anywhere you would normally have a `SessionPlayer`. Usually you would be using `MediaPlayer` which does have some differences:

*DRM handling* - Currently Simple-am2 doesn't handle drm sessions.

If something important is missing from this list, please create an issue!

## Extending
Extending functionality can be done using the `MediaTask` monad to generate custom programs and then submit them for processing using `taskCoordinator.submit(MediaTask<Integer, PlayerResult> mediaTask)`. This functionality is a little tricky to motivate so a contrived example will be used to demonstrate.

Let's say you want a button that causes the player to seek to a certain point and pause. You could use listeners on the futures to chain instructions, but what if another thread/app/service on the device submits a request while you're waiting? The seek instruction waits for an event in order to complete, giving another oppertunity for problems. You *could* send the instructions one after the other like this:
```java
  taskCoordinator.submit(taskCoordinator.seekTo(3000L));
  return taskCoordinator.submit(taskCoordinator.pause());
```
But it *still* doesn't guarentee the order, and what happens if the first one fails, or produces a result you need to work with? What if you only want to seek if the track is already past `3000`? This is where the `MediaTask` comes in.

The `flatMap` method allows you to work with the result of the previous instruction, and submit a new one that will be executed straight after without interruption.
```java
taskCoordinator.seekTo(3000L)
.flatMap(pr -> { // 'pr' is the PlayerResult of the seekTo 
  notifySessionPlayerCallback(callback -> callback.onSeekCompleted(this, 6000)); // notify the system the seek finished
  if (taskCoordinator.getCurrentPosition() > 3000) return taskCoordinator.pause().foreach(x -> changeState(SessionPlayer.PLAYER_STATE_PAUSED));
  else return MediaTask.pure(pr);
});
```
Here the function passed to `flatMap` produces another `MediaTask`, which either pauses the playback or just returns result of `seekTo`. The `foreach` function provides a way to specify any side-effects, in this case updating the state to paused. The `pure` function just elevates a value in to a plain `MediaTask`. 

It's also worth noting that if the `seekTo` instruction fails, the sequence short circuits. The `flatMap` instructions will not be executed and the `PlayerResult` containing the error information for `seekTo` will be returned.

Now let's get more complex. Let's say you sometimes want to play a trick on your users and add an extra step to the sequence that picks a random volume.

```java
private MediaTask<Integer, PlayerResult> sap = taskCoordinator.seekTo(3000L)
  .flatMap(pr -> {
    notifySessionPlayerCallback(callback -> callback.onSeekCompleted(this, 6000));
    if (taskCoordinator.getCurrentPosition() > 3000) return taskCoordinator.pause().foreach(x -> changeState(SessionPlayer.PLAYER_STATE_PAUSED));
    else return MediaTask.pure(pr);
  });

public ListenableFuture<PlayerResult> seekAndPause() {
  return taskCoordinator.submit(sap);
}

public void trick() {
  sap = sap.flatMap(pr -> {
    float v = new Random().nextFloat();
    return taskCoordinator.setVolume(v);
  });
}
```
Here the seek and pause sequence is being stored as a variable, and the `trick()` method updates the sequence with an extra step to randomise the volume. In this way `MediaTask` can be thought of as a way to build mini programs that you can run on the player at will.

## Contributing
Pull requests and issues are welcome.
