package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public class Now<E, A> extends Eval<E, A> {

  private Either<E, A> r;

  public Now(Either<E, A> a) {
    this.r = a;
  }

  @Override
  public Either<E, A> run() {
    return this.r;
  }

  @Override
  public Eval<E, A> step() {
    return this;
  }

  @Override
  public boolean isLater() {
    return false;
  }

  @Override
  public boolean isNow() {
    return true;
  }

  @Override
  public <B> Eval<E, B> map(Function<A, B> f) {
    return new Now(this.r.map(f));
  }

  @Override
  public <B> Eval<E, B> flatMap(Function<A, Eval<E, B>> fa) {
    if (this.r.isGood()) {
      return fa.apply(this.r.getValue());
    } else {
      return new Now<>(new Bad<>(this.r.getErrorValue()));
    }
  }

  @Override
  public Eval<E, A> foreach(Consumer<A> f) {
    return new Now<>(this.r.foreach(f));
  }
}
