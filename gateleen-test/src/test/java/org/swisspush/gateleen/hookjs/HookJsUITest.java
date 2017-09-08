/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2014 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */

package org.swisspush.gateleen.hookjs;

import com.jayway.awaitility.Duration;
import org.junit.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.swisspush.gateleen.AbstractTest;

import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;

public class HookJsUITest {

    private static WebDriver webDriver;

    @BeforeClass
    public static void setupChromeDriver() {


        if (System.getProperty("sel_chrome_driver") == null) {
            System.setProperty("webdriver.chrome.driver", "src/test/resources/chromedriver");
        } else {
            System.setProperty("webdriver.chrome.driver", System.getProperty("sel_chrome_driver"));
        }

        webDriver = new ChromeDriver();
    }

    @Test
    public void testSingleHook() throws InterruptedException {

        webDriver.get("http://localhost:7012/playground/hooktest.html");

//        await().atMost(Duration.TWO_SECONDS).until(() ->
//                webDriver.findElement(By.xpath("/html/body/pre")).getText(),
//                equalTo("404 Not Found"));

        WebElement buttonPlaceSingleHook = webDriver.findElement(By.id("psh"));
        buttonPlaceSingleHook.click();
        await().atMost(Duration.TWO_SECONDS).until(() ->
                webDriver.findElement(By.xpath("//*[@id=\"hjsm\"]/li[1]")).getText(),
                equalTo("Installing listener 1"));

        WebElement buttonPutSingle = webDriver.findElement(By.id("ps"));
        buttonPutSingle.click();
        await().atMost(Duration.TWO_SECONDS).until(() ->
                webDriver.findElement(By.xpath("//*[@id=\"hjsm\"]/li[2]")).getText(),
                equalTo("Listener 1 received:<Message 1>"));
    }

    @AfterClass
    public static void afterClass(){
        webDriver.quit();
    }

}
