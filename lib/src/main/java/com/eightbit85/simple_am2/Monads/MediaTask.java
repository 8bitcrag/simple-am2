package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class MediaTask<A> {

  public static <T> MediaTask<T> pure(T t) {
    return new MediaTask<T>(() -> Eval.pure(t));
  }

  private Supplier<Eval<A>> r;

  public MediaTask(Supplier<Eval<A>> op) {
    this.r = op;
  }

  public Eval<A> run() {
    return this.r.get();
  }

  public <B> MediaTask<B> map(Function<A, B> f) {
    return new MediaTask<B>(() -> this.run().map(f));
  }

  public <B> MediaTask<B> flatMap(Function<A, MediaTask<B>> fa) {
    return new MediaTask<B>(() -> this.run().flatMap(a -> fa.apply(a).run()));
  }

  public MediaTask<A> foreach(Consumer<A> f) {
    return new MediaTask<A>(() -> this.run().foreach(f));
  }

}