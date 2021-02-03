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
| headers  |  **   | Allows to specify extra headers for one specific delegate request. Will add only the defined headers to the delegate request |
| dynamicHeaders  |  **   | Allows to specify headers for one specific delegate request using header manipulations: set, remove, complete (set-if-absent) and override (set-if-present). Will add all headers from original request (unless the 'remove' function is applied) to the delegate request |
| uri      |  x  | The URI against which one the delegate request will be performed. |
| method   |  x  | The HTTP method used to perform the given delegate request. |
| payload  |  (x)*  | The json content of the payload required for processing the delegate request. The payload is dependent on the wished request and can differ from request to request. |
| transform  |  (x)*  | The json-to-json transformation spec defining how to transform the payload of the delegate execution request to the output. See [Jolt library](https://github.com/bazaarvoice/jolt) for more information. |

\* Exactly one of _payload_ or _transform_ is required
\** At most one of _headers_ or _dynamicHeaders_ is allowed

##### Concrete Example
> ```PUT /gateleen/server/admin/v1/delegates/resource-zip-copy/definition```

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

### Payload handling types
As documented in the _Delegate definition_ section, there are two types of payload handling available. The JSON schema ensures that excatly one type is configured.

#### Payload definition
The payload definition is made for each defined request and uses the _payload_ property. The content of the _payload_ property must be a valid json object and contains the
payload which will be used in the delegated requests.
> For this payload handling type, no (or no valid json) payload is required for the delegate execution request, since the payload is not used at all.

Example:
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

#### Payload transformation
The payload transformation is made for each defined request and uses the _transform_ or _transformWithMetadata_ property. The content of the this property must be a valid json-to-json transformation
specification as documented in the [Jolt library](https://github.com/bazaarvoice/jolt)

Example:
```
{
	"methods": [
		"PUT"
	],
	"pattern": ".*/([^/]+.*)",
	"requests": [{
		"method": "PUT",
		"uri": "/playground/server/tests/delegate",
		"transform": [{
			"operation": "shift",
			"spec": {
				"@": "records[0].value"
			}
		}]
	}]
}
```
The _Jolt library_ defines the following out-of-the-box transforms which can be extended by implementing a custom _Java Transform_:
```
shift       : copy data from the input tree and put it the output tree
default     : apply default values to the tree
remove      : remove data from the tree
sort        : sort the Map key values alphabetically ( for debugging and human readability )
cardinality : "fix" the cardinality of input data.  Eg, the "urls" element is usually a List, but if there is only one, then it is a String 
```

Having the _transform_ spec defined as in the example above, the following input
```
{
	"foo": "bar"
}
```
would be transformed into
```
{
  "records" : [ {
    "value" : {
      "foo" : "bar"
    }
  } ]
}
```
Checkout [Jolt Transform Demo](http://jolt-demo.appspot.com/#inception) for more examples and a handy tool to define the transformation specifications.

##### Payload transformation with Metadata
As enhancement to the payload transformation with the _transform_ property, the payload transformation with metadata was introduced using the _transformWithMetadata_ property.

Example:
```
{
	"methods": [
		"PUT"
	],
	"pattern": ".*/([^/]+.*)",
	"requests": [{
		"method": "PUT",
		"uri": "/playground/server/tests/delegate",
		"transformWithMetadata": [{
			"operation": "shift",
			"spec": {
				"urlParts": {
					"1": "records[0].value.metadata.techId"
				},
				"headers": {
					"x-abc": "records[0].value.metadata.x-abc"
				},
				"payload": {
					"@": "records[0].value.dummy",
					"node": {
						"id": ["records[0].key", "records[0].value.&"]
					}
				}
			}
		}]
	}]
}
```

The JSON input which is accessible in the transformation specification contains the following properties:

| Property | Type | Description | 
|---|:-:|---|
| urlParts  |  array  | A string array holding all groups found when applying the pattern regex to the request url |
| headers  |  object  | An object holding key-value pairs of all provided request headers |
| payload |  object  | An object holding the payload of the original request |

Example:
```
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
			"id": "11",
			"type": "x",
			"isLeaf": false
		}
	}
}
```

##### Identity transformation
The transformation specification defining the _identity transformation_ or _identity function_ (input equals output) looks like this:
```
[{
	"operation": "shift",
	"spec": {
		"@": ""
	}
}]
```

### Delegate execution
To execute a delegate it is necessary to perform a HTTP request (method is irrelevant and handled by the methods definition of the delegate) directly on the delegate execution "resource". If the incoming HTTP method matches the given delegate definition, the delegate requests will be performed. For the delegate it is irrelevant if a body is passed or not. It will be ignored, because it’s not needed for the delegates to be performed. 

> ```PUT /<Server_Root>/<Delegate_Uri>/<Name_Of_Delegate>/execution/<passed_resource_path>```

##### Concrete Example
> ```PUT /gateleen/server/admin/v1/delegates/resource-zip-copy/execution/resource1```

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
        "url": "/playground/server/admin/v1/delegates/.*",
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
