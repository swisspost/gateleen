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
To add more properties but still have control over what properties are allowed in the profile the **UserProfileConfiguration** was introduced.

#### UserProfileConfiguration
The **UserProfileConfiguration** is a configuration object that can be used to define the properties that are allowed in the user profile. 
It can be used to restrict the properties that can be set in the user profile and to define default values for certain properties.

Use `UserProfileConfiguration.create()` for a convenient way to create a new instance of the **UserProfileConfiguration**.

Example:

```java
final UserProfileConfiguration.ProfileProperty usernameConfig = UserProfileConfiguration.ProfileProperty
        .with("x-rp-usr", "username").
        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID).
        setOptional(false).build();
final UserProfileConfiguration.ProfileProperty fullNameConfig = UserProfileConfiguration.ProfileProperty
        .with("x-rp-displayName", "fullName").
        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).
        setOptional(false).build();

UserProfileConfiguration.create()
        .userProfileUriPattern(SERVER_ROOT + "/users/v1/([^/]+)/profile")
        .roleProfilesRoot(SERVER_ROOT + "/roles/v1/")
        .rolePattern(ROLE_PATTERN)
        .addAllowedProfileProperties(ArrayUtils.addAll("x-foo", "x-bar", "x-baz"))
        .removeNotAllowedProfileProperties(true)
        .addProfileProperty(usernameConfig)
        .addProfileProperty(fullNameConfig)
        .build();
```

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