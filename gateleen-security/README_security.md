# gateleen-security

#### Schema validation
Updating the acl (Access Control List) resources requires a validation against a schema to be positive. Check the schema [gateleen_security_schema_acl](src/main/resources/gateleen_security_schema_acl)

#### Log acl configuration changes
To log the payload of changes to the acl configurations, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the acl configurations, make sure the url to the acl configuration is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/security/v1/acls/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [Authorizer](src/main/java/org/swisspush/gateleen/security/authorization/Authorizer.java) by calling
```java
authorizer.enableResourceLogging(true);
```

#### RoleMapper
If there are many ACL groups with equal routing setup, it is possible to define RoleMappers which do map the roles to a single ACL role.

The rolemapper object is by default expected at _base_/server/security/v1/rolemapper

Example - You have different ACL groups for a dedicated system domain and each of them would have the same permission setup:
````
  acl-domain-admin
  acl-domain-manager
  acl-domain-user
````

In this case you only define the acl "acl-domain" within your gateleen security configuration and the corresponding RoleMapper:

```
{
    "rolemappers": [{
      "pattern":"acl-domain-.*",
      "role":"acl-domain",
      "keeporiginal":false
      }
    ]
 }
 ```
 
Any request with a user group containing 'acl-domain-' will match the defined rolemapper and result in the defined resulting role acl-domain.

Note the additional keeporiginal flag which defines if the original role which matched the mapper must be kept in the list of roles or not.

Note: The rolemapper object is validated against the gateleen_security_schema_rolemapper json schema