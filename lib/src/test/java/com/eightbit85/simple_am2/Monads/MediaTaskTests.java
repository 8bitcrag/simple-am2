package com.eightbit85.simple_am2.Monads;

import com.eightbit85.simple_am2.internal.MediaTask;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaTaskTests {

  @Test
  public void sequence_now_now_is_eager() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Now<>(new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Now<>(new Good<>("hello")));

    MediaTask<Exception, String> seq = mti.flatMap(i -> mts);
    Eval<Exception, String> ev = seq.run();

    assertTrue(ev.isNow());
    assertEquals("hello", ev.run().getValue());
  }

  @Test
  public void sequence_later_now_is_deferred() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Later<>(() -> new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Now<>(new Good<>("hello")));

    MediaTask<Exception, String> seq = mti.flatMap(i -> mts);

    Eval<Exception, String> ev = seq.run();
    assertFalse(ev.isNow());

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2.isNow());

    assertEquals("hello", ev2.run().getValue());

  }

  @Test
  public void sequence_later_later_steps() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Later<>(() -> new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Later<>(() -> new Good<>("hello")));

    MediaTask<Exception, String> seq = mti.flatMap(i -> mts);

    Eval<Exception, String> ev = seq.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2.isLater());

    assertEquals("hello", ev2.run().getValue());

  }

  @Test
  public void sequence_later_later_later_steps() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Later<>(() -> new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Later<>(() -> new Good<>("hello")));
    MediaTask<Exception, String> seq = mti.flatMap(i -> mts).flatMap((s -> new MediaTask<>(() -> new Later<>(() -> new Good<>(s + " world")))));

    Eval<Exception, String> ev = seq.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Stepper);

    Eval<Exception, String> ev3 = ev2.step();
    assertTrue(ev3.isLater());

    Eval<Exception, String> ev4 = ev3.step();
    assertTrue(ev4.isNow());

    assertEquals("hello world", ev4.run().getValue());
    assertEquals("hello world", ev.run().getValue());

  }

  @Test
  public void sequence_later_later_now_steps() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Later<>(() -> new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Later<>(() -> new Good<>("hello")));
    MediaTask<Exception, String> seq = mti.flatMap(i -> mts).flatMap((s -> new MediaTask<>(() -> new Now<>(new Good<>(s + " world")))));

    Eval<Exception, String> ev = seq.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Stepper);

    Eval<Exception, String> ev3 = ev2.step();
    assertTrue(ev3.isNow());

    Eval<Exception, String> ev4 = ev3.step();
    assertTrue(ev4.isNow());

    assertEquals("hello world", ev4.run().getValue());
    assertEquals("hello world", ev.run().getValue());

  }

  @Test
  public void sequence_later_now_later_later_steps() {
    MediaTask<Exception, Integer> mti = new MediaTask<>(() -> new Later<>(() -> new Good<>(2)));
    MediaTask<Exception, String> mts = new MediaTask<>(() -> new Now<>(new Good<>("hello")));
    MediaTask<Exception, String> seq = mti.flatMap(i -> mts)
      .flatMap((s -> new MediaTask<>(() -> new Later<>(() -> new Good<>(s + " world")))))
      .flatMap((w -> new MediaTask<>(() -> new Later<>(() -> new Good<>(w + " again!")))));

    Eval<Exception, String> ev = seq.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Stepper);

    Eval<Exception, String> ev3 = ev2.step();
    assertTrue(ev3.isLater());

    Eval<Exception, String> ev4 = ev3.step();
    assertTrue(ev4.isNow());

    assertEquals("hello world again!", ev4.run().getValue());
    assertEquals("hello world again!", ev.run().getValue());

  }

  @Test
  public void nested_later_now_later_later_steps() {
    MediaTask<Exception, String> mt = new MediaTask<Exception, Integer>(() -> new Later<>(() -> new Good<>(2))).flatMap(i -> {
      return new MediaTask<Exception, String>(() -> new Now<>(new Good<>("hello"))).flatMap(s -> {
        return new MediaTask<>(() -> new Later<>(() -> new Good<Exception, String>(" world"))).flatMap(w -> {
          return new MediaTask<>(() -> new Later<>(() -> new Good<>(s + w + " again!")));
        });
      });
    });

    Eval<Exception, String> ev = mt.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Stepper);

    Eval<Exception, String> ev3 = ev2.step();
    assertTrue(ev3.isLater());

    Eval<Exception, String> ev4 = ev3.step();
    assertTrue(ev4.isNow());

    assertEquals("hello world again!", ev4.run().getValue());
    assertEquals("hello world again!", ev.run().getValue());

  }

  @Test
  public void nested_later_later_later_later_short_circuits() {
    AtomicReference<String> unaltered = new AtomicReference<>("none");

    MediaTask<Exception, String> mt = new MediaTask<Exception, Integer>(() -> new Later<>(() -> new Good<>(2))).flatMap(i -> {
      return new MediaTask<Exception, String>(() -> new Later<>(() -> new Bad<>(new IllegalArgumentException("test exception")))).flatMap(s -> {
        return new MediaTask<>(() -> new Later<>(() -> new Good<Exception, String>(" world"))).flatMap(w -> {
          return new MediaTask<>(() -> new Later<>(() -> {
            unaltered.set("some");
            return new Good<>(s + w + " again!");
          }));
        });
      });
    });

    Eval<Exception, String> ev = mt.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Stepper);

    Eval<Exception, String> ev3 = ev2.step();
    assertTrue(ev3.isNow());


    assertEquals("test exception", ev3.run().getErrorValue().getMessage());
    assertEquals("test exception", ev.run().getErrorValue().getMessage());
    assertEquals("none", unaltered.get());

  }

  @Test
  public void nested_later_now_later_later_short_circuits() {
    AtomicReference<String> unaltered = new AtomicReference<>("none");

    MediaTask<Exception, String> mt = new MediaTask<Exception, Integer>(() -> new Later<>(() -> new Good<>(2))).flatMap(i -> {
      return new MediaTask<Exception, String>(() -> new Now<>(new Bad<>(new IllegalArgumentException("test exception")))).flatMap(s -> {
        return new MediaTask<>(() -> new Later<>(() -> new Good<Exception, String>(" world"))).flatMap(w -> {
          return new MediaTask<>(() -> new Later<>(() -> {
            unaltered.set("some");
            return new Good<>(s + w + " again!");
          }));
        });
      });
    });

    Eval<Exception, String> ev = mt.run();
    assertTrue(ev instanceof Stepper);

    Eval<Exception, String> ev2 = ev.step();
    assertTrue(ev2 instanceof Now);
    assertFalse(ev2.run().isGood());

    assertEquals("test exception", ev2.run().getErrorValue().getMessage());
    assertEquals("test exception", ev.run().getErrorValue().getMessage());
    assertEquals("none", unaltered.get());

  }


}
