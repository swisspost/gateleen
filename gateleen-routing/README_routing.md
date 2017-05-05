# gateleen-routing

## Schema validation
Updating the routing rules requires a validation against a schema to be positive. Check the schema [gateleen_routing_schema_routing_rules](src/main/resources/gateleen_routing_schema_routing_rules)

## Request hop validation
To protect gateleen from routing rules configured to result in an endless loop, the request hop validation feature has been introduced.

### Description
When the request hop validation is activated, a **_x-hops_** request header will be added to the request before routing. Initially, this header will receive the value 1.
When the **_x-hops_** header already exists (in alredy routed requests for example), the value will be increased by 1.

Before the request is routed, the **_x-hops_** header value will be checked against a configurable limit. When the limit is exceeded, the request will
be answered with a http status code _500_ and a http status message: _Request hops limit exceeded_

### Usage
To use the request hop validation feature, the _ConfigurationResourceManager_ and the path to the configuration resource have to be configured in the _Router_ by calling:

```java
router.enableRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config")
```
The routing configuration is a json resource defining the limit for the maximum request routings (hops) called **_request.hops.limit_**.

Example:

```json
{
  "request.hops.limit": 10
}
```

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
