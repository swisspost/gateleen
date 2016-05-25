# gateleen-performancetest
Execute performance tests using [Gatling load testing framework](http://gatling.io/#/)

## Configuration
The following configuration values can be used


| Name       | Default value | Description |
| :----------|:------------- | :----- |
| targetHost | localhost     | The host where the tests should be executed against |
| targetPort | 7012          | The port of the target host where the tests should be executed against |

## Usage
### Windows
 > gradlew.bat gatling -DtargetHost=mytargethost -DtargetPort=1234

### Linux/Unix
 > ./gradlew gatling -DtargetHost=mytargethost -DtargetPort=1234