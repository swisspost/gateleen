# gateleen-performancetest
Execute performance tests using [Gatling load testing framework](http://gatling.io/#/)

## Configuration
The following configuration values can be used


| Name       | Default value | Description |
| :----------|:------------- | :----- |
| targetHost | localhost     | The host where the tests should be executed against |
| targetPort | 7012          | The port of the target host where the tests should be executed against |

## Prepare server resources
To have the correct server resources like routing rules and acls used for the Gatling tests, the following gradle task should be applied **before** the tests:
### Windows
```
gradlew.bat uploadStaticFiles -DtargetHost=mytargethost -DtargetPort=1234
```

### Linux/Unix
```
./gradlew uploadStaticFiles -DtargetHost=mytargethost -DtargetPort=1234
```

## Executing Gatling simulations
The Gatling simulations can be started with the following command:
### Windows
```
gradlew.bat gatling -DtargetHost=mytargethost -DtargetPort=1234
```

### Linux/Unix
```
./gradlew gatling -DtargetHost=mytargethost -DtargetPort=1234
```