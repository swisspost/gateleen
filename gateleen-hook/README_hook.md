# gateleen-hook

This feature allows you to set either dynamical generated routes or listeners called hooks to a specific resource or a collection.
 
Two types of this hooks exists:
* routes
* listeners

## General configuration
During the instantiation of the [HookHandler](src/main/java/org/swisspush/gateleen/hook/HookHandler.java) you have the possibility to specify the collection where your hooks will be saved in the storage. To do so, you have to set the parameter _hookRootUri_ in the constructor.

## Schema validation
Adding hooks requires a validation against a schema to be positive. Check the schema [gateleen_hooking_schema_hook](src/main/resources/gateleen_hooking_schema_hook) 

## Route - Hooks
> <font color="orange"><b>Attention:</b> </font>A route directs all communication from a specific resource to another destination. Per resource may only exist one route a time.

### Usage
> <font color="skyblue"><b>Information:</b> </font>The suffix _**/_hooks/route**_ always indicates a route registration. The HookHandler will use this suffix to identify either a registration or an unregistration of a route hook.

**Payload**

| Property          | Required | Description                              | 
|:----------------- | :------: | :--------------------------------------- | 
| destination       | yes | A valid URL (absolute) where the requests should be redirected to. |
| methods           | no  | An array of valid HTTP methods (PUT, GET, DELETE, ...) to define for which requests the redirection should be performed. As a default all requests will be redirected. |
| *expireAfter*     | no  | *DEPRECATED - Hooks don't manipulate or set the x-expire-after header any more. Use HeaderFunctions instead* |
| staticHeaders     | no  | (*deprecated - use headers*) This property allows you to set static headers passed down to every request. The defined static headers will overwrite given ones! |
| headers           | no  | array of request header manipulations: set, remove, complete (set-if-absent) and override (set-if-present)|
| collection        | no  | This property specifies if the given URL is a resource or a collection. If this property is not set, the default is true (collection). |
| listable          | no  | This property specifies if a route is listed if a GET is performed on its parent or not. The default is false or set by the value passed during the initialization. | 

> <font color="orange"><b>Attention:</b> </font>A route has a default expiration time of **1 hour**. After this time the route will expire and be removed from the storage, as well as the HookHandler.<br />
To update / refresh a route, simply perform another registration.<br />
To change the expiration time of a route, just pass a _X-Expire-After_ header with the registration PUT request. 



#### Add a route
```json
PUT /<url>/<resource>/_hooks/route
{
    "destination": "http://<url>/<target>",
    "methods": []
}
```
#### Remove a route
```json
DELETE /<url>/<resource>/_hooks/route
```


#### Example:
The following example will show you, how to set a route for a specific resource / collection. 

> <font color="green"><b>Assumption:</b> </font>We’d like to set a dynamic route for the resource _/gateleen/example/resource_. The target destination should be _/gateleen/example/othertarget_. 

To do so, we have to perform the following steps:


##### Register the route hook.
```json
PUT http://myserver:7012/gateleen/example/resource/_hooks/route
{
    "destination": "http://myserver:7012/gateleen/example/othertarget",
    "methods": []
}
```
 

##### Use the hook
```json
DELETE http://myserver:7012/gateleen/example/resource/anExample
```

This request will be rerouted to:
```json
DELETE http://myserver:7012/gateleen/example/othertarget/anExample
```

##### Deleting the hook
```json
 DELETE http://myserver:7012/gateleen/example/resource/_hooks/route
```


#### Create a listable route
Normally a created rout will not be listed, if their parent collection is requested. 
```json
 PUT http://myserver:7012/gateleen/example/resource/_hooks/route
 {
     "destination": "http://myserver:7012/gateleen/example/othertarget"
}
```
The request will lead to a 404.
```json
GET http://myserver:7012/gateleen/example/
```

In order to be able to list one or more routes, we have to tell the HookHandler, that we wish the rout to be listable. 

```json
 PUT http://myserver:7012/gateleen/example/resource/_hooks/route
 {
     "destination": "http://myserver:7012/gateleen/example/othertarget",
     "methods": [],
     "listable" : true,
     "collection" : true
 }
```
Now the request will lead to:
```json
GET http://myserver:7012/gateleen/example/
{
    "example" : [
	    "resource"
    ]
}
```





## Listener - Hooks
> <font color="orange"><b>Attention:</b> </font>A listener registers an additional destination for a resource, which also will get a copy of the original request. You may registers as many listeners per resource as you wish. Requests forwarded to a listener are always enqueued and delivered as soon as the target destination is available.  

### Usage
> <font color="skyblue"><b>Information:</b> </font>The suffix _**/_hooks/listeners/http/\<id\>**_ always indicates a route registration. The HookHandler will use this suffix to identify either a registration or an unregistration of a listener hook.

