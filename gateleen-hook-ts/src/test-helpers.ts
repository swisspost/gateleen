import { vi } from 'vitest';

export function createOkResponse(): Response {
  return new Response('{}', {
    status: 200,
    headers: {
      'Content-Type': 'application/json'
    }
  });
}

export class MockEventBus {
  public static nextReadyState = 0;
  public static instances: MockEventBus[] = [];

  public onopen: (() => void) | null = null;
  public onclose: (() => void) | null = null;
  public readyState: number;
  public state = 'CLOSED';
  public registerHandler = vi.fn();
  public unregisterHandler = vi.fn();

  constructor(_url: string) {
    this.readyState = MockEventBus.nextReadyState;
    this.state = this.readyState === 1 ? 'OPEN' : 'CLOSED';
    MockEventBus.instances.push(this);
  }

  public static reset(): void {
    MockEventBus.nextReadyState = 0;
    MockEventBus.instances = [];
  }
}
