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

**Exoplayer as a dependency** - You can use gradle (or your prefered build tool) to override the version of exo. This is useful if you depend on a certain version or if you are stuggling with a bug/issue.

**Extendable** - Although still limited to what functionality the wrappers expose (for threading reasons), it should be possible to compose and use the functionality in any way you need.

For the most part usage will be the same as [MediaPlayer](https://developer.android.com/reference/androidx/media2/player/MediaPlayer), with the one critical exception being that you need to take care of [audio focus](#audiofocushandler) yourself.

## Installation
Download the latest `.aar` from releases and add it as a module to your app. Instructions for Android Studio can be found [here](https://developer.android.com/studio/projects/android-library#AddDependency).

## Creation
An instance of the player is created usin the Builder class that accompanies it, a basic example might be:
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
Technically audio focus handling is optional but it is really *really* **really** recommended that you handle it appropriately. When using Android's MediaPlayer audio focus is handled for you, but to give users more flexibility and to limit ditance from the Media2 APIs, it's been ommited from this library.

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
You can use [SessionPlayer.registerPlayerCallback](https://developer.android.com/reference/androidx/media2/common/SessionPlayer#registerPlayerCallback(java.util.concurrent.Executor,%20androidx.media2.common.SessionPlayer.PlayerCallback) as normal, the builder simply provides `setCallbacks` as a convenience.

## Usage
Because it implements `SessionPlayer`, simple-am2 can be used anywhere you would normally have one. Usually you would be using `MediaPlayer` which does have some differences:

*DRM handling* - Currently Simple-am2 doesn't handle drm sessions.

*Moving playlist items* - At the time of writing MediaPlayer doesn't support this (the current alpha *does* so it will soon), simple-am2 **does** support this.

*Event trigger quirks* - You will notice that the `onPlayerStateChanged` callback is sometimes fired when the state hasn't change. *This is intentional* as a work around to some issues in Media2 with notification updating. This will be removed when the issues are corrected in later Media2 versions.

If something important is missing from this list, please create an issue!

## Extending
Exoplayer operations need to be handled and organised on their own thread and this is managed by the TaskCoordinator. Each operation is considered a task and SimpleAudioPlayer simply gives these tasks to the coordinator and returns a future of the result (with a few blocking exceptions).

When ever you ask the coordinator to queue up a task, you also supply a function specifying what you want to happen when the task is complete. These 'post' operations are performed on their own thread, so they won't block main, and won't interfere with other exoplayer tasks. The most common use is triggering appropriate callbacks, for example `seekTo` triggers `onSeekCompleted` when it's done:
```java
taskCoordinator.seekTo(
  position,
  (int status, MediaItem item) -> notifySessionPlayerCallback(callback -> callback.onSeekCompleted(this, position))
);
```
A more complex and contrived example might be auto adjusting volume on play:


## Contributing
Pull requests and issues are welcome.
