/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2026 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */

package org.swisspush.gateleen.hookts;

import cucumber.api.CucumberOptions;
import cucumber.api.junit.Cucumber;
import org.junit.runner.RunWith;
@RunWith(Cucumber.class)
@CucumberOptions(
        format = "pretty",
        features = "src/test/resources/features/hook-ts.feature"
)
public class HookTsTest {
}
