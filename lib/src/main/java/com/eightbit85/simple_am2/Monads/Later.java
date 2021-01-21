package com.eightbit85.simple_am2.Monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Later<E, A> extends Eval<E, A> {

  private Supplier<Either<E, A>> r;

  public Later(Supplier<Either<E, A>> op) {
    this.r = op;
  }

  @Override
  public Either<E, A> run() {
    return this.r.get();
  }

  @Override
  public Eval<E, A> step() {
    return new Now<>(this.run());
  }

  @Override
  public boolean isLater() {
    return true;
  }
  @Override
  public boolean isNow() {
    return false;
  }

  public <B> Eval<E, B> map(Function<A, B> f) {
    return new Later<>(() -> this.run().map(f));
  }

  public <B> Eval<E, B> flatMap(Function<A, Eval<E, B>> fa) {
    return new Stepper<>(() -> {
      Either<E, A> ea = this.run();
      if (ea.isGood()) {
        return fa.apply(ea.getValue());
      } else {
        return new Now<>(new Bad<>(ea.getErrorValue()));
      }
    });
  }

  @Override
  public Eval<E, A> foreach(Consumer<A> f) {
    return new Later<>(() -> {
      Either<E, A> ea = this.run();
      return ea.foreach(f);
    });
  }
}
