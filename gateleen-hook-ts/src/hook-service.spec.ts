import { describe, it, expect, beforeEach, vi } from 'vitest';
import { HookService } from './hook-service.js';
import { EventBusService } from './event-bus-service.js';
import { HttpMethods } from './types.js';
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

  async function simulateOpenEventBusConnection() {
    const eventBusService = new EventBusService();
    openEventBus(eventBusService);
    const service = new HookService(eventBusService);
    const callback = vi.fn();

    vi.mocked(global.fetch).mockResolvedValue(createOkResponse());

    const def = {
      path: '/api/test',
      methods: [HttpMethods.PUT]
    };

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

      const def = {
        path: '/api/test',
        methods: [HttpMethods.PUT]
      };

      await expect(service.listen<TestData>(def, callback)).resolves.toBeDefined();
    });

    it('deregisterer should have deregister method', async () => {
      const {deregisterer} = await simulateOpenEventBusConnection();
      expect(typeof deregisterer.deregister).toBe('function');
    });
  });
});
