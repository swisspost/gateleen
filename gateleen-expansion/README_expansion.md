# gateleen-expansion

### Parameter: expand=x
#### Usage
> http://<url>/collection?expand=x

x can be a value from 1 to infinity. It represents the maximum recursion level and is limited by the following properties:
* max.expansion.level.soft
* max.expansion.level.hard
* max.expansion.subrequests

##### max.expansion.level.soft
The _max.expansion.level.soft_ property (default value = _Integer.MAX_VALUE_) defines a soft limit for the maximum expansion level. A soft limit means that the expand request will only be expanded
to the _max.expansion.level.soft_ value.

Example: _max.expansion.level.soft_ is configured to value 3. When making the request

> GET http://localhost:7012/gateleen/some_resources?expand=4

the data are only expanded until level 3. An additional warning will be logged, that the maximum expansion level has been exceeded.

##### max.expansion.level.hard
The _max.expansion.level.hard_ property (default value = _Integer.MAX_VALUE_) defines the hard limit for the maximum expansion level. This means that expand requests with an expand parameter higher
than _max.expansion.level.hard_ value are responded with

> 400 Bad Request

##### max.expansion.subrequests
The expansion is also limited by the property _max.expansion.subrequests_ which sets the maximum count of requests created by one recursive GET request.

The RecursiveExpansionHandler allows you to send GET requests to the server which are resolved recursively. 
What does that actually mean? Let’s have a look at an example:

> GET http://localhost:7012/gateleen/some_resources
```json
{
    "some_resources": [
            "v1/"
    ]
}
```

But what do I have to do, if I want to get all the sub-resources in the given resource tree?
This problem is solved with the recursive GET feature:

> GET http://localhost:7012/gateleen/some_resources?expand=4
```json
{
    "some_resources": {
        "v1": {
            "control": {
                "activations": {
                    "2aa5bf9a-3d51-49c6-b1a0-9f053ac8815b": {
                        "timestamp": "2015-02-12T11:35:11.655+01:00",
                        ...
                    },
                    "41766497-07b8-47d5-967c-a03db2b99b8d": {
                        "timestamp": "2015-02-12T11:35:11.655+01:00",
                        ...
                    }
                }
            }
        }
    }
}
```

Expand 3 levels only:

> GET http://localhost:7012/gateleen/some_resources?expand=3
```json
{
    "some_resources": {
        "v1": {
            "control": {
                "activations": [
                    "2aa5bf9a-3d51-49c6-b1a0-9f053ac8815b",
                    "41766497-07b8-47d5-967c-a03db2b99b8d"
                ]
            }
        }
    }
}
```

Additional information:
> <font color="blue">Note: </font>

* Each request is created with a eTag header. A second request to the same resource will lead, if nothing changed, to a 304 "Not Modified" response. 
* If the given recursion depth (x) is higher than the depth of the tree, the depth of the tree will be used. So it is safe to use high level of x if you want to expand a complete tree, but are not sure how deep he really is.

> <font color="orange">Attention: </font>

* If the collection is not found, status code 404 "Not found" is returned.
* If the collection actually is not a collection, 400 "Request did not return data. Invalid usage of params expand ?" is returned.
* If the collection contains NON-Json Resources OR if a Json Resource is malformed, 500 "Errors found in resources:" with a list of the problematic resources is returned. 
* If the number of allowed sub requests exceeds the given limit, 400 "Number of allowed sub requests exceeded. Limit is x requests" is returned. 

#### Expand on Backend
If you want that the backend expands the request and not gateleen, you can set the attribute:
> expandOnBackend=true

in the corresponding routing rule.

#### StorageExpand
If you want to use the StorageExpand feature, you can set the attribute:
> storageExpand=true

in the corresponding routing rule. The StorageExpand feature does expand directly in the storage, so it is faster than the "standard" expansion feature.
> <font color="orange">Attention: </font> Be aware that the depth is limited to 1 by now, regardless of the _max.expansion.level.hard_ value.

> <font color="orange">Attention: </font> You must allow the POST method (in the acls) for the urls you want to use with the StorageExpand feature!

For more information about the StorageExpand feature see the [vertx-rest-storage](https://github.com/swisspush/vertx-rest-storage) project.

### Parameter: expand=x&zip=true
#### Usage
> http://<url>/collection?expand=x&zip=true

This allows you to create one octet-stream containing each json resource in the given collection (see expand feature).
Basically it works exactly the same way as the default expand feature works, except that it does not set an eTag for the request.

> <font color="orange">Attention: </font> No eTag header is created / returned when this feature is used!