# gateleen-kafka
This module provides a handler to forward http requests to [Apache Kafka](https://kafka.apache.org/) using the native
[Vert.x Kafka client](https://vertx.io/docs/vertx-kafka-client/java/).

For now, only sending messages to Kafka (via KafkaProducer) is available. Consuming messages from Kafka (via KafkaConsumer)
is not implemented.  

## Configuration
To configure this module, the following paths have to be configured during setup of the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java) class:

| Path               | Description                              |
|:-------------------- | :--------------------------------------- |
| configResourceUri    | The path to the topics configuration resource. Example: `/playground/server/admin/v1/kafka/topicsConfig` |
| streamingPath        | The path to handle kafka message requests. Example: `/playground/server/streaming/` |

### Topic configuration resource
This resource holds the _KafkaProducer_ configuration values for topics. The path to this resource is the `configResourceUri` mentioned above.

Modifications (PUT requests) to this resource are validated against the schema [gateleen_kafka_topic_configuration_schema](src/main/resources/gateleen_kafka_topic_configuration_schema). 

The following example shows a topic configuration resource with configurations for two topics. The order of the topics is important. Topics are tried to match against the topic names (regex) from top to
bottom. The first topic name matching the provided topic is used.
```json
{
  "topics": {
    "my.topic.*": {
      "bootstrap.servers": "localhost:9092",
      "key.serializer": "org.apache.kafka.common.serialization.StringSerializer",
      "value.serializer": "org.apache.kafka.common.serialization.StringSerializer"
    },
    ".+": {
      "bootstrap.servers": "localhost:9093",
      "key.serializer": "org.apache.kafka.common.serialization.StringSerializer",
      "value.serializer": "org.apache.kafka.common.serialization.StringSerializer"
    }
  }
}
```
In the example above, a topic called `my.topic.abc` would use the first configuration entry. A topic called `some.other.topic` would use the second configuration entry.

#### Topic configuration values
The following topic configuration values are required:

| Configuration value  | Description                              |
|:-------------------- | :--------------------------------------- |
| bootstrap.servers    | A list of host/port pairs to use for establishing the initial connection to the Kafka cluster. The client will make use of all servers irrespective of which servers are specified here for bootstrapping—this list only impacts the initial hosts used to discover the full set of servers. This list should be in the form `host1:port1,host2:port2,....` Since these servers are just used for the initial connection to discover the full cluster membership (which may change dynamically), this list need not contain the full set of servers (you may want more than one, though, in case a server is down). |
| key.serializer       | Serializer class for key that implements the `org.apache.kafka.common.serialization.Serializer` interface. |
| value.serializer     | Serializer class for value that implements the `org.apache.kafka.common.serialization.Serializer` interface. |

Besides these required configuration values, additional string values can be added. See documentation from Apache Kafka [here](https://kafka.apache.org/documentation/#producerconfigs).

## Usage
To use the gateleen-kafka module, the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java) class has to be initialized as described in the _configuration_ section. Also the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java) has to be integrated in the "MainVerticle" handling all
incoming requests. See [Playground Server](../gateleen-playground/src/main/java/org/swisspush/gateleen/playground/Server.java) and [Runconfig](../gateleen-runconfig/src/main/java/org/swisspush/gateleen/runconfig/RunConfig.java).

The following sequence diagram shows the setup of the "MainVerticle". The `streamingPath` (KafkaHandler) is configured to `/playground/server/streaming/`

