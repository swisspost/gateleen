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

The Cucumber/Selenium scenarios in `gateleen-test/.../hookjs` and
`.../hookts` aren't part of the default `mvn install` — they need a
built reactor, a running playground server with data uploaded, and a
real Chrome + chromedriver. Full copy-paste script (adjust the 4 paths
at the top):

```powershell
$mvn = 'C:\path\to\mvn.cmd'                # find: gci C:\ -Filter mvn.cmd -Recurse -Depth 8 -ea SilentlyContinue
$m2 = "$env:USERPROFILE\.m2\repository"
$chromeDriver = 'C:\path\to\chromedriver.exe'  # download: https://googlechromelabs.github.io/chrome-for-testing/
$redisCli = 'C:\path\to\redis-cli.exe'      # find: gci C:\ -Filter redis-*.exe -Recurse -Depth 8 -ea SilentlyContinue
cd C:\work\gateleen

# Check first — Redis (6379) / playground (7012) are often already running; reuse them, don't start dupes.
(Test-NetConnection 127.0.0.1 -Port 6379 -WarningAction SilentlyContinue).TcpTestSucceeded
(Test-NetConnection 127.0.0.1 -Port 7012 -WarningAction SilentlyContinue).TcpTestSucceeded

# 1) Install the reactor gateleen-test needs (once, or after module code changes).
#    If -o fails with "maven-install-plugin ... could not be resolved" (fresh .m2), re-run once without -o.
& $mvn "-Dmaven.repo.local=$m2" -pl gateleen-test -am install -DskipTests -o

# 2) Build & start the playground (needs network the first time, for maven-shade-plugin deps).
& $mvn "-Dmaven.repo.local=$m2" -pl gateleen-playground -am install -DskipTests
Start-Process -FilePath java -WorkingDirectory gateleen-playground\target -ArgumentList "-jar","playground.jar" `
  -RedirectStandardOutput "$env:TEMP\playground-out.log" `
  -RedirectStandardError "$env:TEMP\playground-err.log" -PassThru |
  ForEach-Object { $_.Id } | Out-File "$env:TEMP\playground.pid"
# -> http://localhost:7012/playground returning 404 confirms it's up (storage is empty until step 3).

# 3) Upload static pages/config (once per fresh playground start).
& $mvn "-Dmaven.repo.local=$m2" -pl gateleen-playground deploy -PuploadStaticFiles -DskipTests
curl http://localhost:7012/playground/hooktest-ts.html  # expect 200

# 4) Run the tests (only run under the uiIntegrationTests profile; add -Dtest=HookTsTest or HookJsTest to narrow down).
& $mvn "-Dmaven.repo.local=$m2" -pl gateleen-test test -PuiIntegrationTests "-Dsel_chrome_driver=$chromeDriver" -o
# Expect: BUILD SUCCESS, Tests run: 140, Failures: 0, Errors: 0

# 5) Clean up — tests leave data in Redis, always flush after:
& $redisCli FLUSHALL
Stop-Process -Id (Get-Content "$env:TEMP\playground.pid") -Force
```

### Gotchas

- Never `mvn install` the whole repo (needs VPN artifactory access) —
  always scope with `-pl <module> -am`.
- A shared/dirty Redis or a stale playground process on port 7012 causes
  the event-bus SockJS bridge to 404 (`/server/event/v1/sock/info`) and
  every "we see the message ..." step to time out — looks like a code
  regression but usually isn't. If in doubt, flush Redis and restart the
  playground.
- If `HookJsTest` and `HookTsTest` fail with the *same* symptom, it's
  environmental, not a gateleen-hook-ts regression.

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
