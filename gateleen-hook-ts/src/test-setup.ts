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
  },
}));
