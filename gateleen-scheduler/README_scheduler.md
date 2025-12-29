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

## Daylight time change observation
In order to detect DST changes properly, the observation could be enabled with the following property. 
This will reschedule all schedulers when a DST change is detected and therefore realign the next execution time correctly.

```properties
dst.observe=true
```

Reason: The scheduler calculates the next execution time based on the last execution time and stores it persistently. If the 
last execution time is before the DST change and the next execution time is after the DST change, the scheduler might hop 
over the DST change hour and therefore miss scheduled executions.
