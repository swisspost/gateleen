package org.swisspush.gateleen.security.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A repository holding {@link ContentTypeConstraint} instances. These instances are searchable
 * by {@link #findMatchingContentTypeConstraint(String)}.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ContentTypeConstraintRepository {

    private final Logger log = LoggerFactory.getLogger(ContentTypeConstraintRepository.class);
    private final List<ContentTypeConstraint> constraints = new ArrayList<>();

    /**
     * Clear all existing constraints. This should be used when the constraint configuration resource
     * was removed.
     */
    void clearConstraints(){
        log.info("About to clear Content-Type constraints");
        this.constraints.clear();
    }

    /**
     * Replaces existing constraints with the provided constraints. This should be called when the configuration
     * resource changed.
     *
     * @param constraints the constraints replacing existing constraints
     */
    void setConstraints(List<ContentTypeConstraint> constraints){
        clearConstraints();
        if(constraints != null){
            log.info("About to set {} Content-Type constraints", constraints.size());
            this.constraints.addAll(constraints);
        }
    }

    /**
     * Searches for a {@link ContentTypeConstraint} having an urlPattern matching the provided request uri.
     *
     * @param requestUri The uri to search the matching {@link ContentTypeConstraint}
     * @return Returns a matching {@link ContentTypeConstraint} or {@link Optional#empty()}
     */
    Optional<ContentTypeConstraint> findMatchingContentTypeConstraint(String requestUri){
        for (ContentTypeConstraint constraint : constraints) {
            if(constraint.urlPattern().getPattern().matcher(requestUri).matches()){
                return Optional.of(constraint);
            }
        }
        return Optional.empty();
    }
}
