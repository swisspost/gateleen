# gateleen-routing

## Schema validation
Updating the routing rules requires a validation against a schema to be positive. Check the schema [gateleen_routing_schema_routing_rules](src/main/resources/gateleen_routing_schema_routing_rules)

## Routing configuration
To configure the routing, the _ConfigurationResourceManager_ and the path to the configuration resource have to be configured in the _Router_ by calling:

```java
router.enableRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config")
```
The routing configuration is a json resource defining the limit for the maximum request routings (hops) called **_request.hops.limit_** and OAuth configuration called **_authConfigs_**.
Check the schema [gateleen_routing_schema_config](src/main/resources/gateleen_routing_schema_config)

Example:

```json
{
  "request.hops.limit": 10,
  "authConfigs": {
    "demo-config-1": {
      "flowType": "CLIENT",
      "clientId": "543567344a5005",
      "clientSecret": "212636219b3a5b",
      "site": "https://api.swisspost.ch",
      "tokenPath": "/OAuth/token",
      "authPath": "/OAuth/authorization",
      "scopes": [
        "APIM_SANDBOX_RESOURCE_SERVER_READ"
      ],
      "supportedGrantTypes": [
        "client_credentials"
      ]
    }
  }
}
```

### Request hop validation
To protect gateleen from routing rules configured to result in an endless loop, the request hop validation feature has been introduced.

When the request hop validation is activated, a **_x-hops_** request header will be added to the request before routing. Initially, this header will receive the value 1.
When the **_x-hops_** header already exists (in alredy routed requests for example), the value will be increased by 1.

Before the request is routed, the **_x-hops_** header value will be checked against a configurable limit. When the limit is exceeded, the request will
be answered with a http status code _500_ and a http status message: _Request hops limit exceeded_

### OAuth2 authentication
To activate OAuth2 authentication for forwarding requests, a configuration with the required properties like _flowType_, _clientId_, _clientSecret_, etc. can be provided. See [vertx-auth-oauth2 documentation](https://vertx.io/docs/vertx-auth-oauth2/java/) for more
information. The name of the configuration (_demo-config-1_ in the example) is used in the routing rule.

Example:

```json
{
  "/gateleen/server/rule/1/(.*)": {
    "oAuthId": "demo-config-1",
    "url": "http://localhost/some/backend/path/$1"
  }
}
```

## Log routing rule changes
To log the payload of changes to the routing rules, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the routing rules, make sure the url to the routing rules is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/routing/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [Router](src/main/java/org/swisspush/gateleen/routing/Router.java) by calling
```java
router.enableResourceLogging(true);
```
## Use CustomHttpResponseHandler
The [CustomHttpResponseHandler](src/main/java/org/swisspush/gateleen/routing/CustomHttpResponseHandler.java) can be used together with simple routing rules to respond with a configurable
http status code.

Having a configured rootPath of
 
> _/gateleen/server/return-http-error_

you can add the following routing rule to correctly respond every request on

> _/gateleen/server/unreachable/service_

with a http status code 503
```json
{
    "/gateleen/server/unreachable/service": {
        "path": "/gateleen/server/return-http-error/503",
        "methods": [
          "GET"
        ]
    }
}
```

## Apply routing rules based on request headers
Routing rules are applied to requests based on the request url and the http method. By defining the _headersFilter_ property, you are able to also apply a routing rule based on request headers.

Examples:
```json
{
    "/gateleen/server/rule/1/(.*)": {
        "headersFilter": "x-foo.*",
        "url": "http://localhost/some/backend/path/$1"
    },
    "/gateleen/server/rule/2/(.*)": {
        "headersFilter": "x-foo: (A|B|C)",
        "url": "http://localhost/some/backend/path/$1"
    },
    "/gateleen/server/rule/3/(.*)": {
        "headersFilter": "x-foo: [0-9]{3}",
        "url": "http://localhost/some/backend/path/$1"
    }
}
```
Each request header entry is validated in the format `<KEY>: <VALUE>`, so you are able to filter for request header names and values.