/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2014 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */

package org.swisspush.gateleen.hookjs;

import com.jayway.awaitility.Duration;
import cucumber.api.java.After;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

import static com.jayway.awaitility.Awaitility.given;
import static org.hamcrest.core.IsEqual.equalTo;

public class HookJsSteps {

    private static WebDriver webDriver;

    @Given("^Chrome has been started$")
    public void chromeHasBeenStarted() throws Throwable {
        System.setProperty("sel_chrome_driver", "/home/jonas/work/jrepo-local/tools/chromedrivers/chromedriver2-32");
        System.setProperty("webdriver.chrome.driver", System.getProperty("sel_chrome_driver"));
        webDriver = new ChromeDriver();
    }

    @And("^the hook-js UI is displayed$")
    public void theHookJsUIIsDisplayed() throws Throwable {
        webDriver.get("http://localhost:7012/playground/hooktest.html");
//        TODO: Check that page really is displayed.
    }

    @When("^we click on the button \"([^\"]*)\"$")
    public void weClickOnTheButton(String button) throws Throwable {

        String buttonId;

        switch (button) {
            case "Place Single Hook":
                buttonId = "psh";
                break;

            case "PUT Single":
                buttonId = "ps";
                break;

            default:
                buttonId = button;
                break;
        }

        WebElement webButton = webDriver.findElement(By.id(buttonId));
        webButton.click();
    }

    @Then("^we see the message \"([^\"]*)\" on position (\\d+)$")
    public void weSeeTheMessageOnPosition(String message, int indexOfMessage) throws Throwable {
        given().await().atMost(Duration.TWO_SECONDS).until(() ->
                        webDriver.findElement(By.xpath("//*[@id=\"hjsm\"]/li[" + indexOfMessage + "]")).getText(),
                equalTo(message));
    }

    @After
    public static void quitBrowser(){
        webDriver.quit();
    }

}
