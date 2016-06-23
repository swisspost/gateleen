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

### Scenario regularExpand
**Description:**

Making _regular_ expand requests to the collection prepared in scenario _prepareExpandResources_. _Regular_ expand requests make another request for each resource in the collection.

**Setup:**

Taking values from real world applications, Gateleen should be able to server 42'000 requests over a duration of 15 minutes. The tests are configured to use 75% of this load. So the setup is set to:
> 42'000 requests during 15 minute => 47 req/s * 75% = 35 req/s

### Scenario storageExpand
**Description:**

Making _storage_ expand requests to the collection prepared in scenario _prepareExpandResources_. _Storage_ expand requests make the expansion directly in the storage.

**Setup:**

Taking values from real world applications, Gateleen should be able to server 42'000 requests over a duration of 20 minutes. The tests are configured to use 75% of this load. So the setup is set to:
> 42'000 requests during 20 minute => 35 req/s * 75% = 26 reg/s

### Scenario storageOperations
Making _CRUD_ storage requests. The requests include PUT, GET and DELETE requests for json values.

### Scenario enqueueRequests
**Description:**

Making queued PUT requests.

**Setup:**

Having 10 users per second over 2 minutes making each 50 requests to a queue result in the following load.
> 1200 different queueus with 50 queue entries each => 60'000 requests total

## Test scenario environment
The above described scenarios have been tested on the following hardware:

**2 cores** with the specifications listed below:
```
vendor_id       : GenuineIntel
cpu family      : 6
model           : 45
model name      : Intel(R) Xeon(R) CPU E5-4650L 0 @ 2.60GHz
stepping        : 2
microcode       : 0x710
cpu MHz         : 2599.999
cache size      : 20480 KB
physical id     : 1
siblings        : 4
core id         : 3
cpu cores       : 4
apicid          : 7
initial apicid  : 7
fpu             : yes
fpu_exception   : yes
cpuid level     : 13
wp              : yes
flags           : fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts mmx fxsr sse sse2 ss ht syscall nx rdtscp lm constant_tsc arch_perfmon pebs bts nopl xtopology tsc_reliable nonstop_tsc aperfmperf pni pclmulqdq ssse3 cx16 pcid sse4_1 sse4_2 x2apic popcnt aes xsave avx hypervisor lahf_lm ida arat epb pln pts dtherm
bogomips        : 5199.99
clflush size    : 64
cache_alignment : 64
address sizes   : 40 bits physical, 48 bits virtual
```