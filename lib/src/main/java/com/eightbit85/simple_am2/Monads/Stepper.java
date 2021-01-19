package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Stepper<A> extends Eval<A> {

  private Supplier<Eval<A>> morph;

  public Stepper(Supplier<Eval<A>> step) {
    this.morph = step;
  }

  @Override
  public boolean isLater() {
    return false;
  }

  @Override
  public boolean isNow() {
    return false;
  }

  @Override
  public A run() {
    return morph.get().run();
  }

  @Override
  public Eval<A> step() {
    return morph.get();
  }

  @Override
  public <B> Eval<B> map(Function<A, B> f) {
    return new Stepper<B>(() -> this.morph.get().map(f));
  }

  @Override
  public <B> Eval<B> flatMap(Function<A, Eval<B>> fa) {
    return new Stepper<B>(() -> this.morph.get().flatMap(fa));
  }

  @Override
  public Eval<A> foreach(Consumer<A> f) {
    return new Stepper<>(() -> this.morph.get().foreach(f));
  }
}
