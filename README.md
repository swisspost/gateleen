# [\_] Gateleen

[![Build Status](https://drone.io/github.com/swisspush/gateleen/status.png)](https://drone.io/github.com/swisspush/gateleen/latest)
[![Maven Central](https://img.shields.io/maven-central/v/org.swisspush.gateleen/gateleen-core.svg)](https://search.maven.org/#search|ga|1|g%3A%22org.swisspush.gateleen%22%20AND%20v%3A%221.0.4%22)

API-Gateway / Middleware library based on Vert.x to build advanced JSON/REST communication servers.

## Getting Started
### Install
* Clone this repository or unzip [archive](https://github.com/swisspush/gateleen/archive/master.zip)
* Install and start Redis
  * Debian/Ubuntu: `apt-get install redis-server`
  * Fedora/RedHat/CentOS: `yum install redis`
  * OS X: `brew install redis`
  * [Windows](https://github.com/MSOpenTech/redis/releases/download/win-2.8.2400/Redis-x64-2.8.2400.zip)
  * [Other](http://redis.io/download)

### Build
You need Java 8 and Maven.
```
cd gateleen
mvn install
```
### Play
The `gateleen-playground` module provides a server example.
```
java -jar gateleen-playground/target/playground.jar
```
It starts on [http://localhost:7012/playground](http://localhost:7012/playground)

The storage is currently empty, that's why you get `404 Not Found`

Create your first resource with
```
curl -X PUT -d '{ "foo": "bar" }' http://localhost:7012/playground/hello/world
```
or any other [REST Client](https://www.google.ch/?q=rest+client)

Now you can see the resource appear in [http://localhost:7012/playground](http://localhost:7012/playground)

The playground module provides a convenient web client for manipulating resources and a basic configuration.

You can push them with
```
cd gateleen-playground
mvn wagon:upload
```
This just PUTs all resources from folder `src/main/resources`

Then go again on [http://localhost:7012/playground](http://localhost:7012/playground)

## Modules

| Module                                                                         | Description                              |
|:---------------------------------------------------------------------------------:| ---------------------------------------- |
| [core](gateleen-core/README_core.md)                                     | HTTP infrastructure and hierarchical resource storage that can be used as cache, intermediate storage for backend-pushed data, user and apps data |
| [delta](gateleen-delta/README_delta.md)                                  | Traffic optimization allowing clients to fetch only data that actually changed from the last time they fetched it  |
| [expansion](gateleen-expansion/README_expansion.md)                      | Traffic optimization allowing clients to fetch a sub-tree of data in one request instead of many requests |
| [hook](gateleen-hook/README_hook.md)                                     | Server push and callback support allowing backends to push data to clients and be notified when data is pushed by clients |
| [integrationtest](gateleen-integrationtest/README_integrationtest.md)    | Test infrastructure |
| [logging](gateleen-logging/README_logging.md)                            | Logs request with configurable filtering for later data analysis |
| [monitoring](gateleen-monitoring/README_monitoring.md)                   | Monitor useful information like number of requests, queue sizes, etc. |
| [packing](gateleen-packing/README_packing.md)                            | Traffic optimization gathering multiple requests in one |
| [player](gateleen-player/README_player.md)                               | Test tool to replay traffic recorded with gateleen-logging for automated tests and troubleshooting |
| [playground](gateleen-playground/README_playground.md)                   | Server example    |
| [qos](gateleen-qos/README_qos.md)                                        | Traffic priorization by rejecting requests to low-priority routes to keep more important features working when backends go down and load increases due to timeouts and retries |
| [queue](gateleen-queue/README_queue.md)                                  | Request queuing to be robust to slow and available backends keeping the client connections short-lived |
| [routing](gateleen-routing/README_routing.md)                            | Configurable URL patterns and method based routing to backends and resource storage |
| [runconfig](gateleen-runconfig/README_runconfig.md)                      | Test infrastructure |
| [scheduler](gateleen-scheduler/README_scheduler.md)                      | Performs planned operations like data cleaning |
| [security](gateleen-security/README_security.md)                         | Fine grained authorization for URL patterns and methods against principal headers  |
| [test](gateleen-test/README_test.md)                                     | Integration tests |
| [user](gateleen-user/README_user.md)                                     | Manages user profile merges user preferences and identity information |
| [validation](gateleen-validation/README_validation.md)                   | Validates data according to JSON schemas  |

## Headers
This is a list of the custom headers used by Gateleen.

| Header             | Description                              | Link to documentation |
|:------------------ | :--------------------------------------- | :---------------------:|
| x-rp-unique-id     | unique id | |
| x-request-id       | id of the request (unique?) | |
| x-self-request     | indicates if the request is triggered by a communication server and the target is the communication server itself | |
| x-rp-grp           | group header | [gateleen-user](gateleen-user/README_user.md) |
| x-roles            | role header | |
| x-delta            | used by the delta parameter to mark a delta request | [gateleen-delta](gateleen-delta/README_delta.md) |
| x-delta-backend    | used by the delta parameter as a marker header to know that we should let the request continue to the router | [gateleen-delta](gateleen-delta/README_delta.md) |
| x-expire-After     | indicates how long (in seconds) a request is 'valid' before it expires | |
| x-hooked           | used by the HookHandler to indicate the original hook request as hooked | [gateleen-hook](gateleen-hook/README_hook.md) |
| x-pack-size        | indicates if a request is packed | [gateleen-packing](gateleen-packing/README_packing.md) |
| x-client-timestamp | timestamp of the client while sending the request | |
| x-server-timestamp | timestamp of the server while sending the request | |
| x-queue            | indicates a queued request | [gateleen-queue](gateleen-queue/README_queue.md) |
| x-duplicate-check  | indicates if a request is a duplicate or not | |
| x-on-behalf-of     | indicates the user the request originally was made from. This header is used when the request of a user is sent by another user because it couldn't be sent at that time (offline) | |
| x-user-\<pattern\> | used for the user profile | |
| x-rp-deviceid      | used to check if device is authorized | |
| x-rp-usr           | used to check if user is authorized | [gateleen-user](gateleen-user/README_user.md) |
| x-rp-lang          | used to specify the language of the user profile | [gateleen-user](gateleen-user/README_user.md) |
| x-valid            | indicates if a request is to be validated or not | [gateleen-validation](gateleen-validation/README_validation.md) |
| x-sync             |  | |
| x-log              |  | |
| x-service          | contains the name of the service, which created the request | |

## Performance Tuning
* The amount of max open files per process should be at least 16384. You can check your max open files per process with this command `cat /proc/<process id>/limits`.
* The amount of open files depends on the amount of open Http Requests, which are holding a tcp connection which are holding a file handle.
* The important number to control the open Http Requests is the pool size of the Http Client. The higher the pool size the higher the open files.

## Dependencies
* [vertx-rest-storage](https://github.com/swisspush/vertx-rest-storage) at least release [v2.0.2](https://github.com/swisspush/vertx-rest-storage/releases/tag/v2.0.2)
* [vertx-redisques](https://github.com/swisspush/vertx-redisques) at least release [v.2.0.1](https://github.com/swisspush/vertx-redisques/releases/tag/2.0.1)
