# gateleen-scheduler

## Log scheduler configuration changes
To log the payload of changes to the schedulers, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the schedulers, make sure the url to the schedulers is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/schedulers",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [SchedulerResourceManager](src/main/java/org/swisspush/gateleen/scheduler/SchedulerResourceManager.java) by calling
```java
schedulerResourceManager.enableResourceLogging(true);
```