# gateleen-hook-ts

Framework-agnostic TypeScript client for [Gateleen](https://github.com/swisspush/gateleen) webhooks.

## Features

- ✅ **Framework Agnostic** - Works with Angular 22, WebComponents, vanilla JS, or any framework
- ✅ **TypeScript** - Full type safety with generic types
- ✅ **Single Dependency** - Only depends on `vertx3-eventbus-client`
- ✅ **Modern** - Uses async/await, native Fetch API, ES2022

## Installation

```bash
npm install gateleen-hook-ts
```

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
import { HookService, HttpMethods } from 'gateleen-hook-ts';

@Service()
export class UserService {
  private readonly hooks = inject(GateleenHookService);
  readonly lastUser = signal<UserData | null>(null);

  setupHook() {
    void this.hooks.listen<UserData>(
      { path: '/api/users', methods: [HttpMethods.PUT], fetch: true },
      (user, params) => {
        this.lastUser.set(user);
      }
    );
  }
}
```

### WebComponent

```typescript
import { HookService, HttpMethods } from 'gateleen-hook-ts';

class UserList extends HTMLElement {
  private readonly hooks = new HookService();

  connectedCallback() {
    void this.hooks.listen<UserData>(
      { path: '/api/users', methods: [HttpMethods.PUT], fetch: true },
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

### Vanilla JavaScript

```javascript
import { HookService, HttpMethods } from 'gateleen-hook-ts';

const hooks = new HookService();

await hooks.listen(
  { path: '/api/data', methods: [HttpMethods.PUT], fetch: true },
  (payload, params) => console.log('Updated:', payload)
);

// When you're done with the instance:
hooks.dispose();
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
    methods: [HttpMethods.PUT, HttpMethods.POST],
    fetch: true
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

## Types

### `HookDefinition`

```typescript
interface HookDefinition {
  path: string;              // Resource path (e.g., '/api/users')
  methods: HttpMethods[];    // HTTP methods to trigger on
  fetch?: boolean;           // Fetch current state on registration
}
```

### `CallbackParams`

```typescript
interface CallbackParams {
  uri: string;              // URI that triggered the event
  headers: Record<string, string>;  // Request headers
  method: HttpMethods;      // HTTP method (PUT, POST, DELETE)
  channelId: string;        // Unique channel ID
}
```

### `HttpMethods`

```typescript
enum HttpMethods {
  PUT = 'PUT',
  POST = 'POST',
  DELETE = 'DELETE'
}
```

## Architecture

The library uses a two-service architecture:

1. **EventBusService** - Manages the Vert.x WebSocket connection for one app instance
2. **HookService** - Manages hook registrations with smart refresh and auto-reconnection

Benefits:
- Clean separation of concerns
- EventBusService can be reused for other services
- Robust auto-recovery on network interruptions
- Smart hook refresh (only refreshes hooks nearing expiry)

## Configuration

### Socket Path

By default, the EventBus connects to `/server/event/v1/sock`. To override:

```typescript
globalThis.GATELEEN_SOCKET_PATH = '/custom/event/socket';
```

Create the service before using it.

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

If EventBus takes longer than timeout to open, promise rejects:

```typescript
try {
  await eventBusService.waitUntilOpen(3000); // 3 second timeout
} catch (err) {
  console.error('EventBus connection timeout');
}
```

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
