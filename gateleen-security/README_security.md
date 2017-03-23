# gateleen-security

#### Schema validation
Updating the acl (Access Control List) resources requires a validation against a schema to be positive. Check the schema [gateleen_security_schema_acl](src/main/resources/gateleen_security_schema_acl)

#### Log acl configuration changes
To log the payload of changes to the acl configurations, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the acl configurations, make sure the url to the acl configuration is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/security/v1/acls/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [Authorizer](src/main/java/org/swisspush/gateleen/security/authorization/Authorizer.java) by calling
```java
authorizer.enableResourceLogging(true);
```