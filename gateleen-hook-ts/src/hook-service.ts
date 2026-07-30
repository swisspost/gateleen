import EventBus, { type MessageHandler } from 'vertx3-eventbus-client';
import { EventBusService } from './event-bus-service.js';
import {
  CallbackParams,
  HookDefinition,
  HookDeregisterer,
  HttpMethods
} from './types.js';

/**
 * Manages Gateleen HTTP hook registrations and routes incoming events
 * to local EventBus handlers.
 *
 * The service keeps remote hooks alive by refreshing them before expiration
 * and provides an idempotent local + remote cleanup mechanism.
 */
export class HookService {
  private static readonly HOOK_EXPIRATION_AFTER_MINUTES = 1;
  private static readonly HOOK_REFRESH_CHECK_INTERVAL_MS = 20_000;
  private static readonly HOOK_REFRESH_LEEWAY_MS = 15_000;

  private readonly eventBusService: EventBusService;
  private readonly eventBus: EventBus;
  private readonly refreshTimer: ReturnType<typeof setInterval>;
  private readonly unsubscribeOpen: () => void;
  private readonly unsubscribeClose: () => void;
  private readonly registrations = new Map<string, ManagedRegistration>();

  constructor(eventBusService = new EventBusService()) {
    this.eventBusService = eventBusService;
    this.eventBus = eventBusService.getEventBus();

    // Periodically refresh hook registrations before they expire.
    this.refreshTimer = setInterval(() => {
      void this.refreshHooks();
    }, HookService.HOOK_REFRESH_CHECK_INTERVAL_MS);
    this.refreshTimer.unref?.();

    this.unsubscribeOpen = this.eventBusService.onOpen(() => {
      void this.rebindAllHandlers();
    });
    this.unsubscribeClose = this.eventBusService.onClose(() => {
      this.unbindAllHandlers();
    });
  }

  /**
   * Registers a hook and subscribes to matching payload updates.
   *
   * @typeParam TPayload Expected payload type delivered by the backend event.
   * @param def Hook definition with resource path and HTTP methods.
   * @param callback Called for each incoming payload.
   * @returns Promise resolving to deregistration handle to stop listening and remove the remote hook.
   * @throws Error if initial registration fails.
   *
   * @example
   * const service = new HookService();
   * const deregisterer = await service.listen<UserData>(
   *   { path: '/api/users', methods: [HttpMethods.PUT], fetch: true },
   *   (user, params) => console.log('Updated:', user)
   * );
   *
   * // Later: cleanup
   * deregisterer.deregister();
   */
  async listen<TPayload = unknown>(
    def: HookDefinition,
    callback: (payload: TPayload, params?: CallbackParams) => void
  ): Promise<HookDeregisterer> {
    const id = HookService.createHookId();
    const address = `event/channels/${id}`;

    await this.eventBusService.waitUntilOpen();

    const handler: MessageHandler = (err, request) => {
      if (err) {
        console.error(`EventBus handler failed for hook ${id}:`, err);
        return;
      }

      const message = request as HookMessage<TPayload>;
      const resourcePath = HookService.getHeaderValue(message.body.headers, 'resource_path') ?? message.body.uri;
      callback(message.body.payload, {
        uri: resourcePath,
        headers: message.body.headers,
        method: message.body.method,
        channelId: id
      });
    };

    const registration: Registration = {
      id,
      handler,
      definition: def
    };

    const managedRegistration: ManagedRegistration = {
      registration,
      address,
      expiresAt: 0,
      disposed: false,
      handlerBound: false
    };

    this.registrations.set(id, managedRegistration);
    this.bindHandler(managedRegistration);

    try {
      await this.ensureRegistered(managedRegistration);
    } catch (err) {
      this.eventBus.unregisterHandler(address, handler);
      this.registrations.delete(id);
      throw err;
    }

    if (def.fetch) {
      try {
        if (HookService.isCollectionPath(def.path)) {
          await this.fetchCollectionAndDispatch<TPayload>(def.path, handler);
        } else {
          const initial = await this.fetch<TPayload>(def.path);
          if (initial !== null) {
            handler(null, {
              body: {
                payload: initial,
                uri: def.path,
                headers: [],
                method: HttpMethods.PUT
              }
            } as unknown as any);
          }
        }
      } catch (err) {
        this.deregister(id);
        throw err;
      }
    }

    return {
      deregister: () => {
        this.deregister(id);
      }
    };
  }

