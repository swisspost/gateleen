package org.swisspush.gateleen.player.player;

import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.IgnoreHeadersTransformer;
import org.swisspush.gateleen.player.exchange.TimeResolver;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.player.log.*;

import java.util.Iterator;

import static com.google.common.base.Predicates.alwaysTrue;

/**
 * Replays requests from a request log.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Player {

    private FluentIterable<Exchange> inputLog;
    private BufferingRequestLog outputLog = new BufferingRequestLog();
    private Collector collector;
    private ExchangeHandler exchangeHandler = new ExchangeHandler();

    private Function<Long, Long> timingFunction = input -> input;
    private Client client = new Client();
    private long gracePeriod = 200L;
    private boolean keepCollectorOpen = false;
    private Thread shutdownHook;

    public Player() {
        shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                LoggerFactory.getLogger(this.getClass()).warn("You created a player but never started. Did you forget to call 'play()' ?.");
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public Player setInputLog(FluentIterable<Exchange> inputLog) {
        this.inputLog = inputLog;
        return this;
    }

    public Player setInputLog(String urlPrefix, String inputLogLocation) {
        return setInputLog(urlPrefix, inputLogLocation, alwaysTrue());
    }

    public Player setInputLog(String urlPrefix, String inputLogLocation, Predicate<? super Exchange> inputFilter) {
        inputLog = new ResourceRequestLog(urlPrefix, inputLogLocation).filter(inputFilter);
        return this;
    }

    public FluentIterable<Exchange> getInputLog() {
        return inputLog;
    }

    public RequestLog getOutputLog() {
        return outputLog;
    }

    public Player setOutputCollector(Collector collector) {
        keepCollectorOpen = true;
        this.collector = collector;
        return this;
    }

    public Player setOutputCollector(String urlPrefix, String sockPath, String eventBusAddress) {
        return setOutputCollector(urlPrefix, sockPath, eventBusAddress, alwaysTrue());
    }

    public Player setOutputCollector(String urlPrefix, String sockPath, String eventBusAddress, Predicate<? super Exchange> outputFilter) {
        collector = new EventBusCollector(urlPrefix, sockPath, eventBusAddress, outputFilter);
        return this;
    }

    public Player setExchangeHandler(ExchangeHandler exchangeHandler) {
        this.exchangeHandler = exchangeHandler;
        return this;
    }

    public Player setTimingFunction(Function<Long, Long> timingFunction) {
        this.timingFunction = timingFunction;
        return this;
    }

    /**
     * The time (ms) at the end of the scenario to wait before closing the collector. This is needed to collect the last log events.
     * Defaults to 200 ms.
     * 
     * @param gracePeriod gracePeriod
     * @return Player
     */
    public Player setGracePeriod(long gracePeriod) {
        this.gracePeriod = gracePeriod;
        return this;
    }

    public Player play() {
        if (inputLog == null) {
            throw new IllegalStateException("inputLog must be set");
        }
        long lastRecordedTime = 0;
        long absoluteTime;
        Iterator<Exchange> iterator = inputLog.iterator();
        try {
            if (iterator.hasNext()) {
                Exchange exchange = iterator.next();
                TimeResolver timeresolver = new TimeResolver(exchange.getTimestamp());
                while (exchange != null) {
                    absoluteTime = System.currentTimeMillis();
                    try {
                        exchange = exchangeHandler.before(exchange);
                    } catch (Exception e) {
                        if (e instanceof RuntimeException) {
                            throw (RuntimeException) e;
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                    if (exchange != null) {
                        exchange = IgnoreHeadersTransformer.ignoreCommonHeaders().apply(exchange);
                        BufferingRequestLog partialOutputLog = null;
                        if (collector != null) {
                            partialOutputLog = collector.getRequestLog();
                            if (exchangeHandler.resetTailOutputLog()) {
                                partialOutputLog.dump(outputLog);
                            }
                            collector.start();
                        }
                        exchange.setResponse(client.exchange(exchange.getRequest()));
                        try {
                            if (!exchangeHandler.after(exchange, partialOutputLog != null ? partialOutputLog.transform(IgnoreHeadersTransformer.ignoreCommonHeaders()) : null)) {
                                break;
                            }
                        } catch (Exception e) {
                            if (e instanceof RuntimeException) {
                                throw (RuntimeException) e;
                            } else {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                    exchange = null;
                    if (iterator.hasNext()) {
                        exchange = iterator.next();
                        long recordedTime = timeresolver.resolve(exchange.getTimestamp());
                        long delay = timingFunction.apply(recordedTime - lastRecordedTime);
                        if (delay < 0) {
                            throw new RuntimeException("Exchange timestamp is smaller than previous one: " + exchange);
                        }
                        if (delay > 60 * 1000) {
                            LoggerFactory.getLogger(this.getClass()).warn("Time to wait until firing next exchange is greater than 1 minute: " + exchange);
                        }
                        lastRecordedTime = recordedTime;
                        long elapsedTime = System.currentTimeMillis() - absoluteTime;
                        try {
                            Thread.sleep(Math.max(delay - elapsedTime, 0));
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        } finally {
            if (collector != null) {
                try {
                    Thread.sleep(gracePeriod);
                    collector.getRequestLog().dump(outputLog);
                } catch (InterruptedException e) {
                    // ignore
                }
                if (!keepCollectorOpen) {
                    collector.stop();
                }
            }
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        return this;
    }

    public Player setClient(Client client) {
        this.client = client;
        return this;
    }
}
