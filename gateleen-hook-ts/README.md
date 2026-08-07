# gateleen-hook-ts

Framework-agnostic TypeScript client for [Gateleen](https://github.com/swisspush/gateleen) webhooks.

## Features

- ✅ **Framework Agnostic** - Works with Angular 22, WebComponents, or any framework
- ✅ **TypeScript** - Full type safety with generic types
- ✅ **Single Dependency** - Only depends on `vertx3-eventbus-client`
- ✅ **Modern** - Uses async/await, native Fetch API, ES2022

## Installation

```bash
npm install gateleen-hook-ts
```

## Running the hook-ts / hook-js UI integration tests fast

The Cucumber/Selenium scenarios in
`gateleen-test/src/test/java/org/swisspush/gateleen/hookjs` and
`.../hookts` are **not** part of the default `mvn install`. They need a
locally installed reactor, a running playground server with data
uploaded, and a real Chrome + chromedriver. Doing this "the normal way"
(`mvn install` over the whole repo, or clicking through IntelliJ dialogs)
is slow.

Prerequisites (one-time):

- A local Maven binary (e.g. the one bundled with IntelliJ, see below) and
  `-Dmaven.repo.local=<your .m2>` pointed at a repo you can write to.
- Redis running (`redis-server`) and reachable with the config in
  `gateleen-playground`'s default properties.
- A `chromedriver.exe` matching your installed Chrome version, downloaded
  once from <https://googlechromelabs.github.io/chrome-for-testing/>.
  Note its full path.

### 1. Build & install everything the tests need (offline, one shot)

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

### 2. Build & start the playground server

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

### 3. Upload the static pages / demo config

Needed once per fresh playground start (registers routing rules, the
`hooktest.html` / `hooktest-ts.html` pages, and the hookjs/hookts JS
bundles):

```powershell
& $mvn '-Dmaven.repo.local=C:\Users\<you>\.m2\repository' -pl gateleen-playground deploy -PuploadStaticFiles -DskipTests
```

Verify with `curl http://localhost:7012/playground/hooktest-ts.html`
(should return `200`, not `404`).

### 4. Run the test(s)

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

### 5. Clean up

The test scenarios write/delete real data (hooks, listeners, queued
resources) into Redis and **leave it dirty** when interrupted or when a
suite fails partway through. Flush it after every run so the next run
(or unrelated work using the same Redis) starts clean:

```powershell
# Find redis-cli.exe once (path varies per machine/checkout) and reuse it:
$redisCli = Get-ChildItem -Path C:\ -Filter "redis-cli.exe" -Recurse `
  -ErrorAction SilentlyContinue -Depth 6 | Select-Object -First 1 -ExpandProperty FullName
& $redisCli FLUSHALL

Stop-Process -Id (Get-Content "$env:TEMP\playground.pid")
```

If you know where your `redis-cli.exe` lives, skip the search and just
run `& "<path-to>\redis-cli.exe" FLUSHALL` directly.

### Notes / gotchas

- `mvn install` over the **whole** repo will fail without VPN access to
  the internal artifactory — always scope with `-pl <module> -am`.
- Step 1 can run fully `-o` (offline) once the local repo is populated;
  step 2's first run needs online access for `maven-shade-plugin`
  transitive deps.
- **Use a Redis instance that is not shared with anyone/anything else,
  and make sure port 7012 isn't already bound by a stale playground
  process.** A shared/dirty Redis (leftover routing rules, hook
  registrations, queued events from a previous or concurrent run) causes
  the event-bus SockJS bridge to 404
  (`/server/event/v1/sock/info`) and every "we see the message ..."
  step to time out — this looks exactly like a code regression but
  isn't. If in doubt, restart Redis fresh and restart the playground
  server before re-running.
- These tests **write and leave behind data in Redis** (hooks,
  listeners, queued messages) — always `FLUSHALL` when done (see step 5)
  so you don't leave Redis in a broken state for the next run or for
  unrelated work sharing the same instance.
- If both `HookJsTest` and `HookTsTest` fail with the *same* symptom,
  the issue is environmental (dirty/shared Redis, stale port binding,
  missing static upload), not a regression in gateleen-hook-ts — verified
  by running both back to back.

## Quick Start

### Angular 22

```typescript
import { Service, OnDestroy } from '@angular/core';
import { HookService } from 'gateleen-hook-ts';

/**
 * @Service() = App-wide singleton wrapper around `HookService`.
 *
 * `HookService` opens its own WebSocket (via `EventBusService`) as soon as
 * it is constructed. Instantiating it per-component (`new HookService()`)
 * therefore forces a fresh socket handshake every time a component is
 * created, delaying on load hook-driven renders.
 */
@Service()
export class GateleenHookService extends HookService implements OnDestroy {
  constructor() {
    super();
  }

  ngOnDestroy(): void {
    this.dispose();
  }
}
```

```typescript

import { Injectable, signal } from '@angular/core';
import { HookService } from 'gateleen-hook-ts';

@Service()
export class UserService {
  private readonly hooks = inject(GateleenHookService);
  readonly lastUser = signal<UserData | null>(null);

  setupHook() {
    void this.hooks.listen<UserData>(
      { path: '/api/users', methods: ['PUT'], fetch: 'single' },
      (user, params) => {
        this.lastUser.set(user);
      }
    );
  }
}
```

### WebComponent

```typescript
import { HookService } from 'gateleen-hook-ts';

class UserList extends HTMLElement {
  private readonly hooks = new HookService();

  connectedCallback() {
    void this.hooks.listen<UserData>(
      { path: '/api/users', methods: ['PUT'], fetch: 'single' },
      (user, params) => {
        console.log('User updated:', user);
        this.updateDisplay(user);
      }
    );
  }

  disconnectedCallback() {
    this.hooks.dispose();
  }
}
```

## API

### `HookService`

Main service for managing hook registrations.

#### `new HookService(eventBusService?: EventBusService)`

Creates a hook service instance. If no `EventBusService` is supplied, one is created automatically.

```typescript
const service = new HookService();
```

#### `listen<TPayload>(def: HookDefinition, callback: Callback): Promise<HookDeregisterer>`

Registers a hook listener.

**Parameters:**

- `def: HookDefinition` - Hook definition (path, methods, fetch option)
- `callback: (payload: TPayload, params: CallbackParams) => void` - Handler function

**Returns:** Promise resolving to a deregisterer handle

**Example:**

```typescript
const deregisterer = await new HookService().listen<MyData>(
  {
    path: '/api/data',
    methods: ['PUT', 'POST'],
    fetch: 'single'
  },
  (payload, params) => {
    console.log('Payload:', payload);
    console.log('From URI:', params.uri);
    console.log('Channel ID:', params.channelId);
  }
);

// Later: cleanup
deregisterer.deregister();
```

### `EventBusService`

Low-level service for managing the Vert.x EventBus connection.

#### `new EventBusService(socketPath?: string)`

Creates an EventBus service instance.

#### `waitUntilOpen(timeoutMs?: number): Promise<void>`

Waits for the EventBus to be open with optional timeout.

```typescript
await eventBusService.waitUntilOpen(5000);
```

#### `onOpen(listener: () => void): () => void`

Registers a callback invoked when EventBus opens. Returns cleanup function.

```typescript
const unsubscribe = eventBusService.onOpen(() => {
  console.log('Connected');
});

unsubscribe(); // cleanup
```

#### `onClose(listener: () => void): () => void`

Registers a callback invoked when EventBus closes. Returns cleanup function.

```typescript
eventBusService.onClose(() => console.log('Disconnected'));
```

## Architecture

The library uses a two-service architecture:

1. **EventBusService** - Manages the Vert.x WebSocket connection for one app instance
2. **HookService** - Manages hook registrations with smart refresh and auto-reconnection

Benefits:

- Clean separation of concerns
- Robust auto-recovery on network interruptions
- Smart hook refresh (only refreshes hooks nearing expiry)

## Configuration

### Socket Path

By default, the EventBus connects to `/server/event/v1/sock`. To override, pass the path
to the `EventBusService` constructor and inject it into `HookService`:

```typescript
const eventBusService = new EventBusService('/custom/event/socket');
const hookService = new HookService(eventBusService);
```

## Error Handling

### Hook Registration Failure

If hook registration fails (network error, etc.), the promise rejects:

```typescript
try {
  await new HookService().listen(def, callback);
} catch (err) {
  console.error('Failed to register hook:', err);
}
```

### Connection Timeout

If EventBus takes longer than timeout to open, promise rejects.

## Performance

- Smart hook refresh: Only refreshes hooks nearing expiry (not all on fixed interval)
- Deduplicates concurrent registration attempts
- Proper cleanup sequencing to prevent orphaned hooks
- Auto-reconnection with handler rebinding

## Development

### Build

```bash
npm run build
```

Outputs:

- `dist/index.js` / `dist/index.cjs` - ESM + CJS build for bundler-based consumers
- `dist/index.d.ts` - TypeScript declarations
- `dist/gateleen-hook-ts.browser.global.js` - self-contained IIFE bundle (all dependencies
  inlined, including `vertx3-eventbus-client`) for plain `<script>` tag usage, exposing the
  `GateleenHookTs` global (used by the `gateleen-playground` `hooktest-ts.html` demo page)

### Test

```bash
npm test
npm run test:ui        # Interactive UI
npm run test:coverage  # Coverage report
```

### Lint

```bash
npm run lint
```

## Browser Support

- Modern browsers with ES2022 support
- Requires EventBus WebSocket endpoint (Gateleen)