  /**
   * Creates a unique hook id with cryptographic randomness when available.
   */
  private static createHookId(): string {
    const uuidGenerator = globalThis.crypto?.randomUUID;
    if (uuidGenerator) {
      return `gateleen-hook-${uuidGenerator.call(globalThis.crypto)}`;
    }

    return `gateleen-hook-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  /**
   * Extracts the application context (e.g. protocol/host + first path segment)
   * from a hooked resource path, so the hook destination can be routed to the
   * same backend context as the hooked resource itself. Mirrors the legacy
   * gateleen-hook-js behavior: `/eagle/vehicle/trailer/v1/status` -> `/eagle`.
   */
  private static extractContext(path: string): string {
    const match = /^(https?:\/\/[^/]+)?\/[^/]+/.exec(path);
    return match ? match[0] : '';
  }

  /**
   * A hooked path ending in "/" designates a collection (mirrors gateleen-hook-js
   * semantics), where each contained sub-resource should trigger its own callback
   * invocation rather than delivering the whole collection as a single payload.
   */
  private static isCollectionPath(path: string): boolean {
    return /\/$/.test(path);
  }

  /**
   * Fetches the current state of a hooked collection resource (using
   * `?expand=1` to inline sub-resources) and invokes the handler once per
   * contained item, mirroring gateleen-hook-js's collection "fetch" behavior.
   */
  private async fetchCollectionAndDispatch<TPayload>(
    path: string,
    handler: MessageHandler
  ): Promise<void> {
    const collectionPath = path.replace(/\/$/, '');
    const collectionName = collectionPath.split('/').pop() ?? '';
    const response = await fetch(`${collectionPath}/?expand=1`);

    if (response.status === 404) {
      return;
    }
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = (await response.json()) as Record<string, Record<string, TPayload>>;
    const entries = data[collectionName] ?? {};

    for (const [key, value] of Object.entries(entries)) {
      handler(null, {
        body: {
          payload: value,
          uri: `${collectionPath}/${key}`,
          headers: [],
          method: HttpMethods.PUT
        }
      } as unknown as any);
    }
  }

  /**
   * Looks up a header value (case-insensitive) in the [name, value] tuple
   * array delivered by the Gateleen event bus payload. Used to find the
   * `resource_path` header, which carries the concrete resource path for
   * events triggered on a hooked collection (the plain `uri` field only
   * reflects the hooked collection path itself, not the specific sub-resource).
   */
  private static getHeaderValue(headers: [string, string][] | undefined, name: string): string | undefined {
    const entry = headers?.find(([key]) => key.toLowerCase() === name.toLowerCase());
    return entry ? entry[1] : undefined;
  }

  /**
   * Refreshes all tracked hooks that are close to expiration.
   */
  private async refreshHooks(): Promise<void> {
    const refreshThreshold = Date.now() + HookService.HOOK_REFRESH_LEEWAY_MS;
    const registrationsToRefresh = [...this.registrations.values()].filter(
      (value) => !value.disposed && value.expiresAt <= refreshThreshold
    );

    for (const managedRegistration of registrationsToRefresh) {
      try {
        await this.ensureRegistered(managedRegistration);
      } catch (err) {
        console.error(`Failed to refresh hook ${managedRegistration.registration.id}:`, err);
      }
    }
  }

  /**
   * Re-registers all local handlers after the EventBus reconnects.
   */
  private async rebindAllHandlers(): Promise<void> {
    await this.eventBusService.waitUntilOpen();

    for (const managedRegistration of this.registrations.values()) {
      if (managedRegistration.disposed || managedRegistration.handlerBound) {
        continue;
      }

      this.bindHandler(managedRegistration);
    }
  }

  /**
   * Unregisters all local handlers when the EventBus disconnects.
   */
  private unbindAllHandlers(): void {
    for (const managedRegistration of this.registrations.values()) {
      if (!managedRegistration.handlerBound) {
        continue;
      }

      try {
        this.eventBus.unregisterHandler(
          managedRegistration.address,
          managedRegistration.registration.handler
        );
      } catch (err) {
        console.warn(
          `Failed to unbind local EventBus handler for hook ${managedRegistration.registration.id}:`,
          err
        );
      }
      managedRegistration.handlerBound = false;
    }
  }

  /**
   * Ensures a single local EventBus handler is attached for the registration.
   */
  private bindHandler(managedRegistration: ManagedRegistration): void {
    if (managedRegistration.disposed || managedRegistration.handlerBound) {
      return;
    }

    this.eventBus.registerHandler(
      managedRegistration.address,
      managedRegistration.registration.handler
    );
    managedRegistration.handlerBound = true;
  }

  /**
   * Ensures the given registration exists remotely and updates local expiry.
   *
   * Concurrent registration calls for the same hook are deduplicated.
   */
  private async ensureRegistered(managedRegistration: ManagedRegistration): Promise<void> {
    if (managedRegistration.disposed) {
      return;
    }

    if (!managedRegistration.inFlightRegistration) {
      managedRegistration.inFlightRegistration = this.registerRemote(
        managedRegistration.registration
      )
        .then((expiresAt) => {
          managedRegistration.expiresAt = expiresAt;
        })
        .finally(() => {
          managedRegistration.inFlightRegistration = undefined;
        });
    }

    await managedRegistration.inFlightRegistration;
  }

  /**
   * Creates or updates the remote Gateleen hook registration.
   *
   * @returns Unix timestamp in milliseconds when the hook is expected to expire.
   */
  private async registerRemote(registration: Registration): Promise<number> {
    // Strip any trailing slash before building the hook URL — a path ending in
    // "/" designates a collection hook (see isCollectionPath), but the actual
    // server-side hook registration must not have a doubled slash before
    // "/_hooks/...", which would break hook matching (mirrors gateleen-hook-js,
    // which strips the trailing slash from the path before registering).
    const normalizedPath = registration.definition.path.replace(/\/$/, '');
    const hookUrl = `${normalizedPath}/_hooks/listeners/http/${registration.id}`;
    const context = HookService.extractContext(normalizedPath);
    const hookDestination = `${context}/server/event/v1/channels/${registration.id}`;
    const expiresAt =
      Date.now() + HookService.HOOK_EXPIRATION_AFTER_MINUTES * 60 * 1000;

    console.log(
      `Registering hook ${registration.id} for [${registration.definition.methods.join(', ')}] ${registration.definition.path}`
    );

    const dto: RegisterHookDto = {
      methods: registration.definition.methods,
      destination: hookDestination,
      expireAfter: HookService.HOOK_EXPIRATION_AFTER_MINUTES,
      headers: [
        {
          header: 'x-queue-mode',
          value: 'transient'
        }
      ],
      filter: registration.definition.filter
    };

    const response = await fetch(hookUrl, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(dto)
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return expiresAt;
  }

  /**
   * Removes a local registration and triggers best-effort remote cleanup.
   */
  private deregister(id: string): void {
    const managedRegistration = this.registrations.get(id);
    if (!managedRegistration) {
      return;
    }

    managedRegistration.disposed = true;
    this.registrations.delete(id);
    this.unbindLocalHandler(managedRegistration);

    const completion = managedRegistration.inFlightRegistration ?? Promise.resolve();
    void completion
      .catch(() => {
        // Ignore registration failures here. We still attempt remote cleanup.
      })
      .then(() => this.deleteRemoteHook(managedRegistration.registration))
      .catch((err) => {
        console.warn(`Failed to remove remote hook ${id}:`, err);
      });
  }

  /**
   * Stops background refresh work, deregisters active hooks, and detaches EventBus lifecycle listeners.
   */
  public dispose(): void {
    for (const id of [...this.registrations.keys()]) {
      this.deregister(id);
    }

    clearInterval(this.refreshTimer);
    this.unsubscribeOpen();
    this.unsubscribeClose();
  }

  /**
   * Deletes the remote hook endpoint.
   *
   * HTTP 404 is treated as success because the desired end-state is achieved.
   */
  private async deleteRemoteHook(registration: Registration): Promise<void> {
    const normalizedPath = registration.definition.path.replace(/\/$/, '');
    const hookUrl = `${normalizedPath}/_hooks/listeners/http/${registration.id}`;
    const response = await fetch(hookUrl, { method: 'DELETE' });

    if (!response.ok && response.status !== 404) {
      throw new Error(
        `Failed to delete hook ${registration.id}. Status: ${response.status}`
      );
    }
  }

  /**
   * Detaches a single local EventBus handler if it is currently bound.
   */
  private unbindLocalHandler(managedRegistration: ManagedRegistration): void {
    if (!managedRegistration.handlerBound) {
      return;
    }

    try {
      this.eventBus.unregisterHandler(
        managedRegistration.address,
        managedRegistration.registration.handler
      );
    } catch (err) {
      console.warn(
        `Failed to unbind local EventBus handler for hook ${managedRegistration.registration.id}:`,
        err
      );
    } finally {
      managedRegistration.handlerBound = false;
    }
  }

  private fetch<TPayload>(url: string): Promise<TPayload | null> {
    return fetch(url).then((response) => {
      if (response.status === 404) {
        return null;
      }

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      return response.json() as Promise<TPayload>;
    });
  }
}

interface RegisterHookDto {
  methods: HttpMethods[];
  destination: string;
  expireAfter: number;
  headers: { header: string; value: string }[];
  filter?: string;
}

interface Registration {
  id: string;
  handler: MessageHandler;
  definition: HookDefinition;
}

interface ManagedRegistration {
  registration: Registration;
  address: string;
  expiresAt: number;
  disposed: boolean;
  handlerBound: boolean;
  inFlightRegistration?: Promise<void>;
}

interface HookMessage<TPayload> {
  body: {
    payload: TPayload;
    uri: string;
    headers: [string, string][];
    method: HttpMethods;
  };
}
