package org.swisspush.gateleen.routing.auth;

public class AuthHeader {
    private final String key = "Authorization";
    private final String value;

    public AuthHeader(String value) {
        this.value = value;
    }

    public String key() {
        return key;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AuthHeader that = (AuthHeader) o;

        if (!key.equals(that.key)) return false;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + value.hashCode();
        return result;
    }
}
