package org.swisspush.gateleen.core.http;

import io.vertx.core.MultiMap;

@FunctionalInterface
public interface HeaderFunction {

    HeaderFunctions.EvalScope apply(MultiMap headers);
}
