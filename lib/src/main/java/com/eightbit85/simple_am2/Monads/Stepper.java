package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Stepper<E, A> extends Eval<E, A> {

  private Supplier<Eval<E, A>> morph;

  public Stepper(Supplier<Eval<E, A>> step) {
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
  public Either<E, A> run() {
    return morph.get().run();
  }

  @Override
  public Eval<E, A> step() {
    return morph.get();
  }

  @Override
  public <B> Eval<E, B> map(Function<A, B> f) {
    return new Stepper<E, B>(() -> this.morph.get().map(f));
  }

  @Override
  public <B> Eval<E, B> flatMap(Function<A, Eval<E, B>> fa) {
    return new Stepper<E, B>(() -> this.morph.get().flatMap(fa));
  }

  @Override
  public Eval<E, A> foreach(Consumer<A> f) {
    return new Stepper<>(() -> this.morph.get().foreach(f));
  }
}
