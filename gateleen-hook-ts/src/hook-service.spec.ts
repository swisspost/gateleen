import { describe, it, expect, beforeEach, vi } from 'vitest';
import { HookService } from './hook-service.js';
import { EventBusService } from './event-bus-service.js';
import { HookDefinition } from './types.js';
import { createOkResponse } from './test-helpers.js';

describe('HookService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn() as typeof fetch;
  });

  function openEventBus(service: EventBusService): void {
    const eventBus = service.getEventBus() as {
      readyState?: number;
      state?: string;
      onopen?: (() => void) | null;
    };

    eventBus.readyState = 1;
    eventBus.state = 'OPEN';
    eventBus.onopen?.();
  }

  const def: HookDefinition = {
    path: '/api/test',
    methods: ['PUT'],
    fetch: 'none',
  };

  async function simulateOpenEventBusConnection() {
    const eventBusService = new EventBusService();
    openEventBus(eventBusService);
    const service = new HookService(eventBusService);
    const callback = vi.fn();

    vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

    const deregisterer = await service.listen(def, callback);
    return { eventBusService, deregisterer };
  }

  describe('listen', () => {
    it('should require hookDefinition with path and methods', async () => {
      const { eventBusService, deregisterer } = await simulateOpenEventBusConnection();
      expect(deregisterer).toBeDefined();
      expect(typeof deregisterer.deregister).toBe('function');
      expect(eventBusService.getEventBus().registerHandler).toHaveBeenCalledTimes(1);
    });

    it('should accept generic type parameter', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      interface TestData {
        id: number;
        name: string;
      }

      const callback: (payload: TestData, params?: unknown) => void = vi.fn();

      vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

      await expect(service.listen<TestData>(def, callback)).resolves.toBeDefined();
    });

    it('deregisterer should have deregister method', async () => {
      const { deregisterer } = await simulateOpenEventBusConnection();
      expect(typeof deregisterer.deregister).toBe('function');
    });

    it('should include filter in the remote hook registration payload when provided', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

      const BASE_URL = '/api/messages/public';
      await service.listen(
        {
          path: BASE_URL,
          methods: ['PUT'],
          fetch: 'none',
          filter: BASE_URL + '([^/]+)/message',
        },
        vi.fn(),
      );

      const putCall = vi
        .mocked(global.fetch)
        .mock.calls.find((call) => (call[1] as RequestInit | undefined)?.method === 'PUT');
      expect(putCall).toBeDefined();
      const body = JSON.parse((putCall![1] as RequestInit).body as string) as {
        filter?: string;
      };
      expect(body.filter).toBe(BASE_URL + '([^/]+)/message');
    });

    it('should set the x-expire-after header and the notification queue expireAfter body field on registration', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

      await service.listen({ path: '/api/expiry-test', methods: ['PUT'], fetch: 'none' }, vi.fn());

      const putCall = vi
        .mocked(global.fetch)
        .mock.calls.find((call) => (call[1] as RequestInit | undefined)?.method === 'PUT');
      expect(putCall).toBeDefined();

      // Controls server-side expiry of the hook registration resource itself.
      const headers = (putCall![1] as RequestInit).headers as Record<string, string>;
      expect(headers['x-expire-after']).toBe('60');

      // Controls how long a triggered notification may sit in the transient queue.
      const body = JSON.parse((putCall![1] as RequestInit).body as string) as { expireAfter?: number };
      expect(body.expireAfter).toBe(10);
    });

    it('should deliver the initial fetched state before any live events received while fetching', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      let resolveGet!: (response: Response) => void;
      const getResponse = new Promise<Response>((resolve) => {
        resolveGet = resolve;
      });

      vi.mocked(global.fetch).mockImplementation((_url, init) => {
        if (!init) {
          return getResponse; // the initial state GET
        }
        return Promise.resolve(createOkResponse()); // PUT registration
      });

      const received: unknown[] = [];
      const callback = vi.fn((payload: unknown) => received.push(payload));

      const listenPromise = service.listen({ path: '/api/live-test', methods: ['PUT'], fetch: 'single' }, callback);

      await vi.waitFor(() => {
        expect(eventBusService.getEventBus().registerHandler).toHaveBeenCalled();
      });

      const registerHandlerMock = eventBusService.getEventBus().registerHandler as unknown as ReturnType<typeof vi.fn>;
      const liveHandler = registerHandlerMock.mock.calls[0][1] as (err: unknown, message: unknown) => void;

      // Simulate a live event arriving over the EventBus while the initial
      // fetch's GET request is still pending.
      liveHandler(null, {
        body: { payload: { text: 'live' }, uri: '/api/live-test', headers: [], method: 'PUT' },
      });

      // The live event must be buffered, not delivered yet.
      expect(callback).not.toHaveBeenCalled();

      resolveGet(
        new Response(JSON.stringify({ text: 'initial' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

      await listenPromise;

      expect(received).toEqual([{ text: 'initial' }, { text: 'live' }]);
    });
  });

  describe('dispose', () => {
    it('should deregister all active hooks', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

      await service.listen({ path: '/api/test-one', methods: ['PUT'], fetch: 'none' }, vi.fn());
      await service.listen({ path: '/api/test-two', methods: ['PUT'], fetch: 'none' }, vi.fn());

      service.dispose();

      const methods = vi
        .mocked(global.fetch)
        .mock.calls.map((call) => (call[1] as RequestInit | undefined)?.method ?? 'GET');
      expect(methods.filter((value) => value === 'PUT')).toHaveLength(2);
      await vi.waitFor(() => {
        const deleteCount = vi
          .mocked(global.fetch)
          .mock.calls.map((call) => (call[1] as RequestInit | undefined)?.method ?? 'GET')
          .filter((value) => value === 'DELETE').length;
        expect(deleteCount).toBe(2);
      });

      const eventBus = eventBusService.getEventBus();
      expect(eventBus.unregisterHandler).toHaveBeenCalledTimes(2);
    });
  });
});
