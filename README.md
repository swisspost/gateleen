# [_] Gateleen

Middleware library based on Vert.x to build advanced JSON/REST communication servers

## Components
The following tables lists all the gateleen components available.

| Component                                                                         | Description                              |
|:---------------------------------------------------------------------------------:| ---------------------------------------- |
| [gateleen-core](gateleen-core/README_core.md)                                     | Add short description for this component |
| [gateleen-delta](gateleen-delta/README_delta.md)                                  | Add short description for this component |
| [gateleen-expansion](gateleen-expansion/README_expansion.md)                      | Add short description for this component |
| [gateleen-hook](gateleen-hook/README_hook.md)                                     | Add short description for this component |
| [gateleen-integrationtest](gateleen-integrationtest/README_integrationtest.md)    | Add short description for this component |
| [gateleen-packing](gateleen-packing/README_packing.md)                            | Add short description for this component |
| [gateleen-player](gateleen-player/README_player.md)                               | Add short description for this component |
| [gateleen-qos](gateleen-qos/README_qos.md)                                        | Add short description for this component |
| [gateleen-queue](gateleen-queue/README_queue.md)                                  | Add short description for this component |
| [gateleen-routing](gateleen-routing/README_routing.md)                            | Add short description for this component |
| [gateleen-runconfig](gateleen-runconfig/README_runconfig.md)                      | Add short description for this component |
| [gateleen-scheduler](gateleen-scheduler/README_scheduler.md)                      | Add short description for this component |
| [gateleen-security](gateleen-security/README_security.md)                         | Add short description for this component |
| [gateleen-test](gateleen-test/README_test.md)                                     | Add short description for this component |
| [gateleen-user](gateleen-user/README_user.md)                                     | Add short description for this component |
| [gateleen-validation](gateleen-validation/README_validation.md)                   | Add short description for this component |

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