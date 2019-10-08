package org.swisspush.gateleen.core.future;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the {@link SequentialFutures} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class SequentialFuturesTest {

    private Vertx vertx;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
    }

    @Test
    public void execute(TestContext context){
        Async async = context.async();
        List<String> executionLog = new ArrayList<>();
        Future<Void> f = Future.succeededFuture();

        List<String> input = new ArrayList<>();
        input.add("one");
        input.add("two");
        input.add("three");

        SequentialFutures.execute(input.iterator(), f,
                (f1, s) -> f1.compose(ignore -> SequentialFuturesTest.this.doAction(s, null, executionLog)))
                .setHandler(res -> {
                    context.assertTrue(res.succeeded());
                    context.assertEquals(new ArrayList<>(Arrays.asList(
                            "started one",
                            "doing one",
                            "started two",
                            "doing two",
                            "started three",
                            "doing three")),
                            executionLog);
                    async.complete();
                });
    }

    @Test
    public void executeWithFailingFuture(TestContext context){
        Async async = context.async();
        List<String> executionLog = new ArrayList<>();
        Future<Void> f = Future.succeededFuture();

        List<String> input = new ArrayList<>();
        input.add("one");
        input.add("two");
        input.add("three");

        SequentialFutures.execute(input.iterator(), f,
                (f1, s) -> f1.compose(ignore -> SequentialFuturesTest.this.doAction(s, "two", executionLog)))
                .setHandler(res -> {
                    context.assertTrue(res.failed());
                    context.assertEquals("booom: two", res.cause().getMessage());
                    context.assertEquals(new ArrayList<>(Arrays.asList(
                            "started one",
                            "doing one",
                            "started two",
                            "doing two",
                            "failed two")),
                            executionLog);
                    async.complete();
                });
    }


    private Future<Void> doAction(String action, String failingAction, List<String> executionLog) {
        executionLog.add("started " + action);
        Future<Void> f = Future.future();
        vertx.setTimer(100, res -> {
            executionLog.add("doing " + action);
            if(failingAction != null && failingAction.equalsIgnoreCase(action)){
                executionLog.add("failed " + action);
                f.fail("booom: " + action);
            } else {
                f.complete();
            }
        });
        return f;
    }
}
