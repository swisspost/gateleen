package org.swisspush.gateleen.expansion;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class SlicedLoopTest {

  @Test
  public void processesAllElementsInOrderAcrossSlices(TestContext context) {
    Async async = context.async();
    Vertx vertx = Vertx.vertx();
    List<Integer> processed = new ArrayList<>();
    SlicedLoopFactory factory = new SlicedLoopFactory(new DeferredReactorEnqueue(vertx));

    factory
        .slicedLoop(
            0,
            1,
            "SlicedLoopTest",
            Arrays.asList(1, 2, 3).iterator(),
            new SlicedLoop.Destination<Integer>() {
              @Override
              public void onNext(Integer element) {
                processed.add(element);
              }

              @Override
              public void onEnd() {
                context.assertEquals(Arrays.asList(1, 2, 3), processed);
                vertx.close(closeResult -> async.complete());
              }

              @Override
              public void onError(RuntimeException error) {
                context.fail(error);
                vertx.close(closeResult -> async.complete());
              }
            })
        .resume();
  }

  @Test
  public void stopsProcessingAfterElementFailure(TestContext context) throws InterruptedException {
    Async async = context.async();
    Vertx vertx = Vertx.vertx();
    List<Integer> processed = new ArrayList<>();
    AtomicInteger errors = new AtomicInteger();
    SlicedLoopFactory factory = new SlicedLoopFactory(new DeferredReactorEnqueue(vertx));

    factory
        .slicedLoop(
            0,
            1,
            "SlicedLoopTest",
            Arrays.asList(1, 2, 3).iterator(),
            new SlicedLoop.Destination<Integer>() {
              @Override
              public void onNext(Integer element) {
                processed.add(element);
                if (element == 1) {
                  throw new IllegalStateException("boom");
                }
              }

              @Override
              public void onEnd() {
                context.fail("onEnd must not be called after a failure");
              }

              @Override
              public void onError(RuntimeException error) {
                errors.incrementAndGet();
                async.complete();
              }
            })
        .resume();

    async.awaitSuccess(5000);
    // leave time for already queued slices to run
    Thread.sleep(300);
    context.assertEquals(Arrays.asList(1), processed);
    context.assertEquals(1, errors.get());
    vertx.close();
  }
}
