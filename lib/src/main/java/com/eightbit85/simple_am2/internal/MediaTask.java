package com.eightbit85.simple_am2.internal;

import com.eightbit85.simple_am2.Monads.Eval;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class MediaTask<E, A> {

  public static <E, T> MediaTask<E, T> pure(T t) {
    return new MediaTask<>(() -> Eval.pure(t));
  }

  private final Supplier<Eval<E, A>> r;

  public MediaTask(Supplier<Eval<E, A>> op) {
    this.r = op;
  }

  Eval<E, A> run() {
    return this.r.get();
  }

  public <B> MediaTask<E, B> map(Function<A, B> f) {
    return new MediaTask<>(() -> this.run().map(f));
  }

  public <B> MediaTask<E, B> flatMap(Function<A, MediaTask<E, B>> fa) {
    return new MediaTask<>(() -> this.run().flatMap(a -> fa.apply(a).run()));
  }

  public MediaTask<E, A> foreach(Consumer<A> f) {
    return new MediaTask<>(() -> this.run().foreach(f));
  }

}