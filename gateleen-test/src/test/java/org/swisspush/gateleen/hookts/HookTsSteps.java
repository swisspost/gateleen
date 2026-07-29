/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2026 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */

package org.swisspush.gateleen.hookts;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import cucumber.api.java.After;
import cucumber.api.java.Before;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.util.logging.Level;

import static org.awaitility.Awaitility.given;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 * Steps for testing gateleen-hook-ts, the framework-agnostic TypeScript replacement
 * for gateleen-hook-js. Mirrors {@link org.swisspush.gateleen.hookjs.HookJsSteps} but
 * exercises the hooktest-ts.html demo page which is built on top of gateleen-hook-ts
 * instead of the Angular based gateleen-hook-js.
 */
public class HookTsSteps {

    private static WebDriver webDriver;

    private static final String PLAYGROUND_URL = "http://localhost:7012/playground";

    // hooktest-ts.html uses its own storage root (separate from hookjs's
    // /tests/hooktest/) so the two demos don't share/contaminate the same
    // Redis-backed resources. Both the "single" resource and the "collection"
    // are cleared before and after every scenario, so leftover state from a
    // previous run (or from the "Place Single Hook with Filter"-style demo data)
    // never leaks into subsequent assertions.
    private static final String HOOK_TEST_BASE = PLAYGROUND_URL + "/server/tests/hooktest-ts";

    @Before
    public static void clearDataBefore() {
        clearTestData();
    }

    @After
    public static void clearDataAfter() {
        clearTestData();
    }

    private static void clearTestData() {
        RestAssured.given().delete(HOOK_TEST_BASE + "/hook-demo");
        RestAssured.given().delete(HOOK_TEST_BASE + "/messages");
    }

    @After
    public static void quitBrowser(){
        dumpBrowserConsole();
        webDriver.quit();
    }

    private static void dumpBrowserConsole() {
        try {
            for (org.openqa.selenium.logging.LogEntry entry : webDriver.manage().logs().get(LogType.BROWSER)) {
                System.out.println("[BROWSER CONSOLE] " + entry.getMessage());
            }
        } catch (Exception e) {
            System.out.println("[BROWSER CONSOLE] unable to read logs: " + e);
        }
    }

    @Given("^Chrome has been started$")
    public void chromeHasBeenStarted() throws Throwable {
        System.setProperty("webdriver.chrome.driver", System.getProperty("sel_chrome_driver"));
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.BROWSER, Level.ALL);
        DesiredCapabilities capabilities = DesiredCapabilities.chrome();
        capabilities.setCapability(CapabilityType.LOGGING_PREFS, logPrefs);
        webDriver = new ChromeDriver(capabilities);
    }

    @And("^the hook-ts UI is displayed$")
    public void theHookTsUIIsDisplayed() throws Throwable {
        webDriver.get(PLAYGROUND_URL + "/hooktest-ts.html");
        given().ignoreExceptions().await().atMost(FIVE_SECONDS).until(() ->
                        webDriver.findElement(By.xpath("/html/body/div/div/div/div[1]")).getText(),
                equalTo("Hook TS Demo")
        );
    }

    @When("^we click on the button \"([^\"]*)\"$")
    public void weClickOnTheButton(String buttonId) throws Throwable {
        WebElement webButton = webDriver.findElement(By.id(buttonId));
        webButton.click();
    }

    @Then("^we see the message \"([^\"]*)\" at position (\\d+)$")
    public void weSeeTheMessageAtPosition(String message, int indexOfMessage) throws Throwable {
        given().ignoreExceptions().await().atMost(FIVE_SECONDS).until(() ->
                        webDriver.findElement(By.xpath("//*[@id=\"hookjs messages\"]/li[" + indexOfMessage + "]")).getText(),
                equalTo(message));

    }

    @Then("^we see no message at position (\\d+)$")
    public void weSeeNoMessageAtPosition(int indexOfMessage) throws Throwable {
        given().await().atMost(FIVE_SECONDS).until(() ->
                webDriver.findElements(By.xpath("//*[@id=\"hookjs messages\"]/li[" + indexOfMessage + "]")).size(),
                equalTo(0));
    }

    @When("^we put \"(.+)\" to \"(.+)\"$")
    public void wePutTextToPath(String text, String path) {
        JsonObject message = new JsonObject();
        message.put("text", text);
        RestAssured.given().contentType(ContentType.JSON)
                .body(message.getMap())
                .put(PLAYGROUND_URL + "/server/tests/hooktest" + path);
    }
}
