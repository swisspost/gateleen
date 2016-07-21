# gateleen-queue
The gateleen-queue module provides queuing functionality and acts as a bridge to [vertx-redisques](https://github.com/swisspush/vertx-redisques).

## Queue Circuit Breaker
// TODO write something

### Configuration
The Queue Circuit Breaker can be configured to match the individual needs. To setup the configuration path, configure the [QueueCircuitBreakerConfigurationResourceManager](src/main/java/org/swisspush/gateleen/queue/queuing/circuitbreaker/configuration/QueueCircuitBreakerConfigurationResourceManager.java) with the desired path.

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

### Features
// TODO write something

#### Check circuit status
// TODO write something

#### Update circuit statistics
// TODO write something

#### Opening circuits
// TODO write something

#### Closing circuits
// TODO write something

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