```
       ┌─┐                                                                                                                                      
       ║"│                                                                                                                                      
       └┬┘                                                                                                                                      
       ┌┼┐                                                                                                                                      
        │            ┌────────────┐                                                    ┌────────────┐          ┌─────────────┐          ┌──────┐
       ┌┴┐           │MainVerticle│                                                    │KafkaHandler│          │OtherHandlers│          │Router│
      User           └─────┬──────┘                                                    └─────┬──────┘          └──────┬──────┘          └──┬───┘
       │     Request      ┌┴┐                                                                │                        │                    │    
       │ ────────────────>│ │                                                                │                        │                    │    
       │                  │ │                                                                │                        │                    │    
       │                  │ │[uri == "/playground/server/streaming/myTopic"] handle(request)┌┴┐                       │                    │    
       │                  │ │ ─────────────────────────────────────────────────────────────>│ │                       │                    │    
       │                  │ │                                                               └┬┘                       │                    │    
       │                  │ │                              true                              │                        │                    │    
       │                  │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                        │                    │    
       │                  │ │                                                                │                        │                    │    
       │                  │ │                       [request not yet handled] handle(request)│                       ┌┴┐                   │    
       │                  │ │ ──────────────────────────────────────────────────────────────────────────────────────>│ │                   │    
       │                  │ │                                                                │                       └┬┘                   │    
       │                  │ │                                  handled (true/false)          │                        │                    │    
       │                  │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                    │    
       │                  │ │                                                                │                        │                    │    
       │                  │ │                                 [no handler handled request] route(request)             │                   ┌┴┐  
       │                  │ │ ───────────────────────────────────────────────────────────────────────────────────────────────────────────>│ │  
       │                  │ │                                                                │                        │                   └┬┘  
       │                  │ │                                                                │                        │                    │    
       │                  │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    
       │                  └┬┘                                                                │                        │                    │    
       │      Response     │                                                                 │                        │                    │    
       │ <─ ─ ─ ─ ─ ─ ─ ─ ─                                                                  │                        │                    │    
       │                   │                                                                 │                        │                    │    
       │                   │                                                                 │                        │                    │    
```

### Sending messages to Kafka
To send messages to Kafka through the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java), a `POST` request to

> /playground/server/streaming/myTopic

is used. The last url segment `myTopic` defines the topic to send the messages to.

The Payload of the `POST` request must have the following structure:
```json
{
  "records": [
    {
      "key": "key_1",
      "value": {},
      "headers": {}
    }
  ]
}
```

| Property | Required  | Description |
|:-------------------- | :---: | :--------------------------------------- |
| records | yes | An array holding 1 ore more _record_ objects |
| value   | yes | The actual message |
| key     | no  | Messages with the same `key` will be sent to the same partition. When no `key` is defined the destination partition is defined by a round robin algorithm |
| headers | no  | Key/Value pairs (String) to add as headers to the message |

Example:
```json
{
  "records": [
    {
      "key": "key_1",
      "value": {
        "metadata": {
          "techId": "071X15004960860907613"
        },
        "event": {
          "actionTime": "2019-06-18T14:28:27.617+02:00",
          "sending": {
            "identCode": "777",
            "capturingType": "1"
          }
        }
      },
      "headers": {
        "x-header-a": "value-a",
        "x-header-b": "value-b"
      }
    }
  ]
}
```

