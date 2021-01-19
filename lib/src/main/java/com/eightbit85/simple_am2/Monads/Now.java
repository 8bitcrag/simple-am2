package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;

public class Now<A> extends Eval<A> {

  private A r;

  public Now(A a) {
    this.r = a;
  }

  @Override
  public A run() {
    return this.r;
  }

  @Override
  public Eval<A> step() {
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
  public <B> Eval<B> map(Function<A, B> f) {
    return new Now(f.apply(this.r));
  }

  @Override
  public <B> Eval<B> flatMap(Function<A, Eval<B>> fa) {
    return fa.apply(this.r);
  }

  @Override
  public Eval<A> foreach(Consumer<A> f) {
    f.accept(this.r);
    return this;
  }
}
