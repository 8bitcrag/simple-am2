package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public abstract class Eval<E, A> {
  public abstract boolean isLater();
  public abstract boolean isNow();

  public abstract Either<E, A> run();
  public abstract Eval<E, A> step();

  public static <E, T> Eval<E, T> pure(T t) {
    return new Now<>(new Good<>(t));
  }

  public abstract <B> Eval<E, B> map(Function<A, B> f);
  public abstract <B> Eval<E, B> flatMap(Function<A, Eval<E, B>> fa);
  public abstract Eval<E, A> foreach(Consumer<A> f);
}

