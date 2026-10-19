# gateleen-routing

## Schema validation
Updating the routing rules requires a validation against a schema to be positive. Check the schema [gateleen_routing_schema_routing_rules](src/main/resources/gateleen_routing_schema_routing_rules)

## Request retries
`ForwarderRetryPolicy` owns configuration parsing, status matching and per-request override
precedence. `Forwarder` retains replay-safety checks, timeout enforcement and retry execution.

There are two settings: a retry policy (`retriesByStatus`) and a safety ceiling (`maxRetries`).
All configured values and headers count **additional retries**, not total attempts.
Without a matching policy or valid request header, requests are attempted once and not retried.

The startup-only JVM system property `org.swisspush.gateleen.routing.forwarder.maxRetries`
sets a server-controlled ceiling on **additional retries**, defaulting to `5` (at most six total
attempts). It caps both sources: request headers and `retriesByStatus`.
Set it to `0` to disable all retries, or to a non-negative integer to change the ceiling.
Invalid values (including overflow beyond `2147483647`) are logged and replaced with `5`.
For example, `-Dorg.swisspush.gateleen.routing.forwarder.maxRetries=10` permits up to ten retries.
The ceiling does not enable retries by itself and cannot be overridden by a request header,
even when `x-timeout: 0` disables the request timeout.

The startup-only JVM system property `org.swisspush.gateleen.routing.forwarder.retriesByStatus`
adds a global status-pattern policy. Its value is a JSON object, for example:

When the property is absent, this explicit default JSON is applied:

```json
{
  "upstreamFailure": 0,
  "4xx": 0,
  "5xx": 0,
  "502": 0
}
```

This disables retries for failures without a response and all received HTTP error responses,
including 429, 502 and 503. Request headers can still override these zero-retry defaults.
The explicit `502` entry documents received Bad Gateway responses; `upstreamFailure` separately
documents failures where no HTTP response arrives.
A supplied JSON object replaces the default; it is not merged with it.

For example, to enable retries selectively, supply:

```json
{"upstreamFailure":2,"4xx":0,"429":3,"5xx":2,"501":0}
```

This allows three additional retries for 429 and two for other 5xx responses, except 501,
which is never retried. `upstreamFailure` allows two retries when the upstream fails to deliver
an HTTP response, including when no connection can be established (connection refused, DNS
failure, connect timeout; answered with 503 once the retries are used up). It is independent of
static `502` and `5xx` settings, which apply only to
received HTTP responses. An absent `upstreamFailure` setting means no retries for these failures.
Exact statuses override class patterns. Supported keys are `upstreamFailure`, 400-599,
`4xx` and `5xx` (case-insensitive); values must be non-negative integral JSON numbers no greater
than `2147483647`. Invalid entries are logged and skipped; malformed JSON is logged and the
whole policy is ignored. An ignored entry does not override a valid class setting.
Each forwarder snapshots both the policy and ceiling at construction;
configure all retry properties before startup and do not change them at runtime.

The startup-only JVM system property `org.swisspush.gateleen.routing.forwarder.retryExcludePattern`
excludes rules from the static `retriesByStatus` policy, for example backends whose failures are
expected and should not be multiplied by retries (such as a server behind a mobile data link):

```
-Dorg.swisspush.gateleen.routing.forwarder.retryExcludePattern=.*houston.*
```

The regular expression is matched case-insensitively against the **whole** URL pattern of the
rule (for example `/eagle/houston/(.*)`) and against its **whole** target URL
`scheme://host:port/path` (for example `http://backend:7013/houston/$1`, with configured wildcards
and unresolved `$1` references); either match excludes the rule. The target URL always contains
the port, also when the rule's `url` omits it (`80` for http, `443` for https), so
`http://backend/.*` never matches; use `http://backend:.*` instead. This applies to routing rules
and hook routes alike. For an excluded rule neither `upstreamFailure` nor any status pattern of
`retriesByStatus` applies, but `x-route-retry-*` request headers still do (within `maxRetries`):
a client sending them explicitly asks for retries. Absent or blank means nothing is excluded; an
invalid expression is logged once (as a warning) and excludes nothing. Exclusions themselves are
logged at DEBUG level of `ForwarderRetryPolicy`.

