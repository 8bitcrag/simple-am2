package com.eightbit85.simple_am2.Monads;

import com.eightbit85.simple_am2.Monads.Eval;
import com.eightbit85.simple_am2.Monads.Later;
import com.eightbit85.simple_am2.Monads.MediaTask;
import com.eightbit85.simple_am2.Monads.Now;
import com.eightbit85.simple_am2.Monads.Stepper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaTaskTests {

  @Test
  public void sequence_now_now_is_eager() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Now<>(2));
    MediaTask<String> mts = new MediaTask<>(() -> new Now<>("hello"));

    MediaTask<String> seq = mti.flatMap(i -> mts);
    Eval<String> ev = seq.run();

    assertEquals(true, ev.isNow());
    assertEquals("hello", ev.run());
  }

  @Test
  public void sequence_later_now_is_deferred() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Later<>(() -> 2));
    MediaTask<String> mts = new MediaTask<>(() -> new Now<>("hello"));

    MediaTask<String> seq = mti.flatMap(i -> mts);

    Eval<String> ev = seq.run();
    assertEquals(false, ev.isNow());

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2.isNow());

    assertEquals("hello", ev2.run());

  }

  @Test
  public void sequence_later_later_is_deferred() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Later<>(() -> 2));
    MediaTask<String> mts = new MediaTask<>(() -> new Later<>(() -> "hello"));

    MediaTask<String> seq = mti.flatMap(i -> mts);

    Eval<String> ev = seq.run();
    assertEquals(true, ev instanceof Stepper);

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2.isLater());

    assertEquals("hello", ev2.run());

  }

  @Test
  public void sequence_later_later_later_is_deferred() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Later<>(() -> 2));
    MediaTask<String> mts = new MediaTask<>(() -> new Later<>(() -> "hello"));
    MediaTask<String> seq = mti.flatMap(i -> mts).flatMap((s -> new MediaTask<>(() -> new Later<>(() -> s + " world"))));

    Eval<String> ev = seq.run();
    assertEquals(true, ev instanceof Stepper);

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2 instanceof Stepper);

    Eval<String> ev3 = ev2.step();
    assertEquals(true, ev3.isLater());

    Eval<String> ev4 = ev3.step();
    assertEquals(true, ev4.isNow());

    assertEquals("hello world", ev4.run());
    assertEquals("hello world", ev.run());

  }

  @Test
  public void sequence_later_later_now_is_deferred() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Later<>(() -> 2));
    MediaTask<String> mts = new MediaTask<>(() -> new Later<>(() -> "hello"));
    MediaTask<String> seq = mti.flatMap(i -> mts).flatMap((s -> new MediaTask<>(() -> new Now<>(s + " world"))));

    Eval<String> ev = seq.run();
    assertEquals(true, ev instanceof Stepper);

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2 instanceof Stepper);

    Eval<String> ev3 = ev2.step();
    assertEquals(true, ev3.isNow());

    Eval<String> ev4 = ev3.step();
    assertEquals(true, ev4.isNow());

    assertEquals("hello world", ev4.run());
    assertEquals("hello world", ev.run());

  }

  @Test
  public void sequence_later_now_later_later_is_deferred() {
    MediaTask<Integer> mti = new MediaTask<>(() -> new Later<>(() -> 2));
    MediaTask<String> mts = new MediaTask<>(() -> new Now<>("hello"));
    MediaTask<String> seq = mti.flatMap(i -> mts)
      .flatMap((s -> new MediaTask<>(() -> new Later<>(() -> s + " world"))))
      .flatMap((w -> new MediaTask<>(() -> new Later<>(() -> w + " again!"))));

    Eval<String> ev = seq.run();
    assertEquals(true, ev instanceof Stepper);

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2 instanceof Stepper);

    Eval<String> ev3 = ev2.step();
    assertEquals(true, ev3.isLater());

    Eval<String> ev4 = ev3.step();
    assertEquals(true, ev4.isNow());

    assertEquals("hello world again!", ev4.run());
    assertEquals("hello world again!", ev.run());

  }

  @Test
  public void nested_later_now_later_later_is_deferred() {
    MediaTask<String> mt = new MediaTask<>(() -> new Later<>(() -> 2)).flatMap(i -> {
      return new MediaTask<>(() -> new Now<>("hello")).flatMap(s -> {
        return new MediaTask<>(() -> new Later<>(() -> " world")).flatMap(w -> {
          return new MediaTask<>(() -> new Later<>(() -> s + w + " again!"));
        });
      });
    });

    Eval<String> ev = mt.run();
    assertEquals(true, ev instanceof Stepper);

    Eval<String> ev2 = ev.step();
    assertEquals(true, ev2 instanceof Stepper);

    Eval<String> ev3 = ev2.step();
    assertEquals(true, ev3.isLater());

    Eval<String> ev4 = ev3.step();
    assertEquals(true, ev4.isNow());

    assertEquals("hello world again!", ev4.run());
    assertEquals("hello world again!", ev.run());

  }


}
