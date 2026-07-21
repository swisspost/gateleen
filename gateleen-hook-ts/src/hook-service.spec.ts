import { describe, it, expect, beforeEach, vi } from 'vitest';
import { HookService } from './hook-service.js';
import { EventBusService } from './event-bus-service.js';
import {HookDefinition, HttpMethods} from './types.js';
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
    methods: [HttpMethods.PUT],
    fetch: false
  };

  async function simulateOpenEventBusConnection() {
    const eventBusService = new EventBusService();
    openEventBus(eventBusService);
    const service = new HookService(eventBusService);
    const callback = vi.fn();

    vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

    const deregisterer = await service.listen(def, callback);
    return {eventBusService, deregisterer};
  }

  describe('listen', () => {
    it('should require hookDefinition with path and methods', async () => {
      const {eventBusService, deregisterer} = await simulateOpenEventBusConnection();
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
      const {deregisterer} = await simulateOpenEventBusConnection();
      expect(typeof deregisterer.deregister).toBe('function');
    });
  });

  describe('dispose', () => {
    it('should deregister all active hooks', async () => {
      const eventBusService = new EventBusService();
      openEventBus(eventBusService);
      const service = new HookService(eventBusService);

      vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

      await service.listen(
        { path: '/api/test-one', methods: [HttpMethods.PUT], fetch: false },
        vi.fn()
      );
      await service.listen(
        { path: '/api/test-two', methods: [HttpMethods.PUT], fetch: false },
        vi.fn()
      );

      service.dispose();

      const methods = vi.mocked(global.fetch).mock.calls.map(
        (call) => (call[1] as RequestInit | undefined)?.method ?? 'GET'
      );
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
