# gateleen-delta
With the delta feature a client is able to track the changes in a collection and load only the difference (delta) between the data already downloaded 
and the data in the server storage.

Having large collections, this feature is useful to minimize the the amount of transferred data. 

## PUT resources with the delta URL param
To enable the delta feature for a resource, the X-Delta header must be provided in the PUT request.
> X-Delta: auto

This header tells gateleen to assign an \<update_id\> to this resource. On every further PUT request (with X-Delta: auto) the \<update_id\> will be updated.

**Important:** The request header _X-Expire-After_ ([see chapter Headers](../README.md)) should also be provided. This is cause the delta metadata is not hold together with the rest-storage data.
When no positive _X-Expire-After_ value is provided, the delta information is lost after a short time (eg. 20 days).
This leads to the situation, where a request with delta information can lead to a wrong result, because the resources with no delta information, will also be returned. 

## GET resources with the delta URL param
To enable the delta feature for consuming resources, the _delta_ URL param is used.
> GET /gateleen/resources/res**?delta=\<update_id\>**

| _delta_ param value   | Description                              | delta information in response header |
|:---------------------:| :--------------------------------------- | :-----------------------------------:|
| | Requests without the _delta_ parameter will return the whole collection | no |
| 0 | Requests with the _delta_ parameter set to 0 (zero) will return the whole collection | yes |
| \<update_id\> | Requests with the _delta_ parameter set to an \<update_id\> (greater than zero) will only return the resources updated after this \<update_id\> | yes |

### Delta information in response header
When delta information is returned in the response, it's done with the X-Delta header.
> X-Delta: \<update_id\>

This header value contains the \<update_id\> of the last updated resource in the returned collection.

## Usage
The following diagram shows the usage of the delta feature having some Backend putting resources (Item 1, Item 2, ..., Item 7) in a sequence in the storage:

```
|'''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''|
|         +--------+                                         +--------+   |
|         |Client 1|                                         |Client 2|   |
|         +--------+                                         +------+-+   |
|          |       |                                          |     |     |
|          |       |                                          |     |     |
|   GET delta=3    |                                          |     |     |
|   Return delta=7 |    +-----------------------+             |     |     |
|          |       |    |Gateleen KeyValue Store|             |     |     |
|          |       |    |-----------------------|             |     |     |
|          |       |    |    +-+ Item 7 +-+ <---|-4------------     |     |
|          |       --3--|--> | | Item 6 +-+     |    GET delta=5    |     |
|          |            |    | | Item 5 +-+     |    Return delta=7 |     |
|          |            |    +-+ Item 4 | |     |                   |     |
|          |            |    +-+ Item 3 | | <-----2------------------     |
|          ----------1-----> | | Item 2 | |     |        GET delta=0      |
|  GET delta=0          |    +-+ Item 1 +-+     |        Return delta=5   |
|  Return delta=3       +-----------------------+                         |
|                                 /|\                                     |
|                                  |  PUT Sequence                        |
|                              +-------+                                  |
|                              |Backend|                                  |
|                              +-------+                                  |
`-------------------------------------------------------------------------'
```
| Step | Description | 
|:----:| :---------- |
| 1 | Client 1 invokes a request to the resource storage with delta=0 which means it would like to retrieve everything currently available below the requested update ID within the servers resource storage. The returned delta value is 3 |
| 2 | Client 2 invokes as well the same request to retrieve all available information. The returned delta value is 5 |
| 3 | Client 1 retrieves all resources which are newer than it's last returned delta=3. The returned delta value is 7 |
| 4 | Client 2 retrieves all resources which are newer than it's last returned delta=5. The returned delta value is 7 |

## Delta requests handled by the backend
The delta feature can be implemented by backends themselves. To pass those requests directly to the backend without storing the \<update_id\> in gateleen, the following request header can be provided:
> x-delta-backend: true