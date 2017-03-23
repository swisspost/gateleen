# gateleen-user

### User profile
The user profile containing user specific information is stored on the server. A basic user profile with the minimal information looks like this:

**/gateleen/server/users/v1/dummyUser/profile**
```json
{
  "lang": "de",
  "mail": "unknown",
  "department": "unknown",
  "personalNumber": "unknown"
}
```
To add more properties but still have control over what properties are allowed in the profile the **allowedProfileProperties** resource was introduced.

##### AllowedProfileProperties resource
When no allowedProfileProperties resource is defined, the default allowed properties are allowed only. Other properties than the allowed will not be saved to the profile. 
These properties are *username, personalNumber, department, lang, mail* when the standard constructor of the **UserProfileHandler** is used.

**Constructor of UserProfileHandler**
```java
public UserProfileHandler(Vertx vertx, ResourceStorage storage, String serProfileUriPattern, String roleProfilesRoot, String rolePattern) {
    this(vertx, storage, userProfileUriPattern, roleProfilesRoot, null, Arrays.asList("username", "personalNumber", "mail", "department", "lang"), rolePattern);
}
```

To add additional allowed properties you have to create the **allowedProfileProperties** resource like this:
```json
{
  "properties": [
    "username",
    "personalNumber",
    "mail",
    "department",
    "lang",
    "tour",
    "zip",
    "context",
    "contextIsDefault",
    "passkeyChanged",
    "volumeBeep",
    "torchMode",
    "spn"
  ]
}
```

To use the allowedProfileProperties resource you have to define the path of the resource in the constructor of the UserProfileHandler. See example below:
```java
new UserProfileHandler(vertx, storage, SERVER_ROOT + "/users/v1/([^/]+)/profile", SERVER_ROOT + "/roles/v1/", SERVER_ROOT + "/users/v1/allowedProfileProperties",
                        Arrays.asList("username", "personalNumber", "mail", "department", "lang"), ROLE_PATTERN);
```

You can also define the default allowed properties using this constructor. Default allowed properties can not be removed from the profile when forgotten to add to the allowedProfileProperties resource.
So these properties are more "secured" when defining as default.

### Log user profile and role profile changes
To log the payload of changes to the user profiles and role profiles, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the user profiles and role profiles, make sure the url to these profiles is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/users/v1/.*",
        "method": "PUT"
      },
      {
        "url": "/playground/server/roles/v1/.*",
        "method": "PUT"
      }      
    ]
  }
}
```
Also you have to enable the logging on the [UserProfileHandler](src/main/java/org/swisspush/gateleen/user/UserProfileHandler.java) and the [RoleProfileHandler](src/main/java/org/swisspush/gateleen/user/RoleProfileHandler.java) by calling
```java
userProfileHandler.enableResourceLogging(true);
roleProfileHandler.enableResourceLogging(true);
```