# gateleen-queue
The gateleen-queue module provides queuing functionality and acts as a bridge to [vertx-redisques](https://github.com/swisspush/vertx-redisques).

## Queue retry configuration
Normally, failed queue items remain in the queue until successfully processed or manually deleted. With the `x-queue-retry-xxx` request header, you are able to control this behaviour.

Example values are:
```
"x-queue-retry-400": "0" (when response is 400, do not retry anymore)
"x-queue-retry-4xx": "0" (when response is 400, 404, 405, etc. do not retry anymore)
```
For now, only a retry count of `0` is supported. So the main use case for this feature are requests which should be tried once and then be discarded when not successful.

## Queue Circuit Breaker
The Queue Circuit Breaker hereinafter referred to as **QCB** can be used to protect your server from having to deal with lots of queued requests when a backend is not reachable.

The QCB is coupled to the routing rules. Every routing rule can be seen as a circuit. The QCB can gather statistics for these circuits with each queued request made. When the configured fail ratio threshold is reached, the circuit is switched to status **open**.

Queued requests against open circuits will no be executed but instantly rejected. This rejecting of queued requests reduces the load the server has to deal with.

### Circuit states

Every circuit has one of the following circuit states:

| State | Description |
|:-----:|-------------|
|  closed | In the closed state, the circuit is considered to be healthy and normaly working. The queued requests of closed circuits are always executed |
|  open | In the open state, the circuit is not working correctly. Not correctly working circuits can occur because of faulty backends or incorrect requests. Queued requests of open circuits will not be executed (fail-fast) unless the circuit is fixed (in state closed or at least half_open) again |
|  half_open | Open circuits will be periodically switched to state _half_open_, where a single queue is unlocked to check the circuit. The queued requests of half_open circuits are always executed  |

The state diagram below describes the possible transitions between these states:

```
                 +--------+
        +------->+        | fail ratio reached
success |        | CLOSED +---------------------+
        +--------+        |   open circuit      |
                 +---+----+                     |
                     ^                          |
                     |                          v
                     |                       +--+---+
                     |             +-------->+      |      try sample request
                     |        fast |         | OPEN +-----------------------+
                     |     failing +---------+      |                       |
                     |                       +---+--+                       |
                     |                           ^                          |
                     |                           |                          v
                     |                           |                    +-----+-----+
                     |                           |       fail         |           |
                     |                           +--------------------+ HALF-OPEN |
                     |                               re-open circuit  |           |
                     |                                                +-----+-----+
                     |                                                      |
                     |                       success                        |
                     +------------------------------------------------------+
                                           close circuit

```

### Features
The following sections give an in-depth explanation how the QCB works.

#### Check circuit status
The class [QueueProcessor](src/main/java/org/swisspush/gateleen/queue/queuing/QueueProcessor.java) is a consumer of vertx-redisques's processor messages. Whenever the QueueProcessor receives a message from vertx-redisques, the QCB (when active) is asked about the status of the corresponding circuit. When QCB is not active, the queued request is executed immediately.

The following sequence diagram shows this process

```
+-----------+                     +-----------------+                                          +---------------------+
| Redisques |                     | QueueProcessor  |                                          | QueueCircuitBreaker |
+-----------+                     +-----------------+                                          +---------------------+
      |                                    |                                                              |
      | notifyConsumer(queuedReq)          |                                                              |
      |----------------------------------->|                                                              |
      |                                    |                                                              |
      |                                    | isCircuitCheckEnabled()                                      |
      |                                    |------------------------------------------------------------->|
      |                                    |                                                              |
      |                                    |                                                              |
      |                                    |<-------------------------------------------------------------|
      |                                    | -------------------------------------\                       |
      |                                    |-| When not enabled execute queuedReq |                       |
      |                                    | |------------------------------------|                       |
      |                                    |                                                              |
      |                                    | handleQueuedRequest(queue, queuedReq)                        |
      |                                    |------------------------------------------------------------->|
      |                                    |                                                              |
      |                                    |                  QueueCircuitState (open, half_open, closed) |
      |                                    |<-------------------------------------------------------------|
      |                                    |                                                              |
      |                                    |                         When state equals 'open', lock queue |
      |<--------------------------------------------------------------------------------------------------|
      |                                    | --------------------------------------------------\          |
      |                                    |-| When state not equals 'open', execute queuedReq |          |
      |                                    | |-------------------------------------------------|          |
      |                                    |                                                              |
      |                              reply |                                                              |
      |<-----------------------------------|                                                              |
      |                                    |                                                              |
```

#### Update circuit statistics
Whenever the [QueueProcessor](src/main/java/org/swisspush/gateleen/queue/queuing/QueueProcessor.java) has executed a queued request and QCB's _statisticsUpdateEnabled_ property is true, the statistics of the corresponding circuit are updated.

The following information is stored for each circuit:

| Information | Description |
|-------------|-------------|
|  circuitHash | The circuitHash is used to reference the circuit. It is calculated from the name of the routing rule which matches the queued request |
|  status | The status of the circuit. Can be one of **open**, **closed** or **half_open** |
|  failRatio | The ratio [%] between the successful and the failed queued requests |
|  circuit | The readable circuit name. This name is equal to the routing rule name. e.g. _/playground/server/storage(.*)_ |
|  successful queued requests | A collection of successful requests for this circuit. The maximum amount of entries in this collection is configured by the _maxQueueSampleCount_ property |
|  failed queued requests | A collection of failed requests for this circuit. The maximum amount of entries in this collection is configured by the _maxQueueSampleCount_ property |
|  queues | A collection of queues for this circuit. This collection holds all the queues to be unlocked when the circuit goes to state _closed_ again |

When the update of the statistics of a circuit leads to a fulfillment of the conditions to open the circuit, this opening will be triggered. See chapter _Opening circuits_ for details.

The following sequence diagram shows the update statistics process

```
+-----------+                            +-----------------+                           +---------------------+
| Redisques |                            | QueueProcessor  |                           | QueueCircuitBreaker |
+-----------+                            +-----------------+                           +---------------------+
      |                                           |                                               |
      | notifyConsumer(queuedReq)                 |                                               |
      |------------------------------------------>|                                               |
      |                                           |                                               |
      |                                           | execute queuedReq                             |
      |                                           |------------------                             |
      |                                           |                 |                             |
      |                                           |<-----------------                             |
      |                                           |                                               |
      |                                           | updateStatistics()                            |
      |                                           |---------------------------------------------->|
      |                                           |                                               |
      |                                           | check response                                |
      |                                           |---------------                                |
      |                                           |              |                                |
      |                                           |<--------------                                |
      |-----------------------------------------\ |                                               |
      || When circuit status equals 'half_open' |-|                                               |
      ||----------------------------------------| |                                               |
      |                                           |                                               |
      |                                           | close circuit on response success             |
      |                                           |---------------------------------------------->|
      |                                           |                                               |
      |                                           | re-open circuit on response fail              |
      |                                           |---------------------------------------------->|
      |                                           |                                               |
```
**Additional information of the process:**
- The updateStatistics() call is made only for circuits in state _open_ or _half_open_
- When the circuit is in state _half_open_ and the queued request succeeded, the circuit will be closed
- When the circuit is in state _half_open_ and the queued request failed, the circuit will re-openend again

#### Opening circuits
Based on the calculations described in section [Update circuit statistics](#update-circuit-statistics), the circuit will be switched to state _open_, when all the following conditions are fulfilled:
- The amount of recorded (unique) queues has reached the value defined in configuration property _minQueueSampleCount_
- The ratio between failed and succeeded queue requests (respecting the _entriesMaxAgeMS_ configuration property) has reached the value defined in configuration property _errorThresholdPercentage_

Queued requests of open circuits will not be served anymore (fail-fast). See section [Closing circuits](#closing-circuits) for more information about how circuits are closed.

#### Closing circuits
The process to close a circuit can be seen in the sequence diagram below.

```
+---------------------+                               +-----------+            +-----------------+
| QueueCircuitBreaker |                               | Redisques |            | QueueProcessor  |
+---------------------+                               +-----------+            +-----------------+
           |                                                |                           |
           | switch open circuit to half_open               |                           |
           |---------------------------------               |                           |
           |                                |               |                           |
           |<--------------------------------               |                           |
           |                                                |                           |
           | unlock sample queue of half_open circuit       |                           |
           |----------------------------------------------->|                           |
           |                                                |                           |
           |                                                | notifyConsumer()          |
           |                                                |-------------------------->|
           |                                                |                           |
           |                                          close circuit on response success |
           |<---------------------------------------------------------------------------|
           |                                                |                           |
           |                                           re-open circuit on response fail |
           |<---------------------------------------------------------------------------|
           |                                                |                           |
           | unlock all queues of closed circuit            |                           |
           |----------------------------------------------->|                           |
           |                                                |                           |
```
##### Switch open circuits to state half_open
The QCB periodically checks whether there are open circuits to switch to state half_open by using the following method:

```java
/**
 * Changes the status of all circuits having a status equals {@link QueueCircuitState#OPEN}
 * to {@link QueueCircuitState#HALF_OPEN}.
 *
 * @return returns a future holding the amount of circuits updated
 */
Future<Long> setOpenCircuitsToHalfOpen();
```
This periodically check can be configured with the _openToHalfOpen_ configuration property
```json
{
  "openToHalfOpen": {
    "enabled": true,
    "interval": 120000
  }
}
```
The configuration includes the activation / deactivation of the check as well as the interval [ms] to execute the check.

##### Check half_open circuits
The QCB periodically checks whether there are half_open circuits by using the following method:

```java
/**
 * Unlocks a sample queue of all circuits having a status equals {@link QueueCircuitState#HALF_OPEN}. The sample
 * queues are always the queues which have not been unlocked the longest.
 *
 * @return returns a future holding the amount of unlocked sample queues
 */
Future<Long> unlockSampleQueues();
```

This periodically check can be configured with the _unlockSampleQueues_ configuration property
```json
{
  "unlockSampleQueues": {
    "enabled": true,
    "interval": 120000
  }
}
```
The configuration includes the activation / deactivation of the check as well as the interval [ms] to execute the check.

The following procedure is executed for every circuit in state _half_open_:

1. Unlock the queue which was not unlocked the longest (get the first item from the list)
2. Update the timestamp of this queue. (This can be seen as append the queue to the end of the list again)

Those unlocked **sample** queues will then be processed by vertx-redisques again, and therefore send to the QueueBrowser. The QueueBrowser will execute those queued requests and then check the responses. There are two possible subsequent steps to make:
- On success: close the circuit. See section [Close a circuit](#close-a-circuit)
- On failure: re-open the circuit. See section [Reopen circuits](#reopen-circuits)

##### Close a circuit
To finally close a circuit, the following steps are processed:

1. Unlock all queues related to the circuit
2. Change status of circuit to _closed_
3. Reset fail ratio value to 0
4. Clear all success/fail statistics entries of the circuit

##### Reopen circuits
When a queued request of a sample queue execution was not successful, the corresponding circuit is changed to status _open_ again. With the next execution of the [Switch open circuits to state half_open](#switch-open-circuits-to-state-half_open) task, the circuit will be changed
to status _half_open_ again and another queue (related to this circuit) will be unlocked.

##### Unlock queues
When a circuit is unlocked, the queues related to this circuit will be unlocked. To not overwhelm the server by unlocking all queues at once, the queues are unlocked slowly.

The unlocking of the queues can be configured with the _unlockQueues_ configuration property
```json
{
  "unlockQueues": {
    "enabled": true,
    "interval": 10000
  }
}
```
The configuration includes the activation / deactivation of the queue unlocking as well as the interval [ms] to unlock the queues.

**Note:** During the execution only 1 queue will be unlocked. Taken the configuration above, a queue is unlocked every 10s

### API
To check the current circuit states or close some or all circuits, the API handled by [QueueCircuitBreakerHttpRequestHandler](src/main/java/org/swisspush/gateleen/queue/queuing/circuitbreaker/api/QueueCircuitBreakerHttpRequestHandler.java) can be used.

The following request examples will use a configured prefix of _/playground/server/queuecircuitbreaker/circuit/_

#### Get status of a single circuit
To get the status of a single circuit use

> GET /playground/server/queuecircuitbreaker/circuit/myCircuitHash/status

The result will be a json object with the circuit status like the example below

```json
{
  "status": "closed"
}
```

#### Get information of a single circuit
To get the information of a single circuit use
> GET /playground/server/queuecircuitbreaker/circuit/myCircuitHash

The result will be a json object with the circuit information like the example below

```json
{
  "status": "closed",
  "info": {
    "failRatio": 15,
    "metric": "server-tests",
    "circuit": "/playground/server/tests/(.*)"
  }
}
```

#### Get information of all circuits
To get the information of all circuits use either
> GET /playground/server/queuecircuitbreaker/circuit/

or

> GET /playground/server/queuecircuitbreaker/circuit/_all

The result will be a json object with the information of all circuits like the example below

```json
{
  "myCircuitHash": {
    "infos": {
      "failRatio": 15,
      "metric": "server-tests",
      "circuit": "/playground/server/tests/(.*)"
    },
    "status": "closed"
  },
  "anotherCircuitHash": {
    "infos": {
      "failRatio": 90,
      "circuit": "/playground/some/backend/(.*)"
    },
    "status": "open"
  }
}
```

#### Close a single circuit
To close a single circuit use
> PUT /playground/server/queuecircuitbreaker/circuit/myCircuitHash/status

with the request body below

```json
{
  "status": "closed"
}
```

#### Close all circuits
To close all circuits use
> PUT /playground/server/queuecircuitbreaker/circuit/_all/status

with the request body below

```json
{
  "status": "closed"
}
```

### Configuration
The QCB can be configured to match the individual needs. To setup the configuration path, configure the [QueueCircuitBreakerConfigurationResourceManager](src/main/java/org/swisspush/gateleen/queue/queuing/circuitbreaker/configuration/QueueCircuitBreakerConfigurationResourceManager.java) with the desired path.

```java
new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, SERVER_ROOT + "/admin/v1/circuitbreaker");
```
The following configuration properties are available:

| Property | Default value | Description |
|----------|---------------|-------------|
| circuitCheckEnabled | false | Defines whether the circuit should be checked during the processing of the queued request. Set this to false to disable the Queue Circuit Breaker functionality |
| statisticsUpdateEnabled | false | Defines whether the statistics of a circuit should be updated during the processing of the queued request |
| errorThresholdPercentage | 90 | The threshold value [%] which must be reached to open a circuit |
| entriesMaxAgeMS | 86400000 ms (24h) | The maximum age of fail/success statistics entries to respect to calculate the failRatio |
| minQueueSampleCount | 100 | The minimum amount of statistics entries (unique uniqueRequestIDs) to reach before status can be changed. Since queues must respect the order of the queue items to process, this property can be seen as **minimum amount of distinct queues active per circuit** |
| maxQueueSampleCount | 5000 | The maximum amount of statistics entries (unique uniqueRequestIDs) to keep |
| openToHalfOpen.enabled | false | Defines whether the periodic task to change _open_ circuits to _half_open_ circuits should be enabled |
| openToHalfOpen.interval | 120000 ms (120s) | Defines the interval for the periodic task to change _open_ circuits to _half_open_ circuits when enabled |
| unlockQueues.enabled | false | Defines whether the periodic task to unlock queues should be enabled |
| unlockQueues.interval | 10000 ms (10s) | Defines the interval for the periodic task to unlock queues when enabled. Only 1 queue will be unlock in a single task execution |
| unlockSampleQueues.enabled | false | Defines whether the periodic task to unlock a sample queue for each circuit with status _half_open_ should be enabled |
| unlockSampleQueues.interval | 120000 ms (120s) | Defines the interval for the periodic task to unlock a sample queue for each circuit with status _half_open_ when enabled |


An example Queue Circuit Breaker configuration would look like this:

```json
{
  "circuitCheckEnabled": true,
  "statisticsUpdateEnabled": true,
  "errorThresholdPercentage": 99,
  "entriesMaxAgeMS": 86400000,
  "minQueueSampleCount": 100,
  "maxQueueSampleCount": 5000,
  "openToHalfOpen": {
    "enabled": true,
    "interval": 120000
  },
  "unlockQueues": {
    "enabled": true,
    "interval": 10000
  },
  "unlockSampleQueues": {
    "enabled": true,
    "interval": 120000
  }
}
```
#### How to find appropriate configuration values
Finding the right configuration values can be difficult. Several parameters have to be respected like for example:
* How many Queues is the server able to handle before it explodes
* How many Requests are expected for a single endpoint
* How sensitive should the Queue Circuit Breaker act

Given these parameters, a sample configuration could be made with the following calculations / thoughts:

**Given:** The server has a maximum capacity of 10'000 active queues (before exploding ;-))
**Given:** The average load of the server is about 2000 active queues

**Assumption:** An endpoint (backend) goes down which normally is used by about 600 queues per minute

Without the Queue Circuit Breaker opening any circuits, the time to live of the server can be calculated as follows:
* 600 req/m => The Server will be dead in approximately 13 minutes having 9'800 active queues
* 650 req/m (+15%) => The Server will be dead in approximately 12 minutes having 9'800 active queues
* 1000 req/m => The Server will be dead in approximately 8 minutes having 10'000 active queues

Based on these calculations, the following configuration values are arguable:

##### entriesMaxAgeMS
300000 [ms] => 5 minutes. This value is approximately half the time the server will survive without opening the circuit.

##### maxQueueSampleCount
4000 => This value is based on the expected request count for a single endpoint. Make sure that this value can hold more requests than with the _entriesMaxAgeMS_ are respected. In this case the _entriesMaxAgeMS_ is 5 minutes. So there are less than 4000 requests expected over 5 minutes for a single endpoint.

##### errorThresholdPercentage
80% This value can be chosen as wished. In this case the Queue Circuit Breaker should not act too agressive.

The other configuration values are not very critical and therefore not mentioned here.

### Log Queue Circuit Breaker configuration changes
To log the payload of changes to the Queue Circuit Breaker configuration, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the Queue Circuit Breaker configuration changes, make sure the url to the routing rules is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/circuitbreaker",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [QueueCircuitBreakerConfigurationResource](src/main/java/org/swisspush/gateleen/queue/queuing/circuitbreaker/configuration/QueueCircuitBreakerConfigurationResource.java) by calling
```java
queueCircuitBreakerConfigurationResourceManager.enableResourceLogging(true);
```

### Micrometer metrics
The Queue Circuit Breaker feature is monitored with micrometer. The following metrics are available:
* gateleen_circuitbreaker_status
  * 0 = closed, 1 = half_open, 2 = open
* gateleen_circuitbreaker_failratio

An Additional tag called `metricName` is provided to identify the related routing rule.

Example metrics:

```
# HELP gateleen_circuitbreaker_status Status of the circuit, 0=CLOSED, 1=HALF_OPEN, 2=OPEN
# TYPE gateleen_circuitbreaker_status gauge
gateleen_circuitbreaker_status{metricName="logininfo-v1-devices",} 0.0
gateleen_circuitbreaker_status{metricName="workinfo-v1-trash",} 0.0
gateleen_circuitbreaker_status{metricName="delegates-rule",} 1.0
# HELP gateleen_circuitbreaker_failratio Fail ratio of the circuit in percentage
# TYPE gateleen_circuitbreaker_failratio gauge
gateleen_circuitbreaker_failratio{metricName="logininfo-v1-devices",} 50.0
gateleen_circuitbreaker_failratio{metricName="workinfo-v1-trash",} 50.0
gateleen_circuitbreaker_failratio{metricName="delegates-rule",} 0.0
```

To enable the metrics, create a `QueueCircuitBreakerMetricsCollector` instance with a `metricCollectionIntervalSeconds` configuration value.

Example:
```java
new QueueCircuitBreakerMetricsCollector(vertx, lock, queueCircuitBreakerStorage, meterRegistry, exceptionFactory, 10);
```

## Queue Splitter
In case there are queues with a large number of queue items to process there is the option to configure these queues to split into sub-queues.
The split is implemented dispatching the incoming request in one of the sub-queues (so the split is always active). Don't use the queue splitting feature when queue items from a queue have to be in cronological order (requests in sub-queues are processed in parallel).

Queue splitters are configured together (for example in admin/v1/queueSplitters), each splitter configuration is composted of three parts:
* name: regex used to match the queue name of the incoming requests
* postfix rule: rule used to generate the postfix to append to the initial queue name
* postfixDelimiter: Optional delimiter value to add between queue name and postfix. When not configured, _-_ is used

There are two types of postfix rules:
* static
* based on request

### Static postfix rule
In the rule all postfixes for splitting are listed. Here is an example of splitter configuration with static postfix rule:
```json
"queue-static": {
        "description": "Simple static splitter",
        "postfixFromStatic": [
            "A",
            "B",
            "C",
            "D"
        ]
    }
```
In this case the splitter is applied only if the queue in the request is 'queue-static' and the splitting is done distributing uniformly the requests in the sub-queues 'queue-static-A', 'queue-static-B', 'queue-static-C' and 'queue-static-D'.

### Postfix rule based on request
Request header to use as postfix and/or the regex to extract parts from the url can be defined in the _postfixFromRequest_ property. Here are two examples:
```json
    "queue-header-[a-z]+": {
        "description": "Simple splitter with request header",
        "postfixDelimiter": "+",
        "postfixFromRequest": {
            "header": "x-rp-deviceid"
        }
    },
    "queue-path-[a-z]+": {
        "description": "Simple splitter with request url matching",
        "postfixDelimiter": "_",
        "postfixFromRequest": {
            "url": ".*/path1/(.*)/.*"
        }
    }
```
In both cases the splitter is applied to all the queues matching the name regex.

In first case the value of the header 'x-rp-deviceid' is added. A queue with name 'queue-header-test' and with a request header 'x-rp-deviceid' valued 'A1B2C3D4' the request is splitted in the sub-queue 'queue-header-test+A1B2C3D4'.
For the second case the matching parts of the request url are added. A queue with name 'queue-path-test' and with the url ending with .../path1/path2/path3 the request is splitted in the sub-queue 'queue-path-test_path2'.

### Splitter implementation
The evaluation of splitting for a queue is defined in the interface [QueueSplitter](../gateleen-queue/src/main/java/org/swisspush/gateleen/queue/queuing/splitter/QueueSplitter.java) with two implementations: [QueueSplitterImpl](../gateleen-queue/src/main/java/org/swisspush/gateleen/queue/queuing/splitter/QueueSplitterImpl.java) (to execute the splitters configured) and [NoOpQueueSplitter](../gateleen-queue/src/main/java/org/swisspush/gateleen/queue/queuing/splitter/NoOpQueueSplitter.java) (no splitter).
For each splitter configured is created either an instance of [QueueSplitExecutorFromStaticList](../gateleen-queue/src/main/java/org/swisspush/gateleen/queue/queuing/splitter/executors/QueueSplitExecutorFromStaticList.java) (for the case of static postfix rule) or an instance of [QueueSplitExecutorFromRequest](../gateleen-queue/src/main/java/org/swisspush/gateleen/queue/queuing/splitter/executors/QueueSplitExecutorFromRequest.java) (for the case of postfix rule based on request).
