package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public class Good<E, A> extends Either<E, A> {

  private A value;

  public Good(A value) {
    this.value = value;
  }

  @Override
  public boolean isGood() {
    return true;
  }

  @Override
  public E getErrorValue() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Cannot get Error from a Good, use getValue instead.");
  }

  @Override
  public A getValue() throws UnsupportedOperationException {
    return value;
  }

  @Override
  public <B> Either<E, B> map(Function<A, B> f) {
    return new Good<>(f.apply(value));
  }

  @Override
  public <B> Either<E, B> flatMap(Function<A, Either<E, B>> fa) {
    return fa.apply(value);
  }

  @Override
  public Either<E, A> foreach(Consumer<A> f) {
    f.accept(value);
    return this;
  }
}
