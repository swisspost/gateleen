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
      service.getEventBus().onopen?.();

      const result = await service.waitUntilOpen(100);
      expect(result).toBeUndefined();
    });

    it('should reject on timeout', async () => {
      const service = new EventBusService();

      await expect(service.waitUntilOpen(50)).rejects.toThrow(/EventBus open timeout/);
    });
  });

  describe('onOpen', () => {
    it('should register and call listener when EventBus opens', async () => {
      const service = new EventBusService();
      const listener = vi.fn();
      const unsubscribe = service.onOpen(listener);

      service.getEventBus().onopen?.();

      await Promise.resolve();
      expect(listener).toHaveBeenCalled();
      unsubscribe();
    });

    it('should unsubscribe when cleanup function is called', () => {
      const service = new EventBusService();
      const listener = vi.fn();
      const unsubscribe = service.onOpen(listener);

      unsubscribe();

      service.getEventBus().onopen?.();

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe('onClose', () => {
    it('should call listener when EventBus closes', () => {
      const service = new EventBusService();
      const listener = vi.fn();
      service.onClose(listener);

      service.getEventBus().onclose?.();

      expect(listener).toHaveBeenCalled();
    });
  });
});
