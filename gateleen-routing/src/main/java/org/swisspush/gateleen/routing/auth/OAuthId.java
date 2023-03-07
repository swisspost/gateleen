package org.swisspush.gateleen.routing.auth;

public class OAuthId {

    private final String oAuthId;

    private OAuthId(String oAuthId) {
        this.oAuthId = oAuthId;
    }

    public static OAuthId of(String oAuthId){
        return new OAuthId(oAuthId);
    }

    public String oAuthId() {
        return oAuthId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OAuthId oAuthId1 = (OAuthId) o;

        return oAuthId.equals(oAuthId1.oAuthId);
    }

    @Override
    public int hashCode() {
        return oAuthId.hashCode();
    }
}
