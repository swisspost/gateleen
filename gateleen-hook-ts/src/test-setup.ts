import { vi } from 'vitest';

vi.mock('vertx3-eventbus-client', () => ({
  default: class MockEventBus {
    public onopen: (() => void) | null = null;
    public onclose: (() => void) | null = null;
    public readyState = 0;
    public state = 'CLOSED';
    public registerHandler = vi.fn();
    public unregisterHandler = vi.fn();
    public enableReconnect = vi.fn();

    constructor(_url: string) {}

    // Real SockJS fires onopen/onclose asynchronously once the underlying
    // socket actually connects/disconnects, never synchronously with the
    // code that registered the handler. Mimic that here via queueMicrotask
    // so tests exercise the same async timing as production.
    public triggerOpen(): void {
      queueMicrotask(() => this.onopen?.());
    }

    public triggerClose(): void {
      queueMicrotask(() => this.onclose?.());
    }
  },
}));