Rules with a `path` never retry themselves; in a chain, the retries happen on the rule that finally
calls the backend. Excluding only the rule with the `path` therefore does **not** exclude the
chain: the expression must match that final rule (its URL pattern or its target URL).

Clients can override this behaviour per request using `x-route-retry-<status>` or
`x-route-retry-<status-class>` headers:

```http
x-route-retry-4xx: 3
x-route-retry-404: 0
x-route-retry-5xx: 2
```

Header values specify the number of **additional retries**, not total attempts: `3` allows an
initial attempt plus up to three retries. An exact status (for example `404`) takes precedence
over its status class (`4xx`), so the example never retries a 404. Headers are case-insensitive.
Only HTTP error responses (400-599) are retried; successful responses are returned immediately.
Matching uses the original backend status, before response status translation.

A matching header overrides the static retry policy, including when no static setting exists,
but remains subject to the server-controlled `maxRetries` ceiling.
`x-route-retry-*` headers apply only to the Gateleen instance that receives them; they are removed
before the request is forwarded, so chained Gateleen instances do not multiply the attempts.
Only external forwarding retries. Rules with a `path` (scheme `local`), and hook routes whose
destination is a path, never retry: they only re-enter the same Gateleen instance through the
self client, where the rule handling the rewritten path applies the retry policy and the
`x-route-retry-*` headers, which local rules pass on unchanged. Otherwise a local rule in front of
a retrying rule would multiply the attempts (three times three). This is decided by the rule
type only: a `url` rule pointing to the Gateleen instance itself is treated like any backend and
retries itself (consuming the headers), so its static policy may apply on both hops.
Retries of the forwarder are independent of request queueing: a queued request (`x-queue`) is
retried by the queue only after the forwarder has used up its own retries, so both multiply.
If a retrying rule has to abort a partly streamed response (for example on deadline expiry), a
Gateleen-internal caller (local rule, queue, hook or scheduler) receives the same abort as an
external client (the response fails with "Connection was closed") and never a seemingly complete
response.
Upstream failures that would produce a 502 also honour `x-route-retry-502` / `x-route-retry-5xx`.
Without a matching valid header, the static status-pattern policy is used. Precedence is:
exact request header, class request header, exact static status, static class, then no retries.
For failures without an HTTP response, precedence is request `502` header, request `5xx` header,
static `upstreamFailure`, then no retries. Static HTTP patterns are not consulted for these failures.
Thus even a class request header overrides an exact static status. With neither a matching
header nor a static setting, no retries are performed. Header values must be non-negative
integers; invalid values are logged and ignored (an invalid exact-status value does not fall back
to the class header).

The former `org.swisspush.gateleen.routing.forwarder.maxForwardAttempts` property has been removed.
To migrate an old total-attempt budget of `N`, set `"upstreamFailure": N-1` in `retriesByStatus`.
This policy, like all others, remains subject to the `maxRetries` ceiling.

All attempts share a single retry counter, even if the backend status changes between attempts.
After the applicable retry count is exhausted, the final backend response (status, headers and
body) is returned normally. Retries require an idempotent method (`GET`, `HEAD`, `PUT`, `DELETE`,
`OPTIONS` or `TRACE`) and a replayable, buffered request body. Routed requests are buffered in
memory before forwarding only when they are idempotent and the effective policy (static
configuration or request headers, after the ceiling) permits at least one retry; if reading that
body fails, the request is answered with 400. All other requests keep streaming their body and
are never replayed (for example `POST`, or any request while all retries are disabled). Callers
that already hold the body (such as hooks) pass it in buffered. Host and port wildcards are
resolved once per request, so all attempts go to the same backend.
These headers configure retry counts, not retry delays.

To bound memory, only bodies with a declared `Content-Length` of at most
`org.swisspush.gateleen.routing.forwarder.maxRetryBodyBytes` (startup-only JVM system property,
default `1048576` = 1 MiB, `0` allows only requests without body) are buffered. Larger bodies and
bodies of unknown length (`Transfer-Encoding: chunked`, or HTTP/2 without `Content-Length`) are
streamed as before and therefore not retried. An invalid value is logged and the default is used.

