package org.swisspush.gateleen.core.resource;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(VertxUnitRunner.class)
public class CopyResourceHandlerTest {
    private HttpServerRequest httpServerRequestMock;
    private CopyResourceHandler copyResourceHandler;

    @Before
    public void init() {
        httpServerRequestMock = Mockito.mock(HttpServerRequest.class);
        copyResourceHandler = new CopyResourceHandler(Mockito.mock(HttpClient.class), null);

        MultiMap headers = new CaseInsensitiveHeaders().add("x-expire-after", "700");
        Mockito.doReturn(headers).when(httpServerRequestMock).headers();
    }

    @Test
    public void testBackwardCompatibility() {
        String json = " { 'staticHeaders': {" +
                " 'x-expire-after': 900," +
                " 'Content-Type': 'application/json'" +
                "} }";
        json = json.replace('\'', '"');

        Buffer jsonBuffer = Buffer.buffer(json);
        CopyTask copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertEquals("900", copyTask.getHeaders().get("x-expire-after"));
        Assert.assertEquals("application/json", copyTask.getHeaders().get("Content-Type"));
    }

    @Test
    public void testDynamicHeaderWithNoFunction() {
        String json = "{ 'headers' : [" +
                "   {'header': 'x-expire-after', 'value': '800'}," +
                "   {'header': 'Content-Type', 'value': 'application/json;charset=UTF-8'}" +
                "] }";
        json = json.replace('\'', '"');

        Buffer jsonBuffer = Buffer.buffer(json);
        CopyTask copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertEquals("800", copyTask.getHeaders().get("x-expire-after"));
        Assert.assertEquals("application/json;charset=UTF-8", copyTask.getHeaders().get("Content-Type"));
    }

    @Test
    public void testDynamicHeaderWithFunction() {
        String json = "{ 'headers' : [" +
                "   { 'header': 'x-bar', 'value': 'I am bar'}," +
                "   { 'header': 'x-foo', 'value': 'bar-{x-bar}'}" +
                "] }";
        json = json.replace('\'', '"');

        Buffer jsonBuffer = Buffer.buffer(json);
        CopyTask copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertEquals("I am bar", copyTask.getHeaders().get("x-bar"));
        Assert.assertEquals("bar-I am bar", copyTask.getHeaders().get("x-foo"));
    }

    @Test
    public void testApplyHeadersFailed() {
        String json = "{ 'headers' : [" +
                "   { 'header': 'x-foo', 'value': 'bar-{x-bar}'}" +
                "] }";
        json = json.replace('\'', '"');

        Buffer jsonBuffer = Buffer.buffer(json);
        CopyTask copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertNull(copyTask);
    }
}