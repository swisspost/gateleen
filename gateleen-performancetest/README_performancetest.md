# gateleen-performancetest
Execute performance tests using [Gatling load testing framework](http://gatling.io/#/)

## Configuration
The following configuration values are required


| Name       | Description |
| :----------| :----- |
| targetHost | The host where the tests should be executed against |
| targetPort | The port of the target host where the tests should be executed against |

## Prepare server resources
To have the correct server resources like routing rules and acls used for the Gatling tests, the following gradle task should be applied **before** the tests:
### Windows
```
gradlew.bat uploadStaticFiles -PtargetHost=mytargethost -PtargetPort=1234
```

### Linux/Unix
```
./gradlew uploadStaticFiles -PtargetHost=mytargethost -PtargetPort=1234
```

## Executing Gatling simulations
The Gatling simulations can be started with the following command:
### Windows
```
gradlew.bat gatling -PtargetHost=mytargethost -PtargetPort=1234
```

### Linux/Unix
```
./gradlew gatling -PtargetHost=mytargethost -PtargetPort=1234
```

## Test scenarios
The following scenarios described below are implmented in [GateleenPerformanceTestSimulation.scala](src/test/scala/gatling/simulations/GateleenPerformanceTestSimulation.scala)

### Scenario prepareExpandResources
This scenario is used to prepare the resources for the tests testing gateleens _Expansion_ feature. A single "user" writes 120 json resources to a collection,
which later can be requested with the expand parameter.