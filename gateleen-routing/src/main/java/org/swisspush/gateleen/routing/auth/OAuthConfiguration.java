package org.swisspush.gateleen.routing.auth;

import io.vertx.ext.auth.oauth2.OAuth2FlowType;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Container holding OAuth2.0 configuration values
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class OAuthConfiguration {

    private final OAuth2FlowType flowType;
    private final String clientId;
    private final String clientSecret;
    private final String site;
    private final String tokenPath;
    private final String authPath;
    @Nullable
    private final List<String> scopes;

    @Nullable
    private final List<String> supportedGrantTypes;

    public OAuthConfiguration(OAuth2FlowType flowType, String clientId, String clientSecret, String site, String tokenPath, String authPath,
                              @Nullable List<String> scopes, @Nullable List<String> supportedGrantTypes) {
        this.flowType = flowType;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.site = site;
        this.tokenPath = tokenPath;
        this.authPath = authPath;
        this.scopes = scopes;
        this.supportedGrantTypes = supportedGrantTypes;
    }

    public OAuth2FlowType flowType() {
        return flowType;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String site() {
        return site;
    }

    public String tokenPath() {
        return tokenPath;
    }

    public String authPath() {
        return authPath;
    }

    @Nullable
    public List<String> scopes() {
        return scopes;
    }

    @Nullable
    public List<String> supportedGrantTypes() {
        return supportedGrantTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OAuthConfiguration that = (OAuthConfiguration) o;

        if (flowType != that.flowType) return false;
        if (!clientId.equals(that.clientId)) return false;
        if (!clientSecret.equals(that.clientSecret)) return false;
        if (!site.equals(that.site)) return false;
        if (!tokenPath.equals(that.tokenPath)) return false;
        if (!authPath.equals(that.authPath)) return false;
        if (!Objects.equals(scopes, that.scopes)) return false;
        return Objects.equals(supportedGrantTypes, that.supportedGrantTypes);
    }

    @Override
    public int hashCode() {
        int result = flowType.hashCode();
        result = 31 * result + clientId.hashCode();
        result = 31 * result + clientSecret.hashCode();
        result = 31 * result + site.hashCode();
        result = 31 * result + tokenPath.hashCode();
        result = 31 * result + authPath.hashCode();
        result = 31 * result + (scopes != null ? scopes.hashCode() : 0);
        result = 31 * result + (supportedGrantTypes != null ? supportedGrantTypes.hashCode() : 0);
        return result;
    }
}
