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

*Moving playlist items* - At the time of writing MediaPlayer doesn't support this (the current alpha *does* so it will soon), simple-am2 **does** support this.

*Event trigger quirks* - You will notice that the `onPlayerStateChanged` callback is sometimes fired when the state hasn't change. *This is intentional* as a work around to some issues in Media2 with notification updating. This will be removed when the issues are corrected in later Media2 versions.

If something important is missing from this list, please create an issue!

## Extending
Interacting with the exoplayer is acheived by giving the TaskCoordinator whichever tasks you need it to perform, and supplying any instructions that need to be carried out upon completion. Take a look at the following example:
```java
taskCoordinator.seekTo(position)
  .foreach((int status, MediaItem item) -> notifySessionPlayerCallback(callback -> callback.onSeekCompleted(this, position)));
```

Calling `seekTo(position)` gives the coordinator a task, and `foreach` specifies what you need to happen once it's done. In this case the corresponding callback is triggered.

Each task is processed in a queue, and you can chain tasks together using `flatMap`.

### Foreach and FlatMap

The `foreach` function signals the final operation to be carried out at the end of the chain, and returns a future of the final result. *Every* non blocking task needs a foreach, because there are no actions that don't need some kind of consequence.

The `flatMap` function will return another task that gets put on the queue after the first one has completed; a contrived example that sets the volume to full after playing might be:
```java
public ListenableFuture<PlayerResult> playAndFullVolume() {
  return taskCoordinator.play()
    .flatMap((int status, MediaItem item) -> taskCoordinator.setVolume(1f))
    .foreach(
      (int status, MediaItem item) -> notifySessionPlayerCallback(callback -> callback.onPlayerStateChanged(this, PLAYER_STATE_PLAYING))
    );
}
```

The `flatMap` puts a volume task on the queue, and after *that* task is complete, the foreach instructions trigger the callback.

Be aware that the result of the last task is the one returned to the original future. Each foreach/flatmap receives the status of the previous task as the first parameter.

## Contributing
Pull requests and issues are welcome.
