# gateleen-core

## Storage
### Resource expiration
Using the _X-Expire-After_ request header, the expiration of a resource can be defined.

#### Creating resources
To define the expiration of a resource, add the _X-Expire-After_ request header to the PUT request.
> X-Expire-After: \<seconds\>

| header value | Description |
|:------------:| :-----------|
| -1 | The resource never expires |
| 0 | The resource expires immediately |
| \> 0 | The resource will expire in the configured amount of seconds |

> <font color="blue">Information: </font> When no _X-Expire-After_ request header is provided, the resource will **never** expire

#### Consuming resources
To consume (GET) resources, the client doesn't have to provide any parameters.
When a resource is expired, it will no longer be returned and a **404 Not Found** error will be returned instead.

## CORS Handling
To enable the headers for Cross-Origin Resource Sharing (CORS) requests, you have to set the following property:
> org.swisspush.gateleen.addcorsheaders=true

See [CORSHandler](src/main/java/org/swisspush/gateleen/core/cors/CORSHandler.java) for more information

## Logging
Besides the logging functionality provided by the [logging](gateleen-logging/README_logging.md) module, the core module
provides a logging utility called [RequestLogger](src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java).
 
The _RequestLogger_ can be used to log the payload of requests which are handled by special handlers and therefore
will not be forwarded by the forwarder classes where the logging happens.

To use the _RequestLogger_ simply call
```java
RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
```

The _RequestLogger_ uses the event bus to send the log entries to the logging module. Therefore, you have to instantiate the [RequestLoggingConsumer](gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling

```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```