**Payload**

| Property          | Required | Description                              | 
|:----------------- | :------: | :--------------------------------------- | 
| destination       | yes | A valid URL (relative) where the requests should be redirected to. |
| methods           | no  | An array of HTTP methods (namely: GET, HEAD, PUT, POST, DELETE, OPTIONS and PATCH) to define for which requests the redirection should be performed. As a default all requests will be redirected. Requests with not whitelisted methods will not be redirected and get dropped instead. |
| *expireAfter*     | no  | *DEPRECATED - Hooks don't manipulate or set the x-expire-after header any more. Use HeaderFunctions instead* |
| queueExpireAfter  | no  | A copied and forwarded request to a listener is always putted into a queue. To ensure to have an expiration time for each request within the given listener queue, you can set a default value (in seconds) with this property. This way you can prevent a queue overflow. The property will only be used for requests, which doesn’t have a _x-queue-expire-after_ header. |
| staticHeaders     | no  | (*deprecated - use headers*) This property allows you to set static headers passed down to every request. The defined static headers will overwrite given ones! |
| headers           | no  | array of request header manipulations: set, remove, complete (set-if-absent) and override (set-if-present)|
| filter            | no  | This property allows you to refine the requests with a regular expression, which you want to receive for the given destination. |
| type              | no  | Default: before <br> This property allows you to set, if the request to a listener will be sent before or after the original request was performed.<br /> <br /> The valid settings are: <br /> after => request will be forwarded to listener after the original request was performed <br /><br />before => (default) request will be forwarded to a listener before the original request was performed <br /> <br /> This can be useful if you want to use your listeners with the delegate feature and expect a request to be already executed as soon as you execute a delegate. |  
| fullUrl           | no  | Default: false <br> <br /> Defines whether the hook forwards using the full initial url or only the appendix <br/><br/> Example: <br/><br/> hooked url = http://a/b/c <br/> request.uri() = http://a/b/c/d/e.x <br/> url appendix = /d/e.x |
| queueingStrategy  | no  | Default: DefaultQueueingStrategy <br> <br /> Configures the 'queueing' behaviour of the HookHandler. See chapter _QueueingStrategy_ for detailed information. |
                                                 
> <font color="orange"><b>Attention:</b> </font>A listener has a default expiration time of **30 seconds**. After this time the listener will expire and be removed from the storage, as well as the HookHandler.<br />
To update / refresh a listener, simply perform another registration.<br />
To change the expiration time of a listener, just pass a _X-Expire-After_ header with the registration PUT request. 
 
#### Add a listener
```json
PUT http://myserver7012/gateleen/everything/_hooks/listeners/http/myexample
{
    "methods": [
        "PUT"
    ],
    "destination": "/gateleen/example/thePosition",
    "filter": "/gateleen/everything/.*/position.*",
    "headers": [
        { "header":"X-Expire-After", "value":"3600", mode:"complete"}
    ]    
}
```

#### Remove a listener
```json
DELETE http://myserver7012/gateleen/everything/_hooks/listeners/http/myexample
```

#### QueueingStrategy
The _QueueingStrategy_ can be configured for listeners only and defines the 'queueing' behaviour. The following strategies are available.

##### DefaultQueueingStrategy
The _DefaultQueueingStrategy_ does not change anything in the 'queueing' behaviour. Requests are enqueued without any modification. This strategy is used when no (or no valid) QueueingStrategy is configured.

##### DiscardPayloadQueueingStrategy
The _DiscardPayloadQueueingStrategy_ removes the payload from the request before enqueueing. When a _Content-Length_ header is provided, the value will be changed to 0.
 
To activate this strategy, the following configuration has to be added when adding a listener:
```json
{
    "queueingStrategy": {
        "type": "discardPayload"
    }    
}
```

##### ReducedPropagationQueueingStrategy
The _ReducedPropagationQueueingStrategy_ is used to reduce the amount of hooked resource changes by only propagating 1
resource change over a configurable amount of time (_intervalMs_). Typically this strategy is used to not overwhelm a client with
thousands of hooked resource changes. As in the _DiscardPayloadQueueingStrategy_, the payload will be removed before enqueueing.

To activate this strategy, the following configuration has to be added when adding a listener:
```json
{
    "queueingStrategy": {
        "type": "reducedPropagation",
        "intervalMs": 60000
    }    
}
```
The _intervalMs_ defines the amount of time in milliseconds to wait before propagate a single resource change.

## Log hook registration changes
To log the payload of changes to the hook registrations, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the hook registration changes, make sure the url to the hook registrations is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/.*/_hooks/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [HookHandler](src/main/java/org/swisspush/gateleen/hook/HookHandler.java) by calling
```java
hookHandler.enableResourceLogging(true);
```









