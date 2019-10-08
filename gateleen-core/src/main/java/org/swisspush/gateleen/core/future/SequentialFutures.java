package org.swisspush.gateleen.core.future;

import java.util.Iterator;
import java.util.function.BiFunction;

/**
 * Executes Futures sequentially and stops when the first Future failed.
 * Based on the solution mentioned here: https://stackoverflow.com/questions/51513678/reduce-future-sequentially-in-vert-x
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class SequentialFutures {

    public static <A, B> B execute(Iterator<A> iterator, B identity, BiFunction<B, A, B> function) {
        B result = identity;
        while(iterator.hasNext()) {
            A next = iterator.next();
            result = function.apply(result, next);
        }
        return result;
    }
}
