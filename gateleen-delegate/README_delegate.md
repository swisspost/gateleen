# gateleen-delegate

With the delegate feature a client is able to delegate requests to perform prespecified tasks. Delegates can be used standalone, with routing rules, as well as with schedulers and hooks. 

You can specify a bunch of different delegates, by PUTing a so called delegate definition.

### Delegate definition
Putting or updating an delegate definition requires a validation against the delegate schema to be positive. Check the schema [gateleen_delegate_schema_delegates](src/main/resources/gateleen_delegate_schema_delegates)!

> ```PUT /<Server_Root>/<Delegate_Uri>/<Name_Of_Delegate>/definition```

```
{
    "methods": [ "...", ... ],
    "pattern": "...",
    "requests": [
        {
             "headers" : [],
             "uri": "...",
             "method": "...",
             "payload": {
                ...
             }
        }, 
        {
          ...
        }
    ]
}
```

| Field | Required | Description | 
|---|:-:|---|
| methods  |  x  | An array of HTTP methods on witch a delegate execution will be performed. If an incoming request does not match the defined methods, nothing will be executed. |
| pattern  |  x  | A regular expression which allows to match and group the request URI. This way a replacement can be performed for the payload and the uri of each specified delegate request. |
| requests |  x  | An array of delegate requests. |
| headers  |     | Allows to specify extra headers for one specific delegate request. |
| uri      |  x  | The URI against which one the delegate request will be performed. |
| method   |  x  | The HTTP method used to perform the given delegate request. |
| payload  |  x  | The json content of the payload required for processing the delegate request. The payload is dependent on the wished request and can differ from request to request. |

##### Concrete Example
> ```PUT /gateleen/server/delegate/v1/delegates/resource-zip-copy/definition```

```
{
    "methods": [ "PUT" ],
    "pattern": ".*/([^/]+.*)",
    "requests": [
        {
            "method": "POST",
            "uri": "/gateleen/server/v1/copy",
            "payload": {
                "source": "/gateleen/$1?expand=100&zip=true",
                "destination": "/gateleen/zips/users/$1.zip"
            }
        }
    ]
}
```

### Delegate execution
To execute a delegate it is necessary to perform a HTTP request (method is irrelevant and handled by the methods definition of the delegate) directly on the delegate execution "resource". If the incoming HTTP method matches the given delegate definition, the delegate requests will be performed. For the delegate it is irrelevant if a body is passed or not. It will be ignored, because it’s not needed for the delegates to be performed. 

> ```PUT /<Server_Root>/<Delegate_Uri>/<Name_Of_Delegate>/execution/<passed_resource_path>```

##### Concrete Example
> ```PUT /gateleen/server/delegate/v1/delegates/resource-zip-copy/execution/resource1```

Applied to our example definition this means, that the defined delegate pattern will put "resource1" as the first pattern group. So "$1" of the payload will be replaced with "resource1".
The executed request will be:
> ```POST /gateleen/server/v1/copy```

``` 
{
    "source": "/gateleen/resource1?expand=100&zip=true",
    "destination": "/gateleen/zips/users/resource1.zip"
}
```

### Log delegate definition changes
To log the payload of changes to the delegate definitions, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the delegate definitions, make sure the url to the delegate definitions is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/delegate/v1/delegates/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [DelegateHandler](src/main/java/org/swisspush/gateleen/delegate/DelegateHandler.java) by calling
```java
delegateHandler.enableResourceLogging(true);
```
