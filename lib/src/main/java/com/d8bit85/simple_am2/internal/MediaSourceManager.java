package com.d8bit85.simple_am2.internal;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.RestrictTo;
import androidx.media2.common.MediaItem;
import androidx.media2.common.UriMediaItem;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;

@RestrictTo(LIBRARY_GROUP_PREFIX)
public class MediaSourceManager {

  private final String logTag = "SMP2: MediaSourceManager";
  private ConcatenatingMediaSource concatMediaSource;
  private DataSource.Factory dataSourceFactory;
  private Context context;
  private ArrayList<MediaItemWithInfo> mediaItems;
  private int currentIndex;

  private final ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
    .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING);

  public MediaSourceManager(Context context, String userAgentName) {
    Log.d(logTag, "constructor");
    this.context = context;
    String userAgent = Util.getUserAgent(context, userAgentName);
    dataSourceFactory = new DefaultDataSourceFactory(context, userAgent);
    concatMediaSource = new ConcatenatingMediaSource();
    mediaItems = new ArrayList<>();
    currentIndex = -1;
  }

  public ConcatenatingMediaSource getConcatMediaSource() {
    return concatMediaSource;
  }

  public void clear() {
    concatMediaSource.clear();
    mediaItems.clear();
    currentIndex = -1;
  }

  public int getPlaylistSize() {
    return mediaItems.size();
  }

  public void setMediaItem(MediaItem mediaItem) {
    setMediaItems(Collections.singletonList(mediaItem));
  }

  public void setMediaItems(List<MediaItem> items) {
    clear();
    List<MediaSource> sources = items.stream().map(this::createMediaSource).collect(Collectors.toList());
    List<MediaItemWithInfo> infos = items.stream().map(this::createInfo).collect(Collectors.toList());
    concatMediaSource.addMediaSources(sources);
    mediaItems.addAll(infos);
    currentIndex = 0;
  }

  public MediaItem getCurrentMediaItem() {
    return (mediaItems.isEmpty() || currentIndex < 0) ? null : mediaItems.get(currentIndex).mediaItem;
  }

  public boolean isCurrentRemote() {
    return (!mediaItems.isEmpty() && currentIndex >= 0) && mediaItems.get(currentIndex).isRemote;
  }

  public int getCurrentIndex() {
    return currentIndex;
  }

  public List<MediaItem> getPlaylist() {
    if (mediaItems.isEmpty()) return null;
    ArrayList<MediaItem> mi = new ArrayList<>(mediaItems.size());
    mi.addAll(mediaItems.stream().map(i -> i.mediaItem).collect(Collectors.toList()));
    return mi;
  }

  public void addItem(int index, MediaItem item) {
    mediaItems.add(index, createInfo(item));
    concatMediaSource.addMediaSource(index, createMediaSource(item));
    if (index <= currentIndex) currentIndex++;
  }

  public void removeItem(int index) {
    int last = mediaItems.size() -1;
    if (index < 0 || index > last) return; // no need to remove non existent index, maybe throw error?
    mediaItems.remove(index);
    concatMediaSource.removeMediaSource(index);
    if (last > 0) {
      if (index < currentIndex) currentIndex--;
      else if (index == currentIndex && index == last) {
        currentIndex = 0;
      }
    } else {
      currentIndex = -1;
    }
  }

  public void replaceItem(int index, MediaItem item) {
    MediaSource source = createMediaSource(item);
    MediaItemWithInfo info = createInfo(item);

    mediaItems.add(index, info);
    mediaItems.remove(index + 1);

    concatMediaSource.addMediaSource(index, source);
    concatMediaSource.removeMediaSource(index + 1);
  }

  public void moveItem(int from, int to) {
    MediaItemWithInfo movingItem = mediaItems.get(from);
    mediaItems.remove(from);
    mediaItems.add(to, movingItem);
    concatMediaSource.moveMediaSource(to, from);
  }

  public Boolean onPlayerDiscontinuity(boolean isPeriodTransition, int windowIndex) {
    if (isPeriodTransition && windowIndex != currentIndex) {
      currentIndex = windowIndex;
      Log.d(logTag, "Current index - " + windowIndex);
      return true;
    }

    return false;
  }

  private MediaSource createMediaSource(MediaItem mediaItem) {
    Uri uri = ((UriMediaItem) mediaItem).getUri();
    MediaSource source;
    if (Util.inferContentType(uri) == C.TYPE_HLS) {
      source = new HlsMediaSource.Factory(dataSourceFactory)
        .setTag(mediaItem)
        .createMediaSource(uri);
    } else {
      source = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
        .setTag(mediaItem)
        .createMediaSource(getResourceUri(uri));
    }

    long startPosition = mediaItem.getStartPosition();
    long endPosition = mediaItem.getEndPosition();
    if (startPosition != 0L || endPosition != MediaItem.POSITION_UNKNOWN) {
      if (endPosition == MediaItem.POSITION_UNKNOWN) {
        endPosition = C.TIME_END_OF_SOURCE;
      }

      return new ClippingMediaSource(
        source,
        C.msToUs(startPosition),
        C.msToUs(endPosition),
        false, // TODO: test this setting with notification updates
        false,
        true);
    }

    return source;
  }

  private Uri getResourceUri(Uri uri) {
    if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
      String path = Preconditions.checkNotNull(uri.getPath());
      int resourceIdentifier;
      if (uri.getPathSegments().size() == 1 && uri.getPathSegments().get(0).matches("\\d+")) {
        resourceIdentifier = Integer.parseInt(uri.getPathSegments().get(0));
      } else {
        path = path.replaceAll("^/", "");
        String host = uri.getHost();
        String resourceName = (host != null ? host + ":" : "") + path;
        resourceIdentifier = context.getResources().getIdentifier(resourceName, "raw", context.getPackageName());
      }

      if (resourceIdentifier == 0) throw new IllegalStateException("Could not create MediaSource due to a bad resource id");

      return RawResourceDataSource.buildRawResourceUri(resourceIdentifier);
    }
    return uri;
  }

  private MediaItemWithInfo createInfo(MediaItem mediaItem) {
    MediaItemWithInfo info = new MediaItemWithInfo();

    info.mediaItem = mediaItem;

    info.isRemote = mediaItem instanceof UriMediaItem
      && !Util.isLocalFileUri(((UriMediaItem) mediaItem).getUri());

    return info;
  }

  static class MediaItemWithInfo {
    MediaItem mediaItem;
    boolean isRemote;
  }
}
