import { describe, it, expect, beforeEach, vi } from 'vitest';
import { EventBusService } from './event-bus-service.js';

describe('EventBusService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('getEventBus', () => {
    it('should return the EventBus client', () => {
      const service = new EventBusService();
      expect(service.getEventBus()).toBeDefined();
    });

    it('should enable automatic reconnection so a dropped connection is re-established', () => {
      const service = new EventBusService();
      const eventBus = service.getEventBus() as unknown as {
        enableReconnect: (enable: boolean) => void;
      };

      expect(eventBus.enableReconnect).toHaveBeenCalledWith(true);
    });
  });

  describe('waitUntilOpen', () => {
    it('should resolve immediately if already open', async () => {
      const service = new EventBusService();
      await triggerOpen(service);

      const result = await service.waitUntilOpen(100);
      expect(result).toBeUndefined();
    });

    it('should reject on timeout', async () => {
      vi.useFakeTimers();
      try {
        const service = new EventBusService();

        const assertion = expect(service.waitUntilOpen(50)).rejects.toThrow('EventBus open timeout after 50ms');
        await vi.advanceTimersByTimeAsync(50);
        await assertion;
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('onOpen', () => {
    it('should register and call listener when EventBus opens', async () => {
      const service = new EventBusService();
      const listener = vi.fn();
      const unsubscribe = service.onOpen(listener);

      await triggerOpen(service);
      expect(listener).toHaveBeenCalled();
      unsubscribe();
    });

    it('should unsubscribe when cleanup function is called', async () => {
      const service = new EventBusService();
      const listener = vi.fn();
      const unsubscribe = service.onOpen(listener);

      unsubscribe();

      await triggerOpen(service);

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe('onClose', () => {
    it('should call listener when EventBus closes', async () => {
      const service = new EventBusService();
      const listener = vi.fn();
      service.onClose(listener);

      await triggerClose(service);

      expect(listener).toHaveBeenCalled();
    });
  });
});

async function triggerOpen(service: EventBusService): Promise<void> {
  (service.getEventBus() as unknown as { triggerOpen(): void }).triggerOpen();
  await Promise.resolve(); // flush the mock's async onopen dispatch, mirroring real SockJS timing
}

async function triggerClose(service: EventBusService): Promise<void> {
  (service.getEventBus() as unknown as { triggerClose(): void }).triggerClose();
  await Promise.resolve(); // flush the mock's async onclose dispatch, mirroring real SockJS timing
}
