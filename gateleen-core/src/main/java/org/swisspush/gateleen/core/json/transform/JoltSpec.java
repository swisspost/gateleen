package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.Chainr;

/**
 * Container holding the spec configuration to transform json. {@link JoltSpec} objects can be reused to make
 * multiple transformations with the same spec.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class JoltSpec {
    private final Chainr chainr;

    private final boolean withMetadata;

    JoltSpec(Chainr chainr) {
        this(chainr, false);
    }

    JoltSpec(Chainr chainr, boolean withMetadata) {
        this.chainr = chainr;
        this.withMetadata = withMetadata;
    }

    Chainr getChainr() {
        return chainr;
    }

    public boolean isWithMetadata() {
        return withMetadata;
    }
}
