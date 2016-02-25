package org.swisspush.gateleen.player.log;

import org.swisspush.gateleen.player.exchange.Exchange;
import com.google.common.collect.FluentIterable;

/**
 * Abstraction for a request log, be it read from a file, from memory or from network.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public abstract class RequestLog extends FluentIterable<Exchange> {

}
