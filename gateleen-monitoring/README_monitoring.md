# gateleen-monitoring
This module provides monitoring functionality based on the [mod-metrics](https://github.com/swisspush/mod-metrics) module.

## Request per Rule Monitoring
Monitor incoming requests and routing rules matching these requests.

### Introduction
This feature provides online statistics about requests and matching routing rules. To activate, the system property _org.swisspush.request.rule.property_ has to be set with a request header name.
The configured request header name is used to group the statistics. Using the request header **x-rp-grp** for example will group the requests by user groups and therefore provide statistics about which routing rule is matched for requests from each user group.
These statistics are cached locally in the MonitoringHandler class and periodically (configurable via sampling system property) sent to the mod-metrics module. The statistics are cached to reduce load..
 
Besides sending the statistics to the mod-metrics module, a storage entry is written too. These entries are grouped by server instance with a UUID created on instantiation of the MonitoringHandler class. This grouping is used to prevent race conditions when having multiple instances of gateleen.
Each storage entry is configured to expire (configurable via expiry system property) after some time. The storage entries can then be accessed through the REST API. 

### Configuration
| System Property                     | Description      | Example |
|:----------------------------------- | -----------------| ------- |
| org.swisspush.request.rule.property | This property has to be set to activate the 'Request per Rule Monitoring' feature. The value should be a request header which has to be used to group the requests by | -Dorg.swisspush.request.rule.property=x-rp-grp |
| org.swisspush.request.rule.sampling | Defines the sampling rate [milliseconds] used to send the metrics to the mod-metrics module | -Dorg.swisspush.request.rule.sampling=30000 |
| org.swisspush.request.rule.expiry   | Defines the expiration [seconds] of the metric entry in the storage | -Dorg.swisspush.request.rule.expiry=120 |

> <font color="orange">Attention: </font> Each routing rule must have the property **name** with a unique identifier for the rule

### Usage
The following steps are used to correctly setup the **Request per Rule Monitoring** feature:

1. Set system property _org.swisspush.request.rule.property_ with the request header used to group the requests
2. Configure the _requestPerRulePath_ value by using the corresponding MonitoringHandler constructor
> MonitoringHandler(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, String prefix, **String requestPerRulePath**)
3. [Optional] Set system property _org.swisspush.request.rule.sampling_ to override the default sampling rate
4. [Optional] Set system property _org.swisspush.request.rule.expiry_ to override the default expiry
5. Set property _name_ on each routing rule

```json
"/playground/img/(.*)": {
	"name": "image_resources",
	"description": "Pages Images",
	"path": "/playground/server/pages/img/$1",
	"storage": "main"
}
```

### Output
The metrics are stored in storage under the configured _requestPerRulePath_. The name of the entry is built from the request header value and the matched routing rule.
> usergrp_1=image_resources
> usergrp_2=image_resources
> usergrp_2=css_resources

When the configured request header name has no value for a request, **unknown** is used as part of the entry name.
> unknown=image_resources

### Example
This example shows 3 gateleen instances having made some requests.

| Configuration                     | Value |
|:----------------------------------- | -----------------| 
| org.swisspush.request.rule.property | -Dorg.swisspush.request.rule.property=x-rp-grp |
| requestPerRulePath | /gateleen/monitoring/rpr/ |

**_GET /playground/server/monitoring/rpr/_**
```json
{
  "rpr": [
    "12455d28-557e-1951-ab26-2af24fd533c2/",
    "62755c74-fa7e-4051-9b2a-6f482d107183/",
    "617abc7e-dd2e-1510-d56a-c5422d10596a/"
  ]
}
```

**_GET /playground/server/monitoring/rpr/?expand=1_**
```json
{
  "rpr": {
    "12455d28-557e-1951-ab26-2af24fd533c2": [
      "usrgrp1=css_resources",
      "usrgrp1=resource_storage",
      "usrgrp2=resource_storage"
    ],
    "62755c74-fa7e-4051-9b2a-6f482d107183": [
      "usrgrp2=css_resources",
      "usrgrp2=storage_empty",
      "usrgrp3=resource_storage"
    ],
    "617abc7e-dd2e-1510-d56a-c5422d10596a": [
      "usrgrp3=css_resources",
      "usrgrp3=storage_empty"
    ]
  }
}
```

**_GET /playground/server/monitoring/rpr/?expand=2_**
```json
{
  "rpr": {
    "617abc7e-dd2e-1510-d56a-c5422d10596a": {
      "usrgrp3=css_resources": {
        "timestamp": 1460386517957
      },
      "usrgrp3=storage_empty": {
        "timestamp": 1460386517957
      }
    }
  }
}
```