For buffered, idempotent requests with an effective policy permitting retries, a positive rule
timeout (or `x-timeout` override) is a **hard request-wide deadline**. Pool waiting, all attempts,
draining rejected responses and streaming the final response share that budget. Incoming response
data does not extend it, and retry eligibility is checked again after draining (too little
remaining budget ends the request with `Insufficient retry budget after draining HTTP <status>`,
otherwise expiry is logged as `Request-wide retry timeout`). Expiry before
response headers are sent returns 502 (504 while waiting for a pool connection); after headers
are sent, the downstream connection is closed rather than completing a truncated response.
Successful completion cancels the deadline. A non-positive timeout disables it.

Requests that cannot retry retain ordinary per-phase pool-wait and upstream idle timeouts:
this includes the default zero-retry policy, zero ceiling, effective zero header overrides
and non-idempotent methods. A continuously progressing response on
these requests may therefore last longer than the configured idle timeout.

Retry attempts share a single request log and metric lifecycle. Buffered payloads are captured
once for logging. If an error response cannot be fully drained before retrying (for example,
its body is truncated), forwarding terminates with 502 without exposing the partial body;
request metrics are finalized once.
Failures while writing a buffered upstream request are coordinated with response-failure handling,
so they cannot send a competing error before a permitted retry. If no retry follows, they are
logged as before (WARN `Failed to '<method> <uri>'` plus an ERROR entry). Header-function errors terminate
with 400, finalize metrics immediately and cancel the retry deadline.
For all forwarded requests, request metrics (`MonitoringHandler` and `gateleen_forwarded_seconds`)
are now also finalized when forwarding ends without a backend response (pool-wait timeout,
connection failure, header-function error or internal error), so those requests are counted too.
A received backend response is still logged when the client disconnects or the deadline expires
while it is being streamed.

When a `MeterRegistry` is configured, `Forwarder` also publishes three cumulative counters:

| Counter | Increment |
| --- | --- |
| `gateleen_forwarded_requests` | Once per forwarded request, including requests without retries and requests ending in errors |
| `gateleen_forwarded_retries` | By the number of additional attempts of that same request (`attempt - 1`) |
| `gateleen_forwarded_failures` | Once if that request finally failed: final status `>= 500` (see below) |

All three are updated together, at the same request-metric completion point, and carry the tags
`metricName` (the rule's metric name, or `no-metric-name`), `rule` (the rule's URL pattern) and
`target` (`host:port`, or the host/port wildcard, for URL rules; the target path template for
local `path` rules; missing parts are `unknown`). They never contain request URLs or IDs, so the
number of series is bounded by the existing routing rules and hook routes.

Failure classification:
* The status is the **backend** status before any status translation (`translateStatus` or
  `x-translate-status-*`): a backend 503 translated to 200 is a failure, a backend 200 translated
  to 500 is not. Every status `>= 500` counts, including 501 and 505.
* Without an accepted backend response, the status is the one Gateleen answers with: 502/503/504
  (connection failure, pool-wait timeout, deadline expiry, upstream error) and 500 (internal error)
  are failures; 400 for a failing header function is not.
* Client errors (4xx) are never failures, also not after the 4xx retries are used up.

The completion point is reached when the backend response is accepted, i.e. after its headers
passed the transfer-encoding check and right before the body starts streaming. A response Gateleen
rejects itself (unsupported transfer coding → 502, internal error → 500) is therefore counted with
Gateleen's status. Anything that happens later is not reflected: a deadline expiry or upstream
reset while the body streams, or a client disconnect, does not change the counted status.
Without a backend response, the completion point is reached when Gateleen answers with its error status.

Meter lifecycle: forwarders with the same tags share the same counters, which are reference
counted. Rule reloads and refreshed hook registrations reuse the counters without reset. Hook
routes are tagged with their own URL pattern; their counters are removed from the registry when
the last route using them is closed (hook unregistered or expired). Counters of routing rules
are never removed.

Synchronization: the increments of the three counters happen while synchronized on the
`MeterRegistry` instance. Consumers reading them as a consistent set (for example interval deltas)
should synchronize on the same instance so a request and its retries/failure never fall into
different intervals. Keep that critical section short: every forwarded request on every event loop
passes it once, so holding the registry monitor for long blocks the event loops.
Local `path` rules forward through Gateleen itself, so a failing request may be counted both by
the local rule and by the rule handling the rewritten path.

