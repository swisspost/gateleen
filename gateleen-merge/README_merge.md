# gateleen-merge
This feature allows you to perform a GET requests over multiple collections and return the merged content of the request transparently, without the need or the knowledge of the underlying collections (hidden). 

## Usage
Add a new rule to the rules for the static routing. This rule has to be designed as described in the following example. 
```json
"/gateleen/data/(.*)" : {
    "path": "data/$1",
    "staticHeaders": {
        "x-merge-collections": "/gateleen/masterdata/parent/"
    }
}
```
| Property          | Description                              | 
|:----------------- | :--------------------------------------- |
| path                | During a merge request the collection mentioned in the header x-merge-collections will be requested and returns a list of all collections contained in it. Then new requests will be created based on the retrieved collections. At this moment the path will be added to this requests. |
| x-merge-collections | The path where the MergeHandler will be looking for collections to create a merge request. | 

> <font color="skyblue">Information: </font>**404 NOT FOUND** is only returned if nothing is found in any of the given routes / sources.


 ### Guidelines 
 > <font color="orange">Attention: </font>Using this feature requires a bit of attentiveness.<br>This guidelines are based on the functionality of the used features and not on the Limitation of the MergeHandler.

1) Be aware that the endpoint of the rule in the static routes has to be equal the _path_ property. 

 ```json
"/gateleen/same/(.*)" : {
    "path": "same/$1",
    "staticHeaders": {
        "x-merge-collections": "/gateleen/masterdata/parent/"
    }
}
```

The reason for this lies in the functionality of the expansion feature. 
E.g. 
Rule: 
```json
"/gateleen/notsame/(.*)" : {
    "path": "same/$1",
    "staticHeaders": {
        "x-merge-collections": "/gateleen/masterdata/parent/"
    }
}
```

Now a request will return an array where the fieldname of the array does not match the name of the requested collection. This may not be a problem, if you only directly request elements, but as soon as you use features like the ExpansionHandler (_?expand=x_), you will encounter problems.
```code
GET /gateleen/notsame/
{
    "same" : [
        ...
    ]
}
``` 

2) Be aware of the order of the Handler. Make sure that you put the **MergeHandler** directly before the **Router**. Mainly it should always be after **ExpansionHandler** and **DeltaHandler**.  

## Example
Here you can see the benefits of the MergeHandler.
 
**Rule**
```json
"/gateleen/data/(.*)" : {
    "path": "data/$1",
    "staticHeaders": {
        "x-merge-collections": "/gateleen/masterdata/parent/"
    }
}
```

Let’s assume that in the collection _/gateleen/masterdata/parent/_ are two other collections (this may also be listed routes):
```json
/gateleen/masterdata/parent/collection1/
/gateleen/masterdata/parent/collection2/
```

### Targeted request
```code
GET /gateleen/data/collection/122
	MergeHandler
	GET /gateleen/masterdata/parent/collection1/data/collection/123 => 404
	GET /gateleen/masterdata/parent/collection2/data/collection/122 => 200
```

You will get the content of the resource _122_.
 
### Collection request
```code
GET /gateleen/data/collection/
 	MergeHandler
 	GET /gateleen/masterdata/parent/collection1/data/collection => 200
 	GET /gateleen/masterdata/parent/collection2/data/collection => 200
     =>
         { 
         	"collection": [ 
         		"122", 
         		"123" 
         	] 
         }
```         
 You will get a merged result of the requests made on the given collections, without even knowing, that more than  one collection is involved.
 