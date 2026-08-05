# Running the hookjs / hookts UI integration tests fast

The Cucumber/Selenium scenarios in
`gateleen-test/src/test/java/org/swisspush/gateleen/hookjs` and
`.../hookts` are **not** part of the default `mvn install`. They need a
locally installed reactor, a running playground server with data
uploaded, and a real Chrome + chromedriver. Doing this "the normal way"
(`mvn install` over the whole repo, or clicking through IntelliJ dialogs)
is slow.

Prerequisites (one-time):
* A local Maven binary (e.g. the one bundled with IntelliJ, see below) and
  `-Dmaven.repo.local=<your .m2>` pointed at a repo you can write to.
* Redis running (`redis-server`) and reachable with the config in
  `gateleen-playground`'s default properties.
* A `chromedriver.exe` matching your installed Chrome version, downloaded
  once from https://googlechromelabs.github.io/chrome-for-testing/.
  Note its full path.

## 1. Build & install everything the tests need (offline, one shot)

Run once (or whenever module code changed). This avoids Maven trying to
reach the (VPN-only) internal artifactory:

```powershell
$mvn = 'C:\Users\<you>\AppData\Local\Programs\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'
cd C:\work\gateleen
& $mvn '-Dmaven.repo.local=C:\Users\<you>\.m2\repository' -pl gateleen-test -am install -DskipTests -o
```

This builds and installs gateleen-core, gateleen-hook, gateleen-hook-js,
gateleen-hook-ts, gateleen-routing, ... and gateleen-test itself into your
local `.m2`, so nothing needs to be re-downloaded.

## 2. Build & start the playground server

The playground module needs Maven Central access for the shade plugin
the first time (drop `-o` here, keep it for step 1):

```powershell
& $mvn '-Dmaven.repo.local=C:\Users\<you>\.m2\repository' -pl gateleen-playground -am install -DskipTests
cd gateleen-playground\target
Start-Process -FilePath java -ArgumentList "-jar","playground.jar" `
  -RedirectStandardOutput "$env:TEMP\playground-out.log" `
  -RedirectStandardError "$env:TEMP\playground-err.log" -PassThru |
  ForEach-Object { $_.Id } | Out-File "$env:TEMP\playground.pid"
cd ..\..
```

It listens on `http://localhost:7012/playground`. A `404` there is
expected — storage is empty until the next step.

**Important:** make sure no other user/process is already bound to port
7012 or sharing the same Redis instance — a shared Redis/port causes
mysterious 404s on the event-bus SockJS bridge
(`/server/event/v1/sock/info`) further down, which look like test
failures but are actually environment contention.

## 3. Upload the static pages / demo config

Needed once per fresh playground start (registers routing rules, the
`hooktest.html` / `hooktest-ts.html` pages, and the hookjs/hookts JS
bundles):

```powershell
& $mvn '-Dmaven.repo.local=C:\Users\<you>\.m2\repository' -pl gateleen-playground deploy -PuploadStaticFiles -DskipTests
```

Verify with `curl http://localhost:7012/playground/hooktest-ts.html`
(should return `200`, not `404`).

## 4. Run the test(s)

The hookjs/hookts scenarios are excluded from the default and
`NonUiIntegrationTests` executions — they only run under the
`uiIntegrationTests` Maven profile. Just running `mvn test` on the
module with that profile active runs everything (this is exactly what
CI does, see `.github/workflows/maven.yml`):

```powershell
& $mvn '-Dmaven.repo.local=C:\Users\<you>\.m2\repository' -pl gateleen-test test `
  -PuiIntegrationTests `
  '-Dsel_chrome_driver=C:\path\to\chromedriver.exe' -o
```

To narrow down to a single suite while iterating, add
`-Dtest=HookTsTest` (plain class name — Cucumber runners don't resolve
package wildcards like `org.swisspush....*`). Swap in `HookJsTest` to
run the legacy JS suite the same way (useful as a baseline to check
whether a failure is a real regression or an environment issue).

## 5. Clean up

The test scenarios write/delete real data (hooks, listeners, queued
resources) into Redis and **leave it dirty** when interrupted or when a
suite fails partway through. Flush it after every run so the next run
(or unrelated work using the same Redis) starts clean:

```powershell
# Find redis-cli.exe once (path varies per machine/checkout) and reuse it:
$redisCli = Get-ChildItem -Path C:\ -Filter "redis-cli.exe" -Recurse -ErrorAction SilentlyContinue -Depth 6 | Select-Object -First 1 -ExpandProperty FullName
& $redisCli FLUSHALL

Stop-Process -Id (Get-Content "$env:TEMP\playground.pid")
```

If you know where your `redis-cli.exe` lives, skip the search and just
run `& "<path-to>\redis-cli.exe" FLUSHALL` directly.

## Notes / gotchas
* `mvn install` over the **whole** repo will fail without VPN access to
  the internal artifactory — always scope with `-pl <module> -am`.
* Step 1 can run fully `-o` (offline) once the local repo is populated;
  step 2's first run needs online access for `maven-shade-plugin`
  transitive deps.
* **Use a Redis instance that is not shared with anyone/anything else,
  and make sure port 7012 isn't already bound by a stale playground
  process.** A shared/dirty Redis (leftover routing rules, hook
  registrations, queued events from a previous or concurrent run) causes
  the event-bus SockJS bridge to 404
  (`/server/event/v1/sock/info`) and every "we see the message ..."
  step to time out — this looks exactly like a code regression but
  isn't. If in doubt, restart Redis fresh and restart the playground
  server before re-running.
* These tests **write and leave behind data in Redis** (hooks,
  listeners, queued messages) — always `FLUSHALL` when done (see step 5)
  so you don't leave Redis in a broken state for the next run or for
  unrelated work sharing the same instance.
* If both `HookJsTest` and `HookTsTest` fail with the *same* symptom,
  the issue is environmental (dirty/shared Redis, stale port binding,
  missing static upload), not a regression in gateleen-hook-ts — verified
  by running both back to back.
