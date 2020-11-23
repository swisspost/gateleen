package org.swisspush.gateleen.security.content;

import org.swisspush.gateleen.security.PatternHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class containing common test resources, objects and methods
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public abstract class ContentTypeConstraintTestBase {

    protected ContentTypeConstraint createConstraint(String urlPattern, List<String> allowedTypes){
        List<PatternHolder> allowedTypesList = new ArrayList<>();
        for (String allowedType : allowedTypes) {
            allowedTypesList.add(new PatternHolder(allowedType));
        }
        return new ContentTypeConstraint(new PatternHolder(urlPattern), allowedTypesList);
    }
}
