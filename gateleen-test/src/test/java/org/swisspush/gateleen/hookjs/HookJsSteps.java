/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2014 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */

package org.swisspush.gateleen.hookjs;

import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.http.ContentType;
import cucumber.api.java.After;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import io.vertx.core.json.JsonObject;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

import static com.jayway.awaitility.Awaitility.given;
import static org.hamcrest.core.IsEqual.equalTo;

public class HookJsSteps {

    private static WebDriver webDriver;
    
    private static final String PLAYGROUND_URL = "http://localhost:7012/playground";

    @After
    public static void quitBrowser(){
        webDriver.quit();
    }

    @Given("^Chrome has been started$")
    public void chromeHasBeenStarted() throws Throwable {
        System.setProperty("webdriver.chrome.driver", System.getProperty("sel_chrome_driver"));
        webDriver = new ChromeDriver();
    }

    @And("^the hook-js UI is displayed$")
    public void theHookJsUIIsDisplayed() throws Throwable {
        webDriver.get(PLAYGROUND_URL + "/hooktest.html");
        given().await().atMost(Duration.TWO_SECONDS).until(() ->
                        webDriver.findElement(By.xpath("/html/body/div/div/div/div[1]")).getText(),
                equalTo("Hook JS Demo")
        );
    }

    @When("^we click on the button \"([^\"]*)\"$")
    public void weClickOnTheButton(String buttonId) throws Throwable {
        WebElement webButton = webDriver.findElement(By.id(buttonId));
        webButton.click();
    }

    @Then("^we see the message \"([^\"]*)\" at position (\\d+)$")
    public void weSeeTheMessageAtPosition(String message, int indexOfMessage) throws Throwable {
        given().await().atMost(Duration.TWO_SECONDS).until(() ->
                        webDriver.findElement(By.xpath("//*[@id=\"hookjs messages\"]/li[" + indexOfMessage + "]")).getText(),
                equalTo(message));

    }

    @Then("^we see no message at position (\\d+)$")
    public void weSeeNoMessageAtPosition(int indexOfMessage) throws Throwable {
        given().await().atMost(Duration.TWO_SECONDS).until(() ->
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