Each retry is logged at INFO (`Retrying request (attempt n) ...`). When a request that was retried
at least once finishes, one summary line with method, target URL, number of retries and final
HTTP status is logged by `org.swisspush.gateleen.routing.Forwarder`:
* INFO if the final backend response is a success (status below 400), e.g.
  `Request GET http://host:8080/path succeeded after 2 retries with HTTP 200`
* ERROR otherwise (error response after the retries are used up, upstream failure, timeout, ...),
  including the reason if no complete response was delivered, e.g.
  `Request GET http://host:8080/path failed after 2 retries with HTTP 502: Bad upstream response: Connection was closed`.
  For timeouts this replaces the generic timeout ERROR, so there is no duplicate error entry.

The summary reports the backend's final status; if the client receives a different one due to
status translation, it is appended, e.g. `... with HTTP 404 (client received HTTP 200)`.

Requests that were not retried produce no summary line; their logging is unchanged.

## Routing configuration
To configure the routing, the _ConfigurationResourceManager_ and the path to the configuration resource have to be configured in the _Router_ by calling:

```java
router.enableRoutingConfiguration(configurationResourceManager, SERVER_ROOT + "/admin/v1/routing/config")
```
The routing configuration is a json resource defining the limit for the maximum request routings (hops) called **_request.hops.limit_** and OAuth configuration called **_authConfigs_**.
Check the schema [gateleen_routing_schema_config](src/main/resources/gateleen_routing_schema_config)

Example:

```json
{
  "request.hops.limit": 10,
  "authConfigs": {
    "demo-config-1": {
      "flowType": "CLIENT",
      "clientId": "543567344a5005",
      "clientSecret": "212636219b3a5b",
      "site": "https://api.swisspost.ch",
      "tokenPath": "/OAuth/token",
      "authPath": "/OAuth/authorization",
      "scopes": [
        "APIM_SANDBOX_RESOURCE_SERVER_READ"
      ],
      "supportedGrantTypes": [
        "client_credentials"
      ]
    }
  }
}
```

### Request hop validation
To protect gateleen from routing rules configured to result in an endless loop, the request hop validation feature has been introduced.

When the request hop validation is activated, a **_x-hops_** request header will be added to the request before routing. Initially, this header will receive the value 1.
When the **_x-hops_** header already exists (in alredy routed requests for example), the value will be increased by 1.

Before the request is routed, the **_x-hops_** header value will be checked against a configurable limit. When the limit is exceeded, the request will
be answered with a http status code _500_ and a http status message: _Request hops limit exceeded_

