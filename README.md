# simple-am2

1. [Overview](#overview)
2. [Installation](#installation)

## Overview
This is a reworking of the original [SessionPlayer implementation](https://developer.android.com/reference/androidx/media2/player/MediaPlayer) ([view source](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-master-dev:media2/player/src/main/java/androidx/media2/player/MediaPlayer.java)) by google. As such code from the original remains in this version so credit must go the authors and you can find the OWNERS file [here](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-master-dev:media2/OWNERS). 

Currently this implementaion is focused on audio only and the main goals of this version are:

**Exoplayer as a dependency** - You can use gradle (or your prefered build tool) to override the version on exo. This is useful if you depend on a certain version or if you are stuggling with a bug/issue.

**Entendable** - Although still limited to what functionality the wrappers expose (for threading reasons), it should be possible to compose and use the functionality in any way you need.

## Installation
Download the .aar from releases and add it as a module to your app.

## Usage
