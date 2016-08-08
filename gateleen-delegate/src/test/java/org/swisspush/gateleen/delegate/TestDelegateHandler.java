package org.swisspush.gateleen.delegate;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests some features of the DelegateHandler.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class TestDelegateHandler {
    private static final String DELEGATE_URI = "/gateleen/server/delegate/v1/delegates/";
    private static DelegateHandler delegateHandler;

    @BeforeClass
    public static void init() {
        delegateHandler = new DelegateHandler(null, null, null, null, DELEGATE_URI, null);
    }

    @Test
    public void testGetDelegateName_Recognition() {
        String delegateName = "aName";

        // Positive Cases
        // --------------

        // Case 1: /gateleen/server/delegate/v1/delegates/<name>
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName ));

        // Case 2: /gateleen/server/delegate/v1/delegates/<name>/
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/"));

        // Case 3: /gateleen/server/delegate/v1/delegates/<name>/execution/xxx
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution/xxx"));

        // Case 4: /gateleen/server/delegate/v1/delegates/<name>/execution/
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution/"));

        // Case 5: /gateleen/server/delegate/v1/delegates/<name>/execution
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution"));

        // Case 6: /gateleen/server/delegate/v1/delegates/<name>/definition
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/definition"));

        // --------------

        // Negative Cases
        // --------------
        // Case 1: /gateleen/server/delegate/v1/delegates/<name>/blah
        Assert.assertNull(delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/blah"));

        // Case 2: /gateleen/server/delegate/v1/delegates/<name>/definition/
        Assert.assertNull(delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/definition/"));

        // --------------
    }
}
