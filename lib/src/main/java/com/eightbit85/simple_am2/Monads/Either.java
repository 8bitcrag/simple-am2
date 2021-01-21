package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public abstract class Either<E, A> {

  public abstract boolean isGood();
  public abstract E getErrorValue() throws UnsupportedOperationException;
  public abstract A getValue() throws UnsupportedOperationException;

  public abstract <B> Either<E, B> map(Function<A, B> f);
  public abstract <B> Either<E, B> flatMap(Function<A, Either<E, B>> fa);
  public abstract Either<E, A> foreach(Consumer<A> f);

  public static <E, T> Either<E, T> pure(T t) {
    return new Good<>(t);
  }
}

