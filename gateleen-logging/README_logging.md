# gateleen-logging

This feature allows to log the payload and header values of requests.

#### Configuration
Use the *LoggingResourceManager* class to configure the logging configuration resource. The logging configuration resource is a json resource with the following content:

##### Property: Headers
A string array of headers to log. When no headers property is provided, ALL headers are logged. When no headers should be logged, add an empty array. See examples below:

```json
//log no headers
headers: []
  
//log specified headers only
headers:["x-user-zip", "x-user-tour", "x-rp-lang"]
```

##### Property: Payload
An object called destinations (optional) which contains destination objects specifying the type of the destination (e.g. file or eventBus) and the filename in case of a file or the address in case of a eventBus. 
| Property    | Description                              | 
|:----------- | :--------------------------------------- | 
| <name>      | The name of the object, used in the filters to reference the destination. |
| type        | Specifies the type of the destination. This can be a file (file) or an address from the eventBus (address). |
| file        | Used to specify the name of the file, where the filtered content should be logged. |
| address     | Used to specify the address in the eventBus, where the filtered content should be logged. |


```json
"payload": {
    "destinations": {
      "requestLog": {
        "type" : "file",
        "file" : "requests.log"
      },
      "default": {
        "type" : "file",
        "file" : "recording.log"
      },
      "rec2": {
        "type" : "eventBus",
        "address" : "event/request-test"
      }
    }
}
```


An array called filters of filter entries to specify from what requests the payload should be logged. The filter entries can have the following values:

| Property    | Description                              | 
|:----------- | :--------------------------------------- | 
| url         | A string containing the URL to log. Regex can be used to define a pattern |
| method      | A string containing the HTTP method to log. Regex can be used to define a pattern |
| header name | An arbitrary header name. The request must contain the defined header to be logged |
| reject      | Set to "true" in order to not log the corresponding request. |
| destination | An optional reference to a destination specified in the property destinations. The filtered content will be logged to the referenced destination. |

All filter values inside a filter entry have to match in order to log the request payload. Example of a payload filters configuration:

```json
"payload": {
  "filters": [
    {
      "url": "/gateleen/resources/.*",
      "x-rp-mail": "john.doe@swisspush.org"
    },
    {
      "url": "/gateleen/anotherres/.*"
    },
    {
      "url": "/gateleen/someres/v1/.*"
    },
    {
      "url": "/gateleen/server/tests/res/.*"
    },
    {
      "url": "/gateleen/(?!schemas/|apps/|server/(?!tests/res/.*)|img/).+"
    }
  ]
}
```

Example of a payload filters configuration using the reject property and the method property:

```json
"payload": {
  "filters": [
    {
      "url": "/gateleen/server/test/sub/.*",
      "method": "PUT|POST|DELETE"
    },
    {
      "url": "/gateleen/server/test/.*",
      "reject": "true"
    }
  ]
}
```

Example of a payload filters configuration using destinations to redirect the logging: 
```json
{
  "payload": {
    "destinations": {
      "requestLog": {
        "type" : "file",
        "file" : "requests.log"
      },
      "default": {
        "type" : "file",
        "file" : "recording.log"
      },
      "rec2": {
        "type" : "eventBus",
        "address" : "event/request-test"
      }
    },
    "filters": [
      {
        "url": "/gateleen/audit/log",
        "destination": "requestLog"
      },
      {
        "url": "/gateleen/navigation/.*"
      },
      {
        "url": "/gateleen/trip/.*",
        "destination": "rec2"
      }
    ]
  }
}
```

Logs all PUT, POST and DELETE Requests to /gateleen/server/test/sub/... and below but does not log requests to /gateleen/server/test/...

> <font color="orange">Attention: </font> Be aware of the order you define the payload filter configurations. Define "more" specific URLs before "less" specific URLs!

#### Update logging configuration
Use **_LoggingResourceManager.handleLoggingResource(final HttpServerRequest request)_** method to update the logging configuration.

#### Log requests
To log requests, create a new **_LoggingHandler(LoggingResourceManager loggingResourceManager, HttpServerRequest request)_** and use the **_log()_** method. The LoggingHandler will decide on whether to log the request or not with the guidance of the provided LoggingResourceManager.

Examples of logged request payloads and headers:

```json
{"url":"/gateleen/server/test/r1","method":"GET","statusCode":304,"statusMessage":"Not Modified","request":{"headers":{"x-server-timestamp":"2015-01-21T15:12:21.392+01:00"}},"response":{"headers":{}}}
{"url":"/gateleen/server/test/r1","method":"GET","statusCode":304,"statusMessage":"Not Modified","request":{"headers":{"if-none-match":"adafffea-cc94-4cf0-b138-9420f9c4fa27"}},"response":{"headers":{}}}
{"url":"/gateleen/server/test/r1","method":"GET","statusCode":304,"statusMessage":"Not Modified","request":{"headers":{"connection":"keep-alive","cache-control":"max-age=0","accept":"text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8","user-agent":"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.99 Safari/537.36","accept-encoding":"gzip, deflate, sdch","accept-language":"de-DE,de;q=0.8,en-US;q=0.6,en;q=0.4,fr;q=0.2,it;q=0.2","cookie":"JSESSIONID=ljv80nz7bmfz1wynkwpf5lte5","if-none-match":"adafffea-cc94-4cf0-b138-9420f9c4fa27","x-rp-unique_id":"5edb1d7c7ee93b3dd65779d39171c02c","x-server-timestamp":"2015-01-21T13:49:47.172+01:00","x-rp-unique-id":"5edb1d7c7ee93b3dd65779d39171c02c","host":"localhost:8989","transfer-encoding":"chunked"}},"response":{"headers":{"etag":"adafffea-cc94-4cf0-b138-9420f9c4fa27","content-length":"0"}}}
```
