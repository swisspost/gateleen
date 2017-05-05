# gateleen-validation
This feature allows to validate the requests to json resources.

#### Configuration
Use the ValidationResourceManager class to configure the validation configuration resource. The validation configuration resource is a json resource with the content listed below. The "resources" property is required containing an array of objects with the following properties:

| Property    | Description                              | 
|:----------- | :--------------------------------------- | 
| url         | A string containing the URL to validate. Regex can be used to define a pattern |
| method      | A string containing the HTTP method to validate. Regex can be used to define a pattern. This property is optional. If not provided, "PUT" will be used as default |

All filter values inside a config property (url, method) have to match in order to validate the corresponding json resource.

Example of a validation configuration:

```json
{
    "resources": [
        {
            "url": "/gateleen/server/admin/v1/logging",
            "method": "GET|PUT"
        },
        {
            "url": "/gateleen/server/admin/v1/schedulers",
            "method": "GET|PUT"
        }       
    ]
}
```

> <font color="orange">Attention: </font> To validate a json resource, a schema for the resource must exist!

#### Schema validation
Updating the validation configuration resource requires a validation against a schema to be positive. Check the schema [gateleen_validation_schema_validation](src/main/resources/gateleen_validation_schema_validation)

#### Request handling
When a json resource is configured to be validated, requests are handled by the following rules:

1. If no schema is available for the resource, reject the request
2. If a schema is available but does not match the request, reject the request
3. If the resource is not a proper json resource, reject the request

Rejected requests will be answered with status code **400 Bad Request**

#### Log validation configuration changes
To log the payload of changes to the validation configuration, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the validation configuration, make sure the url to the validation configuration is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/validation",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [ValidationResourceManager](src/main/java/org/swisspush/gateleen/validation/ValidationResourceManager.java) by calling
```java
validationResourceManager.enableResourceLogging(true);
```