#### Transactional transmission
When sending multiple messages (having more than 1 entry in the records array), transactional transmission is **not** guaranteed since, [Vert.x Kafka client](https://vertx.io/docs/vertx-kafka-client/java/)
does not yet provide transaction support.

See the following issues requesting this feature:
* [added kafka transaction support #143](https://github.com/vert-x3/vertx-kafka-client/pull/143)
* [Added Kafka Transactions support #66](https://github.com/vert-x3/vertx-kafka-client/pull/66)

### Delegates vs Kafka
When working with Kafka, the [gateleen-delegate](../gateleen-delegate/README_delegate.md) module is most likely be used too, because delegates can transform an incoming http request body to the
structure needed by Kafka.

The following delegate definition transforms the incoming payload to the correct structure and forwards it by making the following request, which will be handled by the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java):

> POST /playground/server/streaming/myTopic

Delegate definition:
```json
{
  "methods": [
    "PUT"
  ],
  "pattern": ".*/execution/([^/]+)",
  "requests": [
    {
      "method": "POST",
      "uri": "/playground/server/streaming/myTopic",
      "transformWithMetadata": [
        {
          "operation": "shift",
          "spec": {
            "urlParts": {
              "1": "records[0].value.metadata.techId"
            },
            "headers": {
              "x-abc": "records[0].value.metadata.x-abc"
            },
            "payload": {
              "@": "records[0].value.data",
              "node": {
                "id": [
                  "records[0].key",
                  "records[0].value.&"
                ]
              }
            }
          }
        }
      ]
    }
  ]
}
```
With this delegate definition, the incoming http request
```json
{
  "urlParts": [
    "/some/test/url/xyz",
    "xyz"
  ],
  "headers": {
    "x-abc": "x",
    "x-def": "x,y",
    "x-ghi": "z"
  },
  "payload": {
    "parent": "10",
    "node": {
      "id": "Key_123456",
      "type": "x",
      "isLeaf": false
    }
  }
}
```
will be transformed into
```json
{
  "records": [
    {
      "value": {
        "metadata": {
          "techId": "xyz",
          "x-abc": "x"
        },
        "data": {
          "parent": "10",
          "node": {
            "id": "Key_123456",
            "type": "x",
            "isLeaf": false
          }
        },
        "id": "Key_123456"
      },
      "key": "Key_123456"
    }
  ]
}
```
which is the required structure for the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java) to extract the message(s) from.

## Main processes sequence diagrams
The following sections contain sequence diagrams showing the inner workings of the most critical processes.

### Initialization KafkaHandler
This sequence diagrams shows the initialization of the [KafkaHandler](src/main/java/org/swisspush/gateleen/kafka/KafkaHandler.java) on server startup:
```
                      ┌─┐                                                                                                                                                                                                 
                      ║"│                                                                                                                                                                                                 
                      └┬┘                                                                                                                                                                                                 
                      ┌┼┐                                                                                                                                                                                                 
                       │             ┌────────────┐                             ┌────────────────────────────┐          ┌───────────────┐          ┌────────────────────────┐          ┌───────────────────────┐          
                      ┌┴┐            │KafkaHandler│                             │ConfigurationResourceManager│          │ResourceStorage│          │KafkaConfigurationParser│          │KafkaProducerRepository│          
                     Setup           └─────┬──────┘                             └─────────────┬──────────────┘          └───────┬───────┘          └───────────┬────────────┘          └───────────┬───────────┘          
                       │  initialize()    ┌┴┐                                                 │                                 │                              │                                   │                      
                       │─────────────────>│ │                                                 │                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │   registerResource(configResourceUri, schema)   │                                 │                              │                                   │                      
                       │                  │ │ ───────────────────────────────────────────────>│                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │    registerObserver(this, configResourceUri)    │                                 │                              │                                   │                      
                       │                  │ │ ───────────────────────────────────────────────>│                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │    getRegisteredResource(configResourceUri)    ┌┴┐                                │                              │                                   │                      
                       │                  │ │ ──────────────────────────────────────────────>│ │                                │                              │                                   │                      
                       │                  │ │                                                │ │                                │                              │                                   │                      
                       │                  │ │                                                │ │    get(configResourceUri)     ┌┴┐                             │                                   │                      
                       │                  │ │                                                │ │ ────────────────────────────> │ │                             │                                   │                      
                       │                  │ │                                                │ │                               └┬┘                             │                                   │                      
                       │                  │ │                                                │ │        Optional<Buffer>        │                              │                                   │                      
                       │                  │ │                                                │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                              │                                   │                      
                       │                  │ │                                                └┬┘                                │                              │                                   │                      
                       │                  │ │                Optional<Buffer>                 │                                 │                              │                                   │                      
                       │                  │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
          ╔══════╤═════╪══════════════════╪═╪═════════════════════════════════════════════════╪══╗                              │                              │                                   │                      
          ║ ALT  │  missing configuration case                                                │  ║                              │                              │                                   │                      
          ╟──────┘     │                  │ │                                                 │  ║                              │                              │                                   │                      
          ║            │                  │ │────┐                                            │  ║                              │                              │                                   │                      
          ║            │                  │ │    │ [configuration resource missing] log error │  ║                              │                              │                                   │                      
          ║            │                  │ │<───┘                                            │  ║                              │                              │                                   │                      
          ║            │                  └┬┘                                                 │  ║                              │                              │                                   │                      
          ║            │                   │                                                  │  ║                              │                              │                                   │                      
          ║            │<─ ─ ─ ─ ─ ─ ─ ─ ─ │                                                  │  ║                              │                              │                                   │                      
          ╚════════════╪═══════════════════╪══════════════════════════════════════════════════╪══╝                              │                              │                                   │                      
                       │                   │                                                  │                                 │                              │                                   │                      
                       │                  ┌┴┐                                          parse(configurationResource)             │                             ┌┴┐                                  │                      
                       │                  │ │ ───────────────────────────────────────────────────────────────────────────────────────────────────────────────>│ │                                  │                      
                       │                  │ │                                                 │                                 │                             └┬┘                                  │                      
                       │                  │ │                                             List<KafkaConfiguration>              │                              │                                   │                      
                       │                  │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │                                                 │                    closeAll()   │                              │                                   │                      
                       │                  │ │ ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────>│                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │   ╔═══════╤══════╪═╪═════════════════════════════════════════════════╪═════════════════════════════════╪══════════════════════════════╪═══════════════════════════════════╪═════════════════════╗
                       │   ║ LOOP  │  for every KafkaConfiguration                            │                                 │                              │                                   │                     ║
                       │   ╟───────┘      │ │                                                 │                                 │                              │                                   │                     ║
                       │   ║              │ │                                                 │      addKafkaProducer(kafkaConfiguration)                      │                                  ┌┴┐                    ║
                       │   ║              │ │ ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────> │ │                    ║
                       │   ║              │ │                                                 │                                 │                              │                                  │ │                    ║
                       │   ║              │ │                                                 │                                 │                           ╔══╧════════════════════════════════╗ │ │                    ║
                       │   ║              │ │                                                 │                                 │                           ║instantiate Producer and store it ░║ │ │                    ║
                       │   ║              │ │                                                 │                                 │                           ║in a map with key 'topic-pattern'  ║ │ │                    ║
                       │   ║              │ │                                                 │                                 │                           ║and value producer                 ║ │ │                    ║
                       │   ║              │ │                                                 │                                 │                           ╚══╤════════════════════════════════╝ └┬┘                    ║
                       │   ║              │ │                                                 │                                 │                              │                                   │                     ║
                       │   ║              │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                     ║
                       │   ╚══════════════╪═╪═════════════════════════════════════════════════╪═════════════════════════════════╪══════════════════════════════╪═══════════════════════════════════╪═════════════════════╝
                       │                  │ │                                                 │                                 │                              │                                   │                      
                       │                  └┬┘                                                 │                                 │                              │                                   │                      
```

### Update topic configuration resource
This sequence diagrams shows the process when updating the topic configuration resource:
```
     ┌────────────────────────────┐                                ┌────────────┐          ┌────────────────────────┐          ┌───────────────────────┐          
     │ConfigurationResourceManager│                                │KafkaHandler│          │KafkaConfigurationParser│          │KafkaProducerRepository│          
     └─────────────┬──────────────┘                                └─────┬──────┘          └───────────┬────────────┘          └───────────┬───────────┘          
                   │resourceChanged(String resourceUri, String resource)┌┴┐                            │                                   │                      
                   │ ──────────────────────────────────────────────────>│ │                            │                                   │                      
                   │                                                    │ │                            │                                   │                      
                   │                                                    │ │      parse(resource)      ┌┴┐                                  │                      
                   │                                                    │ │ ─────────────────────────>│ │                                  │                      
                   │                                                    │ │                           └┬┘                                  │                      
                   │                                                    │ │  List<KafkaConfiguration>  │                                   │                      
                   │                                                    │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                                   │                      
                   │                                                    │ │                            │                                   │                      
                   │                                                    │ │                           closeAll()                           │                      
                   │                                                    │ │ ──────────────────────────────────────────────────────────────>│                      
                   │                                                    │ │                            │                                   │                      
                   │                                                    │ │                            │                                   │                      
                   │                                     ╔═══════╤══════╪═╪════════════════════════════╪═══════════════════════════════════╪═════════════════════╗
                   │                                     ║ LOOP  │  for every KafkaConfiguration       │                                   │                     ║
                   │                                     ╟───────┘      │ │                            │                                   │                     ║
                   │                                     ║              │ │             addKafkaProducer(kafkaConfiguration)              ┌┴┐                    ║
                   │                                     ║              │ │ ────────────────────────────────────────────────────────────> │ │                    ║
                   │                                     ║              │ │                            │                                  │ │                    ║
                   │                                     ║              │ │                         ╔══╧════════════════════════════════╗ │ │                    ║
                   │                                     ║              │ │                         ║instantiate Producer and store it ░║ │ │                    ║
                   │                                     ║              │ │                         ║in a map with key 'topic-pattern'  ║ │ │                    ║
                   │                                     ║              │ │                         ║and value producer                 ║ │ │                    ║
                   │                                     ║              │ │                         ╚══╤════════════════════════════════╝ └┬┘                    ║
                   │                                     ║              │ │                            │                                   │                     ║
                   │                                     ║              │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                     ║
                   │                                     ╚══════════════╪═╪════════════════════════════╪═══════════════════════════════════╪═════════════════════╝
                   │                                                    │ │                            │                                   │                      
                   │                                                    └┬┘                            │                                   │                      
```

### Sending messages to Kafka
This sequence diagrams shows the process when messages are sent to Kafka:
```
                    ┌────────────┐          ┌────────────┐           ┌───────────────────┐          ┌───────────────────────┐          ┌──────────────────────────┐               ┌──────────────────┐                         
                    │MainVerticle│          │KafkaHandler│           │KafkaTopicExtractor│          │KafkaProducerRepository│          │KafkaProducerRecordBuilder│               │KafkaMessageSender│                         
                    └─────┬──────┘          └─────┬──────┘           └─────────┬─────────┘          └───────────┬───────────┘          └────────────┬─────────────┘               └────────┬─────────┘                         
                          │   handle(request)    ┌┴┐                           │                                │                                   │                                      │                                   
                          │ ────────────────────>│ │                           │                                │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      │ │extractTopic(request.uri) ┌┴┐                               │                                   │                                      │                                   
                          │                      │ │ ───────────────────────> │ │                               │                                   │                                      │                                   
                          │                      │ │                          └┬┘                               │                                   │                                      │                                   
                          │                      │ │     topic (optional)      │                                │                                   │                                      │                                   
                          │                      │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                                │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
          ╔══════╤════════╪══════════════════════╪═╪═══════════════╗           │                                │                                   │                                      │                                   
          ║ ALT  │  topic is missing             │ │               ║           │                                │                                   │                                      │                                   
          ╟──────┘        │                      └┬┘               ║           │                                │                                   │                                      │                                   
          ║               │    400 Bad Request    │                ║           │                                │                                   │                                      │                                   
          ║               │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                 ║           │                                │                                   │                                      │                                   
          ╚═══════════════╪═══════════════════════╪════════════════╝           │                                │                                   │                                      │                                   
                          │                       │                            │                                │                                   │                                      │                                   
                          │                      ┌┴┐             findMatchingKafkaProducer(topic)              ┌┴┐                                  │                                      │                                   
                          │                      │ │ ────────────────────────────────────────────────────────> │ │                                  │                                      │                                   
                          │                      │ │                           │                               └┬┘                                  │                                      │                                   
                          │                      │ │                    producer (optional)                     │                                   │                                      │                                   
                          │                      │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
          ╔══════╤════════╪══════════════════════╪═╪═══════════════╗           │                                │                                   │                                      │                                   
          ║ ALT  │  no matching producer         │ │               ║           │                                │                                   │                                      │                                   
          ╟──────┘        │                      └┬┘               ║           │                                │                                   │                                      │                                   
          ║               │     404 Not Found     │                ║           │                                │                                   │                                      │                                   
          ║               │ <──────────────────────                ║           │                                │                                   │                                      │                                   
          ╚═══════════════╪═══════════════════════╪════════════════╝           │                                │                                   │                                      │                                   
                          │                       │                            │                                │                                   │                                      │                                   
                          │                      ┌┴┐                           │ buildRecords(topic, request.payload)                              ┌┴┐                                     │                                   
                          │                      │ │ ─────────────────────────────────────────────────────────────────────────────────────────────>│ │                                     │                                   
                          │                      │ │                           │                                │                                  │ │                                     │                                   
                          │                      │ │                           │                                │                                  │ │                                     │                                   
                          │                      │ │                           │                                │  ╔═══════╤═══════════════════════╪═╪═════════════════════════════════════╪══════════════╗                    
                          │                      │ │                           │                                │  ║ LOOP  │  for entry in records array                                   │              ║                    
                          │                      │ │                           │                                │  ╟───────┘                       │ │                                     │              ║                    
                          │                      │ │                           │                                │  ║                               │ │────┐                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │    │ extract 'key' from payload     │              ║                    
                          │                      │ │                           │                                │  ║                               │ │<───┘                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │                                     │              ║                    
                          │                      │ │                           │                                │  ║                               │ │  ╔══════════════════════════════════╧════════╗     ║                    
                          │                      │ │                           │                                │  ║                               │ │  ║See vertx-kafka-client documentation      ░║     ║                    
                          │                      │ │                           │                                │  ║                               │ │  ║on how to set key to record. If no key in  ║     ║                    
                          │                      │ │                           │                                │  ║                               │ │  ║payload found, use default (round robin)   ║     ║                    
                          │                      │ │                           │                                │  ║                               │ │  ╚══════════════════════════════════╤════════╝     ║                    
                          │                      │ │                           │                                │  ║                               │ │────┐                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │    │ extract 'value' from payload   │              ║                    
                          │                      │ │                           │                                │  ║                               │ │<───┘                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │                                     │              ║                    
                          │                      │ │                           │                                │  ║                               │ │────┐                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │    │ extract 'headers' from payload │              ║                    
                          │                      │ │                           │                                │  ║                               │ │<───┘                                │              ║                    
                          │                      │ │                           │                                │  ║                               │ │                                     │              ║                    
                          │                      │ │                           │                                │  ║                               │ │                                     │              ║                    
                          │                      │ │                           │                                │  ║         ╔═══════╤═════════════╪═╪═════════════════════════════════════╪═╗            ║                    
                          │                      │ │                           │                                │  ║         ║ LOOP  │  for every header                                   │ ║            ║                    
                          │                      │ │                           │                                │  ║         ╟───────┘             │ │                                     │ ║            ║                    
                          │                      │ │                           │                                │  ║         ║                     │ │────┐                                │ ║            ║                    
                          │                      │ │                           │                                │  ║         ║                     │ │    │ record.addHeader(name, value)  │ ║            ║                    
                          │                      │ │                           │                                │  ║         ║                     │ │<───┘                                │ ║            ║                    
                          │                      │ │                           │                                │  ║         ╚═════════════════════╪═╪═════════════════════════════════════╪═╝            ║                    
                          │                      │ │                           │                                │  ╚═══════════════════════════════╪═╪═════════════════════════════════════╪══════════════╝                    
                          │                      │ │                           │                                │                                  └┬┘                                     │                                   
                          │                      │ │                           │                records         │                                   │                                      │                                   
                          │                      │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      │ │                           │                       sendMessages(producer, records)              │                                     ┌┴┐                                  
                          │                      │ │ ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────>│ │                                  
                          │                      │ │                           │                                │                                   │                                     │ │                                  
                          │                      │ │                           │                                │                                   │                                     │ │                                  
                          │                      │ │                           │                                │                                   │                   ╔═══════╤═════════╪═╪═════════════════════════════════╗
                          │                      │ │                           │                                │                                   │                   ║ LOOP  │  for message in records                     ║
                          │                      │ │                           │                                │                                   │                   ╟───────┘         │ │                                 ║
                          │                      │ │                           │                                │                                   │                   ║                 │ │────┐                            ║
                          │                      │ │                           │                                │                                   │                   ║                 │ │    │ producer.write(message)    ║
                          │                      │ │                           │                                │                                   │                   ║                 │ │<───┘                            ║
                          │                      │ │                           │                                │                                   │                   ╚═════════════════╪═╪═════════════════════════════════╝
                          │                      │ │                           │                                │                                   │                                     └┬┘                                  
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │         true         │ │                           │                                │                                   │                                      │                                   
                          │ <────────────────────│ │                           │                                │                                   │                                      │                                   
                          │                      │ │                           │                                │                                   │                                      │                                   
                          │                      └┬┘                           │                                │                                   │                                      │                                   
```