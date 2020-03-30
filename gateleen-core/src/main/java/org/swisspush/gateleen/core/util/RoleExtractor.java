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

    public final static String groupHeader = "x-rp-grp";
    public final static String roleHeader = "x-roles";
    private Pattern rolePattern;

    public RoleExtractor(String rolePattern) {
        this.rolePattern = Pattern.compile(rolePattern);
    }

    /*
    Default constructor performing a full match by default of each role which means the
    roles given are extracted and returned as is without any filtering.
     */
    public RoleExtractor() {
        this.rolePattern = Pattern.compile("(.*)");
    }

    /**
     * Extract the roles from the request.
     * 
     * @param request the request
     * @return the role set or null if no role information was found.
     */
    public Set<String> extractRoles(HttpServerRequest request) {
        return extractRoles(request.headers());
    }


    /**
     * Extract the roles from the given http header
     *
     * @param headers the request/response headers to analyse
     * @return the role set or null if no role information was found.
     */
    public Set<String> extractRoles(MultiMap headers) {
        Set<String> roles = null;
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
                    // check if we have found any capture group as we would crash here if not.
                    // because we assume that the given regex MUST contain a capture group.
                    if (matcher.groupCount()>0) {
                        roles.add(matcher.group(1));
                    }
                }
            }
        }
        return roles;
    }

}
