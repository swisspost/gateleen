package org.swisspush.gateleen.hook;

/**
 * Defines the possible types when a trigger could be fired.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public enum HookTriggerType {
    BEFORE("before"),
    AFTER("after");

    private final String type;

    HookTriggerType(String type) {
        this.type = type;
    }

    public String text() {
        return this.type;
    }
}
