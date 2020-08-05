package com.d8bit85.simple_am2.internal;

import android.content.Context;
import android.os.Looper;

/**
 *  This class is primarily to facilitate testing. It keeps the creation of ExoPlayerWrapper
 *  package private, while allowing for creation to be injected. For almost all conceivable
 *  use cases, getDefaultFactory should be used.
 */
public abstract class ExoWrapperFactory {

  public static ExoWrapperFactory getDefaultFactory() {
    return new ExoWrapperFactory() {
      @Override
      ExoPlayerWrapper getWrapper(Context context, Looper looper, ExoPlayerWrapper.WrapperListener listener) {
        return new ExoPlayerWrapper(context, looper, listener);
      }
    };
  }

  abstract ExoPlayerWrapper getWrapper(Context context, Looper looper, ExoPlayerWrapper.WrapperListener listener);

}
