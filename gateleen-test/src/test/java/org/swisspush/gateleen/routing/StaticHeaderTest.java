package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static com.jayway.restassured.RestAssured.*;

@RunWith(VertxUnitRunner.class)
public class StaticHeaderTest extends AbstractTest {

	/**
	 * Overwrite RestAssured configuration
	 */
	public void initRestAssured() {
		super.initRestAssured();
		RestAssured.requestSpecification.basePath(SERVER_ROOT + "/pages");
	}

	public void init(){
		delete();

		// add a routing
		JsonObject rules = new JsonObject();
		rules = TestUtils.addRoutingRuleMainStorage(rules);
		rules = createStaticHeaderRules(rules);
		TestUtils.putRoutingRules(rules);

	}

	public JsonObject createStaticHeaderRules(JsonObject rules){
		JsonObject header = new JsonObject();
		header.put("x-queue", "position");
		header.put("x-expire-after", "30");
		header.put("x-flow-control-window", "5");
		header.put("notpresentheaderelement", "");

		JsonObject translate = new JsonObject();
		translate.put("202",200);

		JsonObject test1Rule =  TestUtils.createRoutingRule(ImmutableMap.of(
				"description",
				"Adding static headers with null values",
				"translateStatus",
				translate,
				"staticHeaders",
				header,
				"url",
				"http://localhost:" + MAIN_PORT + ROOT + "/debug"
		));
		String ruleName = SERVER_ROOT + "/pages/test/test1";
		rules = TestUtils.addRoutingRule(rules, ruleName, test1Rule);

		header.put("presentheaderelement", "notnull");
		JsonObject test2Rule =  TestUtils.createRoutingRule(ImmutableMap.of(
				"description",
				"Adding static headers without null values",
				"translateStatus",
				translate,
				"staticHeaders",
				header,
				"url",
				"http://localhost:" + MAIN_PORT + ROOT + "/debug"
		));
		ruleName = SERVER_ROOT + "/pages/test/test2";
		rules = TestUtils.addRoutingRule(rules, ruleName, test2Rule);

		return rules;
	}

	/**
	 * Test if static header which contains null values are removed from request
	 */
	@Test
	public void testStaticHeaderWithNullValue(TestContext context){
		Async async = context.async();
		delete();
		init();
		String body = with().header("notpresentheaderelement", "value").get("/test/test1").getBody().asString();
		System.out.println(body);
		Assert.assertFalse(body.contains("notpresentheaderelement"));
		async.complete();
	}

	/**
	 * Test if static header are set if it does not contain null values
	 */
	@Test
	public void testStaticHeaderWithNoNullValue(TestContext context){
		Async async = context.async();
		delete();
		init();
		String body = with().get("/test/test2").getBody().asString();
		System.out.println(body);
		Assert.assertTrue(body.contains("presentheaderelement"));
		async.complete();
	}

	/**
	 * Test if static header are set if it does not contain null values
	 */
	@Test
	public void testStaticHeaderOverwrite(TestContext context){
		Async async = context.async();
		delete();
		init();
		String body = with().header("presentheaderelement", "value").get("/test/test2").getBody().asString();
		System.out.println(body);
		Assert.assertTrue(body.contains("presentheaderelement: notnull"));
		async.complete();
	}
}
