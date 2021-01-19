package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class Eval<A> {
  public abstract boolean isLater();
  public abstract boolean isNow();

  public abstract A run();
  public abstract Eval<A> step();

  public static <T> Eval<T> pure(T t) {
    return new Now<T>(t);
  }

  public abstract <B> Eval<B> map(Function<A, B> f);
  public abstract <B> Eval<B> flatMap(Function<A, Eval<B>> fa);
  public abstract Eval<A> foreach(Consumer<A> f);
}

