# gateleen-core

## Storage
### Resource expiration
Using the _X-Expire-After_ request header, the expiration of a resource can be defined.

#### Creating resources
To define the expiration of a resource, add the _X-Expire-After_ request header to the PUT request.
> X-Expire-After: \<seconds\>

| header value | Description |
|:------------:| :-----------|
| -1 | The resource never expires |
| 0 | The resource expires immediately |
| \> 0 | The resource will expire in the configured amount of seconds |

> <font color="blue">Information: </font> When no _X-Expire-After_ request header is provided, the resource will **never** expire

#### Consuming resources
To consume (GET) resources, the client doesn't have to provide any parameters.
When a resource is expired, it will no longer be returned and a **404 Not Found** error will be returned instead.

## CORS Handling
To enable the headers for Cross-Origin Resource Sharing (CORS) requests, you have to set the following property:
> org.swisspush.gateleen.addcorsheaders=true

See [CORSHandler](src/main/java/org/swisspush/gateleen/core/cors/CORSHandler.java) for more information