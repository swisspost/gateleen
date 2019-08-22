package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains functions for request handling.
 * 
 * @author https://github.com/floriankammermann [Florian Kammermann]
 */
public class RoleExtractor {

    private static String groupHeader = "x-rp-grp";
    private static String roleHeader = "x-roles";
    private Pattern rolePattern;

    public RoleExtractor(String rolePattern) {
        this.rolePattern = Pattern.compile(rolePattern);
    }

    /**
     * Extract the roles from the request.
     * 
     * @param request the request
     * @return the role set or null if no role information was found.
     */
    public Set<String> extractRoles(HttpServerRequest request) {
        Set<String> roles = null;
        MultiMap headers = request.headers();
        String proxyGroupHeader = headers.get(groupHeader);
        String userRoles = null;
        if (proxyGroupHeader != null) {
            userRoles = proxyGroupHeader;
        } else {
            userRoles = headers.get(roleHeader);
        }
        if (userRoles != null) {
            roles = new HashSet<>();
            String[] split = userRoles.split(",");
            for (String r : split) {
                r = r.trim();
                r = r.toLowerCase();
                Matcher matcher = rolePattern.matcher(r);
                if (matcher.matches()) {
                    roles.add(matcher.group(1));
                }
            }
        }
        return roles;
    }

}
