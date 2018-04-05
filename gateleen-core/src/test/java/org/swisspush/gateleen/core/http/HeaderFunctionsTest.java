package org.swisspush.gateleen.core.http;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonArray;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Consumer;

import static org.swisspush.gateleen.core.http.HeaderFunctions.*;

public class HeaderFunctionsTest {

    @Test
    public void testHeaderFunctionChain() {
        Consumer<EvalScope> chain = (scope) -> {};
        chain = chain.andThen(setAlways   ("xxx", "111"             ));
        chain = chain.andThen(setAlways   ("yyy", "222"             ));
        chain = chain.andThen(setIfAbsent ("yyy", "333"             )); // yyy should stay on value 222
        chain = chain.andThen(setIfPresent("zzz", "444"             )); // zzz should not be there
        chain = chain.andThen(setAlways   ("oli", "{xxx}-{yyy}"     ));
        chain = chain.andThen(remove      ("xxx"                              ));
        chain = chain.andThen(setAlways   ("preSuff","pre-{yyy}-suff")); // test constant prefix and suffix

        // Execute the Header manipulator chain
        MultiMap headers = new CaseInsensitiveHeaders();
        EvalScope evalScope = new EvalScope(headers);
        chain.accept(evalScope);
        System.out.println(headers);

        Assert.assertFalse(headers.contains("xxx")); // explicit removed
        Assert.assertFalse(headers.contains("zzz")); // never added
        Assert.assertEquals("222"         , headers.get("yyy"    ));
        Assert.assertEquals("111-222"     , headers.get("oli"    ));
        Assert.assertEquals("pre-222-suff", headers.get("preSuff"));
        Assert.assertNull("no eval error occured", evalScope.getErrorMessage());
    }

    @Test
    public void testUnresolvableHeaderName() {
        Consumer<EvalScope> c = setAlways("gugus","{no-exist}");
        MultiMap headers = new CaseInsensitiveHeaders();
        EvalScope evalScope = new EvalScope(headers);
        c.accept(evalScope);
        Assert.assertNotNull("eval error message present", evalScope.getErrorMessage());
    }

    @Test
    public void testJsonConfigParser() {
        String json = "[" +
                " { 'header': 'xxx'    , 'value': '111'                                }," + // set always
                " { 'header': 'yyy'    , 'value': '222'                                }," + // set always
                " { 'header': 'yyy'    , 'value': '333'           , 'mode': 'complete' }," + // not set as already there
                " { 'header': 'zzz'    , 'value': '444'           , 'mode': 'override' }," + // not set as not (yet) there
                " { 'header': 'oli'    , 'value': '{xxx}-{yyy}'                        }," + // use variable replacement
                " { 'header': 'xxx'    , 'value': null                                 }," + // remove
                " { 'header': 'preSuff', 'value': 'pre-{yyy}-suff'                     }," + // test constant prefix and suffix
                " { 'header': 'aaa'    , 'value': 'yes'                                }," +
                " { 'header': 'aaa'    , 'value': ''                                   } " + // test that value = empty string also deletes the header
                "]";
        json = json.replace('\'', '"');
        JsonArray config = new JsonArray(json);
        HeaderFunction chain = HeaderFunctions.parseFromJson(config);

        MultiMap headers = new CaseInsensitiveHeaders();
        final EvalScope evalScope = chain.apply(headers);

        Assert.assertFalse(headers.contains("xxx")); // explicit removed
        Assert.assertFalse(headers.contains("zzz")); // never added
        Assert.assertEquals("222"         , headers.get("yyy"    ));
        Assert.assertEquals("111-222"     , headers.get("oli"    ));
        Assert.assertEquals("pre-222-suff", headers.get("preSuff"));
        Assert.assertNull("no eval error occured", evalScope.getErrorMessage());
        Assert.assertFalse(headers.contains("aaa"));
    }
}
