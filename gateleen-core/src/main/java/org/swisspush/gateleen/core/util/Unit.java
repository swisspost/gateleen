package org.swisspush.gateleen.core.util;

/**
 * The type with only one value: the Unit object. Used when {@link Void} cannot be used
 * (for example needs a non-null value).
 * <p>
 * Is immutable, supports equals/hashcode (since it's a singleton reference equality).
 * <p>
 * Same in rust:
 * https://doc.rust-lang.org/nightly/std/primitive.unit.html
 * <p>
 * Same in kotlin:
 * https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html
 */
public final class Unit {
    private static final Unit INSTANCE = new Unit();

    /**
     * private: MUST only have one instance!
     */
    private Unit() {
    }

    public static Unit get() {
        return INSTANCE;
    }
}
