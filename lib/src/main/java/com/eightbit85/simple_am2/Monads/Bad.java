package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public class Bad<E, A> extends Either<E, A> {

  public boolean isGood = false;

  private E value;

  public Bad(E value) {
    this.value = value;
  }

  @Override
  public boolean isGood() {
    return false;
  }

  @Override
  public E getErrorValue() throws UnsupportedOperationException {
    return value;
  }

  @Override
  public A getValue() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Cannot get underlying value of an Error, use getErrorValue instead.");
  }

  @Override
  public <B> Either<E, B> map(Function<A, B> f) {
    return new Bad<E, B>(value);
  }

  @Override
  public <B> Either<E, B> flatMap(Function<A, Either<E, B>> fa) {
    return new Bad<E, B>(value);
  }

  @Override
  public Either<E, A> foreach(Consumer<A> f) {
    return this;
  }
}