### OAuth2 authentication
To activate OAuth2 authentication for forwarding requests, a configuration with the required properties like _flowType_, _clientId_, _clientSecret_, etc. can be provided. See [vertx-auth-oauth2 documentation](https://vertx.io/docs/vertx-auth-oauth2/java/) for more
information. The id of the configuration called _oAuthId_ (_demo-config-1_ in the example) is used in the routing rule.

Example:

```json
{
  "/gateleen/server/rule/1/(.*)": {
    "oAuthId": "demo-config-1",
    "url": "http://localhost/some/backend/path/$1"
  }
}
```

## Log routing rule changes
To log the payload of changes to the routing rules, the [RequestLogger](../gateleen-core/src/main/java/org/swisspush/gateleen/core/logging/RequestLogger.java) can be used.

Make sure to instantiate the [RequestLoggingConsumer](../gateleen-logging/src/main/java/org/swisspush/gateleen/logging/RequestLoggingConsumer.java) by calling
                                                                                                  
```java
RequestLoggingConsumer requestLoggingConsumer = new RequestLoggingConsumer(vertx, loggingResourceManager);
```

To enable the logging of the routing rules, make sure the url to the routing rules is enabled in the logging resource.

Example:

```json
{
  "headers": [],
  "payload": {
    "filters": [
      {
        "url": "/playground/server/admin/v1/routing/.*",
        "method": "PUT"
      }
    ]
  }
}
```
Also you have to enable the logging on the [Router](src/main/java/org/swisspush/gateleen/routing/Router.java) by calling
```java
router.enableResourceLogging(true);
```
## Use CustomHttpResponseHandler
The [CustomHttpResponseHandler](src/main/java/org/swisspush/gateleen/routing/CustomHttpResponseHandler.java) can be used together with simple routing rules to respond with a configurable
http status code.

Having a configured rootPath of
 
> _/gateleen/server/return-http-error_

you can add the following routing rule to correctly respond every request on

> _/gateleen/server/unreachable/service_

with a http status code 503
```json
{
    "/gateleen/server/unreachable/service": {
        "path": "/gateleen/server/return-http-error/503",
        "methods": [
          "GET"
        ]
    }
}
```

## Apply routing rules based on request headers
Routing rules are applied to requests based on the request url and the http method. By defining the _headersFilter_ property, you are able to also apply a routing rule based on request headers.

Examples:
```json
{
    "/gateleen/server/rule/1/(.*)": {
        "headersFilter": "x-foo.*",
        "url": "http://localhost/some/backend/path/$1"
    },
    "/gateleen/server/rule/2/(.*)": {
        "headersFilter": "x-foo: (A|B|C)",
        "url": "http://localhost/some/backend/path/$1"
    },
    "/gateleen/server/rule/3/(.*)": {
        "headersFilter": "x-foo: [0-9]{3}",
        "url": "http://localhost/some/backend/path/$1"
    }
}
```
Each request header entry is validated in the format `<KEY>: <VALUE>`, so you are able to filter for request header names and values.

## Micrometer metrics
The routing feature is monitored with micrometer. The following metrics are available:
* gateleen_forwarded_seconds
* gateleen_forwarded_seconds_max
* gateleen_forwarded_seconds_count
* gateleen_forwarded_seconds_sum
* gateleen_forwarded_storage_writes_total

Additional tags are provided to split the forward count into sub counts.

| tag        | description                                                                                                                                        |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| metricName | The `metricName` property from the corresponding routing rule. With this, you are able to count requests per rule                                  |
| type       | Describes where the request was forwarded to. Possible values are `storage`, `local`, `external` and `null`                                        |      
| quantile   | Values of `0.75` and `0.95` for percentile durations of requests                                                                                   |
| expires    | Values of `true` or `false` for gateleen_forwarded_storage_writes_total metrics to count the amount of storage writes using the expiration feature |


Example metrics:

```
# HELP gateleen_forwarded_seconds_max Durations of forwarded requests
# TYPE gateleen_forwarded_seconds_max gauge
gateleen_forwarded_seconds_max{metricName="storage-resources",type="storage",} 8.5515
gateleen_forwarded_seconds_max{metricName="infotool_v1_informations",type="external",} 3.456
# HELP gateleen_forwarded_seconds Durations of forwarded requests
# TYPE gateleen_forwarded_seconds summary
gateleen_forwarded_seconds{metricName="storage-resources",type="storage",quantile="0.75",} 6.2158
gateleen_forwarded_seconds{metricName="storage-resources",type="storage",quantile="0.95",} 8.2123
gateleen_forwarded_seconds_count{metricName="storage-resources",type="storage",} 67565.0
gateleen_forwarded_seconds_sum{metricName="storage-resources",type="storage",} 656434.0
gateleen_forwarded_seconds{metricName="infotool_v1_informations",type="external",quantile="0.75",} 4.2365
gateleen_forwarded_seconds{metricName="infotool_v1_informations",type="external",quantile="0.95",} 4.8998
gateleen_forwarded_seconds_count{metricName="infotool_v1_informations",type="external",} 7567.0
gateleen_forwarded_seconds_sum{metricName="infotool_v1_informations",type="external",} 256324.0
# HELP gateleen_forwarded_storage_writes_total Amount of storage write operations
# TYPE gateleen_forwarded_storage_writes_total counter
gateleen_forwarded_storage_writes_total{expires="true",metricName="storage-main",} 235745.0
gateleen_forwarded_storage_writes_total{expires="false",metricName="storage-main",} 1055.0
```

To enable the metrics, set a `MeterRegistry` instance by calling `withMeterRegistry(MeterRegistry meterRegistry)` method in `RouterBuilder` class.