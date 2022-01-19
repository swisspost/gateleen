# gateleen-cache
The gateleen-cache module provides caching functionality for json based http responses.

## Configuration
The gateleen-cache module requires the following configuration:

| Value                          | Class | Description                              |
|:-------------------------------|-------|------------------------------------- |
| _cacheAdminUri_ | CacheHandler   | The path to access the adminstrator functionalities like cache clearing |
| _storageCleanupIntervalMs_ | RedisCacheStorage   | The interval (in milliseconds) to clean supporting storage entries used for _cache entries count and list_. The cache entries are cleared automatically. |

## Usage
The cache functionality is available for requests matching the following conditions:
* The http method must be __GET__
* The request headers must contain `Cache-Control: max-age=1` with values greater than zero
* The response headers must contain `Content-Type: application/json`

## Administration
Under the configured admin API path, the following admin functionality is currently available.

### Clear cache
With a POST request to
```
/your/admin/api/path/clear
```
you can clear all cache entries from the storage. The response containes the number of cleared entries.

Example:
```json
{
  "cleared": 99
}
```

### Count cache entries
With a GET request to
```
/your/admin/api/path/count
```
you can get the count of cached entries in the storage. The response containes the number of cached entries.

Example:
```json
{
  "count": 99
}
```

### List cache entries
With a GET request to
```
/your/admin/api/path/entries
```
you can get the list of cached entries in the storage. The response containes the identifiers (url) of cached entries.

Example:
```json
{
  "entries": [
    "/some/cached/resources/res_1",
    "/some/cached/resources/res_2",
    "/some/cached/resources/res_3"
  ]
}
```

## Implementation
```
       ┌─┐                                                                                                                                                                                                   
       ║"│                                                                                                                                                                                                   
       └┬┘                                                                                                                                                                                                   
       ┌┼┐                                                                                                                                                                                                   
        │                                   ┌────────────┐                                                   ┌────────────┐          ┌────────────────┐                           ┌───────────────┐          
       ┌┴┐                                  │CacheHandler│                                                   │CacheStorage│          │CacheDataFetcher│                           │3rdPartyBackend│          
      User                                  └─────┬──────┘                                                   └─────┬──────┘          └───────┬────────┘                           └───────┬───────┘          
       │Request /gateleen/resources/some_resource┌┴┐                                                               │                         │                                            │                  
       │ ───────────────────────────────────────>│ │                                                               │                         │                                            │                  
       │                                         │ │                                                               │                         │                                            │                  
       │                                         │ │       cachedRequest(/gateleen/resources/some_resource)        ┌┴┐                       │                                            │                  
       │                                         │ │ ────────────────────────────────────────────────────────────> │ │                       │                                            │                  
       │                                         │ │                                                               └┬┘                       │                                            │                  
       │                                         │ │                      cached json resource                     │                         │                                            │                  
       │                                         │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                          │                                            │                  
       │                                         │ │                                                               │                         │                                            │                  
       │                                         │ │                                                               │                         │                                            │                  
       │                          ╔══════╤═══════╪═╪═══════════════════════════════════════════════════════════════╪═════════════════════════╪════════════════════════════════════════════╪═════════════════╗
       │                          ║ ALT  │  resource not yet cached                                                │                         │                                            │                 ║
       │                          ╟──────┘       │ │                                                               │                         │                                            │                 ║
       │                          ║              │ │                      fetchData(/gateleen/resources/some_resource)                       ┌┴┐                                          │                 ║
       │                          ║              │ │ ──────────────────────────────────────────────────────────────────────────────────────> │ │                                          │                 ║
       │                          ║              │ │                                                               │                         │ │                                          │                 ║
       │                          ║              │ │                                                               │                         │ │request /gateleen/resources/some_resource┌┴┐                ║
       │                          ║              │ │                                                               │                         │ │ ───────────────────────────────────────>│ │                ║
       │                          ║              │ │                                                               │                         │ │                                         └┬┘                ║
       │                          ║              │ │                                                               │                         │ │               json response              │                 ║
       │                          ║              │ │                                                               │                         │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                 ║
       │                          ║              │ │                                                               │                         └┬┘                                          │                 ║
       │                          ║              │ │                                      json response            │                         │                                            │                 ║
       │                          ║              │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                                             │                 ║
       │                          ║              │ │                                                               │                         │                                            │                 ║
       │                          ║              │ │cacheRequest(/gateleen/resources/some_resource, json response) ┌┴┐                       │                                            │                 ║
       │                          ║              │ │ ────────────────────────────────────────────────────────────> │ │                       │                                            │                 ║
       │                          ║              │ │                                                               └┬┘                       │                                            │                 ║
       │                          ║              │ │                            response                           │                         │                                            │                 ║
       │                          ║              │ │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                          │                                            │                 ║
       │                          ╚══════════════╪═╪═══════════════════════════════════════════════════════════════╪═════════════════════════╪════════════════════════════════════════════╪═════════════════╝
       │                                         └┬┘                                                               │                         │                                            │                  
       │               json response              │                                                                │                         │                                            │                  
       │ <─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                                                                │                         │                                            │                  
       │                                          │                                                                │                         │                                            │                  
       │                                          │                                                                │                         │                                            │                  
```