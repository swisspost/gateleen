package org.swisspush.gateleen.core.util;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A result inspired by rust. See https://doc.rust-lang.org/stable/std/result/enum.Result.html
 * <p>
 * A result is either successful (ok) or unsuccessful (error).
 * All values should be immutable.
 * Does NOT support null values.
 * Implements equals / hashcode / toString and is immutable.
 */
public final class Result<TOk, TErr> {

    private static Result UNIT_ERROR = new Result<>(null, Unit.get());
    private static Result UNIT_OK = new Result<>(Unit.get(), null);

    @Nullable
    private final TOk okValue;
    @Nullable
    private final TErr errValue;

    private Result(@Nullable TOk okValue, @Nullable TErr errValue) {
        if (okValue != null && errValue != null) {
            throw new IllegalStateException("A result cannot be ok and error at the same time");
        }
        this.okValue = okValue;
        this.errValue = errValue;
    }

    public static <TOk, TErr> Result<TOk, TErr> ok(TOk value) {
        if (value == Unit.get()) {
            //noinspection unchecked
            return UNIT_OK;
        }
        return new Result<>(Objects.requireNonNull(value, "You tried to construct a " +
                "OK result with null value. Hint: Use Unit."), null);
    }

    public static <TOk, TErr> Result<TOk, TErr> err(TErr value) {
        if (value == Unit.get()) {
            //noinspection unchecked
            return UNIT_ERROR;
        }
        return new Result<>(null, Objects.requireNonNull(value, "You tried to " +
                "construct an ERROR result with null value. Hint: Use Unit."));
    }

    public boolean isOk() {
        return this.okValue != null;
    }

    /**
     * See https://doc.rust-lang.org/stable/std/result/enum.Result.html#method.unwrap
     * Returns the ok result or throws if this result is in error state.
     */
    public TOk ok() throws IllegalStateException {
        final TOk localOk = this.okValue;
        if (localOk == null) {
            throw new IllegalStateException("Cannot call this method for results in error state");
        }
        return localOk;
    }

    /**
     * Returns present if ok or absent if error.
     */
    public Optional<TOk> okOptional() {
        return Optional.ofNullable(okValue);
    }

    public boolean isErr() {
        return !isOk();
    }

    public TErr err() throws IllegalStateException {
        final TErr localError = errValue;
        if (localError == null) {
            throw new IllegalStateException("Cannot call this method for results in ok state");
        }
        return localError;
    }

    public <R> R handle(RetHandler<TOk, TErr, R> handler) {
        if (isOk()) {
            return handler.onOk(ok());
        } else {
            return handler.onErr(err());
        }
    }

    public void handle(Handler<TOk, TErr> handler) {
        if (isOk()) {
            handler.onOk(ok());
        } else {
            handler.onErr(err());
        }
    }

    public void ifOk(Consumer<TOk> okHandler) {
        if (isOk()) {
            okHandler.accept(ok());
        }
    }

    /**
     * If this is in error state, just returns a result with the same error.
     * If it's in OK state, maps the OK value using the map function.
     */
    public <T> Result<T, TErr> mapOk(Function<TOk, T> mapFunction) {
        if (isOk()) {
            return Result.ok(mapFunction.apply(ok()));
        } else {
            return Result.err(err());
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Result<?, ?> result = (Result<?, ?>) o;

        if (okValue != null ? !okValue.equals(result.okValue) : result.okValue != null)
            return false;
        return errValue != null ? errValue.equals(result.errValue) : result.errValue == null;
    }

    @Override
    public int hashCode() {
        int result = okValue != null ? okValue.hashCode() : 0;
        result = 31 * result + (errValue != null ? errValue.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        if (isOk()) {
            return "ResultOk{" + okValue + '}';
        } else {
            return "ResultErr{" + errValue + '}';
        }
    }

    public interface RetHandler<TOk, TErr, R> {
        R onOk(TOk ok);

        R onErr(TErr error);
    }

    public interface Handler<TOk, TErr> {
        void onOk(TOk ok);

        void onErr(TErr error);
    }
}
