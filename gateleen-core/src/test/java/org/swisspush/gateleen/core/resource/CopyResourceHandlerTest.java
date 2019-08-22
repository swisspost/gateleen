package org.swisspush.gateleen.core.resource;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(VertxUnitRunner.class)
public class CopyResourceHandlerTest {

    @Test
    public void handle() {
        CopyResourceHandler copyResourceHandler = new CopyResourceHandler(Mockito.mock(HttpClient.class), "");

        MultiMap headers = new CaseInsensitiveHeaders();
        HttpServerRequest httpServerRequestMock = Mockito.mock(HttpServerRequest.class);
        Mockito.doReturn(headers).when(httpServerRequestMock).headers();

        String json = " { 'staticHeaders': {" +
                " 'x-expire-after': 900," +
                " 'Content-Type': 'application/json'" +
                "} }";
        json = json.replace('\'', '"');

        headers.add("x-expire-after", "700");
        Buffer jsonBuffer = Buffer.buffer(json);
        CopyTask copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertEquals("900", copyTask.getHeaders().get("x-expire-after"));
        Assert.assertEquals("application/json", copyTask.getHeaders().get("Content-Type"));

        json = "{ 'headers' : [" +
                "   {'header': 'x-expire-after', 'value': '800'}," +
                "   {'header': 'Content-Type', 'value': 'application/json;charset=UTF-8'}" +
                "] }";
        json = json.replace('\'', '"');

        jsonBuffer = Buffer.buffer(json);
        copyTask = copyResourceHandler.createCopyTask(httpServerRequestMock, jsonBuffer);

        Assert.assertEquals("800", copyTask.getHeaders().get("x-expire-after"));
        Assert.assertEquals("application/json;charset=UTF-8", copyTask.getHeaders().get("Content-Type"));
    }
}