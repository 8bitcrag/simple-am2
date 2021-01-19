package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Later<A> extends Eval<A> {

  private Supplier<A> r;

  public Later(Supplier<A> op) {
    this.r = op;
  }

  @Override
  public A run() {
    return this.r.get();
  }

  @Override
  public Eval<A> step() {
    return new Now<A>(this.run());
  }

  @Override
  public boolean isLater() {
    return true;
  }
  @Override
  public boolean isNow() {
    return false;
  }

  public <B> Eval<B> map(Function<A, B> f) {
    return new Later<B>(() -> f.apply(this.run()));
  }

  public <B> Eval<B> flatMap(Function<A, Eval<B>> fa) {
    return new Stepper<B>(() -> {
      Eval<B> eb = fa.apply(this.run());
      if (eb.isLater()) {
        return eb;
      } else {
        return eb;
      }
    });
  }

  @Override
  public Eval<A> foreach(Consumer<A> f) {
    return new Later<>(() -> {
      A a = this.run();
      f.accept(a);
      return a;
    });
  }
}
