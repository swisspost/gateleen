# gateleen-qos
Introduces a Quality of Service network traffic.
An example on how to integrate this feature in a communication server can be found in the class [org.swisspush.gateleen.AbstractTest](../gateleen-test/src/test/java/org/swisspush/gateleen/AbstractTest.java).

The QoS feature is controlled by a ruleset which you have to PUT according to this example.

**PUT gateleen/server/admin/v1/qos**
```json
{
  "config":{
    "percentile":75,
    "quorum":40,
    "period":5,
    "minSampleCount" : 1000,
    "minSentinelCount" : 5
  },
  "sentinels":{
    "sentinelA":{
      "percentile":50
    },
    "sentinelB":{},
    "sentinelC":{},
    "sentinelD":{}
  },
  "rules":{
    "/test/myapi1/v1/.*":{
      "reject":1.2,
      "warn":0.5
    },
    "/test/myapi2/v1/.*":{
      "reject":0.3
    }
  }
}
```

The **config** section defines the global settings of the QoS.

| Setting              | Description                              |
|:---------------------| ---------------------------------------- |
| **percentile**       | Indicates which percentile value from the metrics will be used (eg. 50, 75, 95, 98, 999 or 99) |
| **quorum**           | Percentage of the the sentinels which have to be over the calculated threshold to trigger the given rule. |
| **period**           | The period (in seconds) after which a new calculation is triggered. If a rule is set to reject requests, it will reject requests until the next period. |
| **minSampleCount**   | The min. count of the samples a sentinel has to provide to be regarded for the QoS calculation. |
| **minSentinelCount** | The min count of sentinels which have to be available to perform a QoS calculation. A sentinel is only available if it corresponds to the minSampleCount rule. |

The **sentinels** section defines which metrics (defined in the routing rules) will be used as sentinels. To determine the load, the lowest measured percentile value will be preserved for each sentinel and put in relation to the current percentile value.
This calculated ratio is later used to check if a rule needs some actions or not. You can override the taken percentile value for a specific sentinel by setting the attribute **percentile** as shown in the example above.
 
The **rules** section defines the rules for the QoS. Each rule is based on a pattern like the routing rules. 
The possible attributes are:
 
| Attribute  | Description                              |
|:-----------| ---------------------------------------- |
| **reject** | The ratio (eg. 1.3 means that *`<quorum>`* % of all sentinels must have an even or greater current ratio) which defines when a rule rejects the given request.  |
| **warn**   | The ratio which defines when a rule writes a warning in the log without rejecting the given request  |


> <font color="blue">Information: </font> You can combine warn and reject.

> <font color="orange">Attention: </font> Be aware that a metric is only available (in JMX) after a HTTP request (PUT/GET/...) was performed. Therefore it’s correct if the log shows something like **_MBean X for sentinel Y is not ready yet ..._** The QoS feature considers only available metrics (from the sentinels) for its calculation.
 
## Log Qos ruleset changes
To log the payload of changes to the Qos ruleset, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the Qos ruleset, make sure the url to the ruleset is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [QoSHandler](src/main/java/org/swisspush/gateleen/qos/QoSHandler.java) by calling
```java
qosHandler.enableResourceLogging(true);
```
