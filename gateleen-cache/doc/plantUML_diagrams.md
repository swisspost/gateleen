# gateleen-cache implementation
```
@startuml

hide footbox
actor User
participant CacheHandler order 10
participant CacheStorage order 30
participant CacheDataFetcher order 40
participant 3rdPartyBackend order 50

User -> CacheHandler: Request /gateleen/resources/some_resource
activate CacheHandler
CacheHandler -> CacheStorage: cachedRequest(/gateleen/resources/some_resource)
activate CacheStorage
return cached json resource
alt resource not yet cached
CacheHandler -> CacheDataFetcher: fetchData(/gateleen/resources/some_resource)
activate CacheDataFetcher
CacheDataFetcher -> 3rdPartyBackend: request /gateleen/resources/some_resource
activate 3rdPartyBackend
return json response
return json response
CacheHandler -> CacheStorage: cacheRequest(/gateleen/resources/some_resource, json response)
activate CacheStorage
return response
end
return json response

@enduml
```