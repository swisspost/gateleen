# gateleen-routing

## Schema validation
Updating the routing rules requires a validation against a schema to be positive. Check the schema [gateleen_routing_schema_routing_rules](src/main/resources/gateleen_routing_schema_routing_rules)

## Log routing rule changes
To log the payload of changes to the routing rules, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the routing rules, make sure the url to the routing rules is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/routing/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [Router](src/main/java/org/swisspush/gateleen/routing/Router.java) by calling
```java
router.enableResourceLogging(true);
```
