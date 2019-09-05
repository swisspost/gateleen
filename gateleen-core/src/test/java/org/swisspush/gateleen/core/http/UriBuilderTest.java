package org.swisspush.gateleen.core.http;

import org.junit.Assert;
import org.junit.Test;

public class UriBuilderTest {

    @Test
    public void uriWithTrailingSlash() {
        Assert.assertEquals("segment1/segment2/segment3", UriBuilder.concatUriSegments("segment1/", "segment2", "segment3"));
    }

    @Test
    public void uriWithTrailingAndLeadingSlash() {
        Assert.assertEquals("segment1/segment2", UriBuilder.concatUriSegments("segment1/", "/segment2"));
    }

    @Test
    public void uriWithLeadingSlash() {
        Assert.assertEquals("segment1/segment2", UriBuilder.concatUriSegments("segment1", "/segment2"));
    }

    @Test
    public void uriWithNoSlash() {
        Assert.assertEquals("segment1/segment2/segment3", UriBuilder.concatUriSegments("segment1", "segment2", "segment3"));
    }

    @Test
    public void cleanDoubleSlash() {
        Assert.assertEquals("segment1/segment2", UriBuilder.cleanUri("segment1/segment2"));
        Assert.assertEquals("segment1/segment2", UriBuilder.cleanUri("segment1//segment2"));
        Assert.assertEquals("http://segment1/segment2", UriBuilder.cleanUri("http://segment1//segment2"));
        Assert.assertEquals("https://segment1/segment2", UriBuilder.cleanUri("https://segment1//segment2"));
        Assert.assertEquals("https://segment1/segment2/", UriBuilder.cleanUri("https:///segment1//segment2/"));
    }


}
