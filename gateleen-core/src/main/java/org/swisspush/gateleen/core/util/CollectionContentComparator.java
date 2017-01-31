package org.swisspush.gateleen.core.util;

import java.util.Comparator;

/**
 * This comparator allows you to sort a list to be
 * compliant with the natural ordering of content
 * returned by the storage. This may help you to
 * create merged / manual created arrays for collections
 * respecting the order as the storage does.
 *
 * @author https://github.com/ljucam [Mario Aerni]
 */
public class CollectionContentComparator implements Comparator<String> {
    private static final String SLASH = "/";
    public int compare(String c1, String c2) {

        if ( c1.endsWith(SLASH) && c2.endsWith(SLASH) ) {
            return c1.compareTo(c2);
        }

        if ( c1.endsWith(SLASH) && ! c2.endsWith(SLASH)) {
            return -1;
        }
        else if ( ! c1.endsWith(SLASH) && c2.endsWith(SLASH) ) {
            return 1;
        }

        return c1.compareTo(c2);
    }
}