package org.swisspush.gateleen.core.util;

import io.vertx.core.json.JsonObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Base64;

public class Base64UnitTest {
    @Test
    public void testBase64Decoder() {
        String srcString = ">>>???>>>???>>>???>>>???";
        Base64.Encoder legacyEncoder = Base64.getEncoder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        System.setProperty("vertx.json.base64", "none");
        String legacyEncoderString = legacyEncoder.encodeToString(srcString.getBytes());
        String encoderString = encoder.encodeToString(srcString.getBytes());
        Assert.assertNotEquals(legacyEncoderString, encoderString);
        Assert.assertEquals(new String(Base64Unit.decodeBase64Safe(legacyEncoderString)), new String(Base64Unit.decodeBase64Safe(encoderString)));

        System.setProperty("vertx.json.base64", "legacy");
        legacyEncoderString = legacyEncoder.encodeToString(srcString.getBytes());
        encoderString = encoder.encodeToString(srcString.getBytes());
        Assert.assertNotEquals(legacyEncoderString, encoderString);
        Assert.assertEquals(new String(Base64Unit.decodeBase64Safe(legacyEncoderString)), new String(Base64Unit.decodeBase64Safe(encoderString)));
    }

    @Test
    public void testBase64DecoderFromJson() {
        String srcString = ">>>???>>>???>>>???>>>???";
        Base64.Encoder legacyEncoder = Base64.getEncoder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        String legacyEncoderString = legacyEncoder.encodeToString(srcString.getBytes());
        String encoderString = encoder.encodeToString(srcString.getBytes());

        JsonObject jsonObject = new JsonObject();
        jsonObject.put("payloadLegacy", legacyEncoderString);
        jsonObject.put("payload", encoderString);

        Assert.assertNotEquals(legacyEncoderString, encoderString);
        System.setProperty("vertx.json.base64", "none");
        Assert.assertEquals(new String(Base64Unit.decodeBase64Safe(legacyEncoderString)), new String(Base64Unit.decodeBase64Safe(encoderString)));
        String decodeString = new String(jsonObject.getBinary("payload"));
        Assert.assertEquals(srcString, decodeString);

        System.setProperty("vertx.json.base64", "legacy");
        Assert.assertEquals(new String(Base64Unit.decodeBase64Safe(legacyEncoderString)), new String(Base64Unit.decodeBase64Safe(encoderString)));
        decodeString = new String(jsonObject.getBinary("payload"));
        Assert.assertEquals(srcString, decodeString);
        // this line should fail
        //new String(jsonObject.getBinary("payloadLegacy"));
    }
}
