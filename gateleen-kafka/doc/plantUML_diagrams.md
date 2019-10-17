# KafkaHandler integration in Verticle
```
@startuml

hide footbox
actor User
participant MainVerticle order 10
participant KafkaHandler order 20
participant OtherHandlers order 30
participant Router order 40

User -> MainVerticle: Request
activate MainVerticle
MainVerticle -> KafkaHandler: [uri == "/playground/server/streaming/myTopic"] handle(request)
activate KafkaHandler
return true
MainVerticle -> OtherHandlers: [request not yet handled] handle(request)
activate OtherHandlers
return handled (true/false)
MainVerticle -> Router: [no handler handled request] route(request)
activate Router
return
return Response

@enduml
```

# KafkaHandler initialization
```
@startuml

hide footbox
actor Setup
participant KafkaHandler order 10
participant ConfigurationResourceManager order 20
participant ResourceStorage order 30
participant KafkaConfigurationParser order 40
participant KafkaProducerRepository order 50

Setup -> KafkaHandler: initialize()
activate KafkaHandler
KafkaHandler -> ConfigurationResourceManager: registerResource(configResourceUri, schema)
KafkaHandler -> ConfigurationResourceManager: registerObserver(this, configResourceUri)
KafkaHandler -> ConfigurationResourceManager: getRegisteredResource(configResourceUri)
activate ConfigurationResourceManager
ConfigurationResourceManager -> ResourceStorage: get(configResourceUri)
activate ResourceStorage
return Optional<Buffer>
return Optional<Buffer>
alt missing configuration case
KafkaHandler -> KafkaHandler: [configuration resource missing] log error
return
end
KafkaHandler -> KafkaConfigurationParser: parse(configurationResource)
activate KafkaHandler
activate KafkaConfigurationParser
return List<KafkaConfiguration>
KafkaHandler -> KafkaProducerRepository: closeAll()
loop for every KafkaConfiguration
KafkaHandler -> KafkaProducerRepository: addKafkaProducer(kafkaConfiguration)
activate KafkaProducerRepository
note left of KafkaProducerRepository: instantiate Producer and store it\nin a map with key 'topic-pattern'\nand value producer
return
end

@enduml
```

# Topic configuration resource update
```
@startuml

hide footbox
participant KafkaHandler order 20
participant ConfigurationResourceManager order 10
participant KafkaConfigurationParser order 30
participant KafkaProducerRepository order 40

ConfigurationResourceManager -> KafkaHandler: resourceChanged(String resourceUri, String resource)
activate KafkaHandler
KafkaHandler -> KafkaConfigurationParser: parse(resource)
activate KafkaConfigurationParser
return List<KafkaConfiguration>
KafkaHandler -> KafkaProducerRepository: closeAll()
loop for every KafkaConfiguration
KafkaHandler -> KafkaProducerRepository: addKafkaProducer(kafkaConfiguration)
activate KafkaProducerRepository
note left of KafkaProducerRepository: instantiate Producer and store it\nin a map with key 'topic-pattern'\nand value producer
return
end

@enduml
```

# Sending messages to Kafka
```
@startuml

hide footbox
participant MainVerticle order 10
participant KafkaHandler order 20
participant KafkaTopicExtractor order 30
participant KafkaProducerRepository order 40
participant KafkaProducerRecordBuilder order 50
participant KafkaMessageSender order 60

MainVerticle -> KafkaHandler: handle(request)
activate KafkaHandler
KafkaHandler -> KafkaTopicExtractor: extractTopic(request.uri)
activate KafkaTopicExtractor
return topic (optional)
alt topic is missing
return 400 Bad Request
end
KafkaHandler -> KafkaProducerRepository: findMatchingKafkaProducer(topic)
activate KafkaHandler
activate KafkaProducerRepository
return producer (optional)

alt no matching producer
KafkaHandler -> MainVerticle: 404 Not Found
deactivate KafkaHandler
end

KafkaHandler -> KafkaProducerRecordBuilder: buildRecords(topic, request.payload)
activate KafkaHandler
loop for entry in records array
activate KafkaProducerRecordBuilder
KafkaProducerRecordBuilder -> KafkaProducerRecordBuilder: extract 'key' from payload
note right of KafkaProducerRecordBuilder: See vertx-kafka-client documentation\non how to set key to record. If no key in\npayload found, use default (round robin)
KafkaProducerRecordBuilder -> KafkaProducerRecordBuilder: extract 'value' from payload
KafkaProducerRecordBuilder -> KafkaProducerRecordBuilder: extract 'headers' from payload
loop for every header
KafkaProducerRecordBuilder -> KafkaProducerRecordBuilder: record.addHeader(name, value)
end
end
return records
KafkaHandler -> KafkaMessageSender: sendMessages(producer, records)
activate KafkaMessageSender
loop for message in records
KafkaMessageSender -> KafkaMessageSender: producer.write(message)
end
return
KafkaHandler -> MainVerticle: true

@enduml
```