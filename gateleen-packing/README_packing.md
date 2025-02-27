# gateleen-packing

With the packing feature a client is able to send multiple requests as payload of a single request. The packing feature is useful for reducing the number of requests to the server.

To use the packing feature add the following header to the request:

```
x-packed: true
```
The payload of the request will be validated against the packing schema. Check the schema [gateleen_packing_schema_packing](src/main/resources/gateleen_packing_schema_packing)!

The payload holds an array of requests having the following properties:

| Field                 | Required | Description                                                                                   | 
|-----------------------|:--------:|-----------------------------------------------------------------------------------------------|
| uri                   |   yes    | The URI where the request will be sent to                                                     |
| method                |   yes    | The HTTP method used to perform the given request. Allowed are `GET`, `PUT`, `POST`, `DELETE` |
| payload               |    no    | The json content of the payload required for processing the request.                          |
| headers               |    no    | Array of request headers that will be sent with the request                                   |
| copy_original_headers |    no    | Boolean flag whether to add the request headers from the original request. Default is `false` |

Example request:

> PUT /playground/some/url

```json
{
  "requests": [
    {
      "uri": "/playground/some/sub/resource/r1",
      "method": "PUT",
      "payload": {
        "key": 1,
        "key2": [1,2,3]
      },
      "headers": [["x-foo", "bar"], ["x-bar", "foo"]]
    },
    {
      "uri": "/playground/some/sub/resource/r2",
      "method": "POST"
    },
    {
      "uri": "/playground/some/sub/resource/r3",
      "method": "PUT",
      "payload": {
        "key": 5
      },
      "copy_original_headers": true,
      "headers": [["x-user", "batman"], ["x-bar", "foo"]]
    }    
  ]
}
```
The defined requests will be enqueued using the configured queueName prefix and the current timestamp. To enqueue to a specific queue, the following header has be added to each request:

```
x-queue: <queueName>
```

## Micrometer metrics
The packing feature is monitored with micrometer. The following metrics are available:
* gateleen_packing_requests_success_total
* gateleen_packing_requests_fail_total

Example metrics:

```
# HELP gateleen_packing_requests_success_total Amount of successfully packed requests processed
# TYPE gateleen_packing_requests_success_total counter
gateleen_packing_requests_success_total 8234.0
# HELP gateleen_packing_requests_fail_total Amount of failed packed requests processed
# TYPE gateleen_packing_requests_fail_total counter
gateleen_packing_requests_fail_total 0.0
```

To enable `gateleen_kafka_send_success_messages_total` and `gateleen_kafka_send_fail_messages_total` metrics, set a `MeterRegistry` instance by calling `setMeterRegistry(MeterRegistry meterRegistry)` method in `KafkaMessageSender` class.
To enable `gateleen_kafka_validation_fail_messages_total` metrics, set a `MeterRegistry` instance by calling `setMeterRegistry(MeterRegistry meterRegistry)` method in `KafkaMessageValidator` class.