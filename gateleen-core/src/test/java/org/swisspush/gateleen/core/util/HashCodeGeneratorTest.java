package org.swisspush.gateleen.core.util;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class HashCodeGeneratorTest {

	@Test
	public void testHashing(TestContext context){
		String inputString = "/playground/img/(.*)";
		String sameInputString = "/playground/img/(.*)";

		String hash = HashCodeGenerator.createHashCode(inputString);
		String sameHash = HashCodeGenerator.createHashCode(sameInputString);

		context.assertEquals(hash, sameHash, "hash values should be identical");
	}

	@Test
	public void testHashingWithEqualData(TestContext context){
		String uri = "/gateleen/server/tests/t1";
		String payload = "{\"property1\": \"value1\"}";
		
		String sameUri = "/gateleen/server/tests/t1";
		String samePayload = "{\"property1\": \"value1\"}";
		
		String hash = HashCodeGenerator.createHashCode(uri, payload);
		String sameHash = HashCodeGenerator.createHashCode(sameUri, samePayload);
		
		context.assertEquals(hash, sameHash, "hash values should be identical");
	}
	
	@Test
	public void testHashingWithNonEqualData(TestContext context){
		String uri = "/gateleen/server/tests/t1";
		String payload = "{\"property1\": \"value1\"}";
		
		String differentUri = "/gateleen/server/tests/t1";
		String differentPayload = "{\"property1\": \"value2\"}";
		
		String hash = HashCodeGenerator.createHashCode(uri, payload);
		String differentHash = HashCodeGenerator.createHashCode(differentUri, differentPayload);
		
		context.assertNotEquals(hash, differentHash, "hash values should not be identical");
	}
	
	@Test
	public void testHashingWithEqualDataAndWhitespaces(TestContext context){
		String uri = "/gateleen/server/tests/t1";
		String payload = "{\"property1\": \"value1\"}";
		
		String sameUri = "/gateleen/server/tests/t1";
		String samePayloadWithWhitespaces = "{\"property1\": \"value1\"} ";
		
		String hash = HashCodeGenerator.createHashCode(uri, payload);
		String sameHash = HashCodeGenerator.createHashCode(sameUri, samePayloadWithWhitespaces);
		
		context.assertEquals(hash, sameHash, "hash values should be identical");
	}
	
	@Test
	public void testHashingWithNullValues(TestContext context){
		String hash = HashCodeGenerator.createHashCode(null, null);
		String sameHash = HashCodeGenerator.createHashCode(null, null);
		context.assertEquals(hash, sameHash, "hash values should be identical");
	}	
}
