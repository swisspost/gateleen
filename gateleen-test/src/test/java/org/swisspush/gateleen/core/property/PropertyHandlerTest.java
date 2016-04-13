/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2016 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */
package org.swisspush.gateleen.core.property;

import static com.jayway.restassured.RestAssured.with;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import org.swisspush.gateleen.core.refresh.Refreshable;

/**
 * Test class for the PropertyHandler.
 * 
 * @author ljucam
 */
@RunWith(VertxUnitRunner.class)
public class PropertyHandlerTest extends AbstractTest {


    /**
     * Checks if the property is NOT updated, if
     * no registration is available.
     */
    @Test
    public void testChangeProperty_not_registred(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "myPropId", "notmyprop.id");

        // put something to testserver
        with().body(getBody("myPropId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertTrue(props.get(property).equals(propertyValue));

        context.async().complete();
    }

    /**
     * Checks if no update is performed, if the
     * url may be found, but the valueid is not
     * registred.
     */
    @Test
    public void testChangeProperty_wrong_valueId(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "myPropId", "myprop.id");

        // put something to testserver
        with().body(getBody("notMyId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertEquals(props.get(property), propertyValue);

        context.async().complete();
    }

    /**
     * Checks if a registred propery is correctly updated,
     * if a request is sent to the server.
     */
    @Test
    public void testChangeProperty_registred(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "myPropId", "myprop.id");

        // put something to testserver
        with().body(getBody("myPropId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertEquals(props.get(property), "test");

        context.async().complete();
    }

    /**
     * Checks if the refreshables are called or not.
     */
    @Test
    public void testChangeProperty_registred_refresh(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "myPropId", "myprop.id");

        // register a refreshable
        MyRefreshable freshi = new MyRefreshable();
        propertyHandler.addRefreshable(freshi);
        Assert.assertFalse(freshi.isRefreshed());

        // put something to testserver
        with().body(getBody("myPropId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertEquals(props.get(property), "test");

        // wait 3 sec
        TestUtils.waitSomeTime(3);

        // check refreshable
        Assert.assertTrue(freshi.isRefreshed());

        context.async().complete();
    }

    /**
     * Checks if the refreshables are called or not.
     */
    @Test
    public void testChangeProperty_not_registred_refresh(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "notMyPropId", "myprop.id");

        // register a refreshable
        MyRefreshable freshi = new MyRefreshable();
        propertyHandler.addRefreshable(freshi);
        Assert.assertFalse(freshi.isRefreshed());

        // put something to testserver
        with().body(getBody("myPropId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // wait 3 sec
        TestUtils.waitSomeTime(3);

        // check refreshable
        Assert.assertFalse(freshi.isRefreshed());

        context.async().complete();
    }

    /**
     * Checks if the refreshables are called or not.
     */
    @Test
    public void testChangeProperty_wrong_valueId_refresh(TestContext context) {
        String property = "myprop.id";
        String propertyValue = "ok";

        // put property to pros
        props.put(property, propertyValue);
        Assert.assertTrue(props.get(property).equals(propertyValue));

        // register something for the handler
        propertyHandler.addProperty("/myprop/v1/id", "myPropId", "myprop.id");

        // register a refreshable
        MyRefreshable freshi = new MyRefreshable();
        propertyHandler.addRefreshable(freshi);
        Assert.assertFalse(freshi.isRefreshed());

        // put something to testserver
        with().body(getBody("notMyId", "test")).put("/myprop/v1/id").then().assertThat().statusCode(200);

        // check property
        Assert.assertEquals(props.get(property), propertyValue);

        // wait 3 sec
        TestUtils.waitSomeTime(3);

        // check refreshable
        Assert.assertFalse(freshi.isRefreshed());

        context.async().complete();
    }

    /**
     * Returns a body for the PropertyHandler request.
     * 
     * @param valueId - name of the valueid
     * @param value - the value itself
     * @return - the body
     */
    private String getBody(String valueId, String value) {
        return "{ \"" + valueId + "\" : \"" + value + "\"}";
    }

    /**
     * Helper class
     * 
     * @author ljucam
     */
    private class MyRefreshable implements Refreshable {
        private boolean refreshed = false;

        @Override
        public void refresh() {
            refreshed = true;
        }

        public boolean isRefreshed() {
            return refreshed;
        }
    }
}
