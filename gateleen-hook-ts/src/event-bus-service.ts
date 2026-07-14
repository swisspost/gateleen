import EventBus from 'vertx3-eventbus-client';

/**
 * Provides a Vert.x EventBus client and connection-state utilities.
 *
 * The service centralizes socket initialization and exposes a Promise-based
 * wait helper for callers that must register handlers only after the socket
 * is open.
 */
export class EventBusService {
  private readonly eventBus: EventBus;
  private open = false;
  private openWaiters: Array<() => void> = [];
  private openSubscribers: Array<() => void> = [];
  private closeSubscribers: Array<() => void> = [];

  /**
   * Creates a new EventBusService bound to the configured socket path.
   */
  constructor(
    socketPath = globalThis.GATELEEN_SOCKET_PATH || '/server/event/v1/sock'
  ) {
    this.eventBus = new EventBus(socketPath);

    this.eventBus.onopen = () => {
      console.log('Vert.x EventBus connected');
      this.open = true;
      this.notifyOpen();
    };

    this.eventBus.onclose = () => {
      console.log('Vert.x EventBus disconnected');
      this.open = false;
      this.notifyClose();
    };
  }

  /**
   * Returns the wrapped EventBus client.
   */
  public getEventBus(): EventBus {
    return this.eventBus;
  }

  /**
   * Resolves once the EventBus connection is open.
   *
   * @param timeoutMs Maximum time to wait before rejecting.
   */
  public waitUntilOpen(timeoutMs = 10000): Promise<void> {
    if (this.isOpen()) {
      return Promise.resolve();
    }

    return new Promise((resolve, reject) => {
      const listener = () => {
        clearTimeout(timeoutHandle);
        this.openWaiters = this.openWaiters.filter((value) => value !== listener);
        resolve();
      };

      const timeoutHandle = setTimeout(() => {
        this.openWaiters = this.openWaiters.filter((value) => value !== listener);
        reject(new Error(`EventBus open timeout after ${timeoutMs}ms`));
      }, timeoutMs);

      this.openWaiters.push(listener);
    });
  }

  /**
   * Registers a callback that is invoked whenever the EventBus becomes open.
   *
   * @returns Cleanup function that removes the subscription.
   */
  public onOpen(listener: () => void): () => void {
    this.openSubscribers.push(listener);

    if (this.open) {
      queueMicrotask(listener);
    }

    return () => {
      this.openSubscribers = this.openSubscribers.filter((value) => value !== listener);
    };
  }

  /**
   * Registers a callback that is invoked whenever the EventBus closes.
   *
   * @returns Cleanup function that removes the subscription.
   */
  public onClose(listener: () => void): () => void {
    this.closeSubscribers.push(listener);
    return () => {
      this.closeSubscribers = this.closeSubscribers.filter((value) => value !== listener);
    };
  }

  /**
   * Checks if the EventBus is currently open using local and runtime states.
   */
  private isOpen(): boolean {
    if (this.open) {
      return true;
    }

    const candidate = this.eventBus as EventBusRuntimeState;
    return candidate.readyState === 1 || candidate.state === 'OPEN';
  }

  /**
   * Notifies all callers currently waiting for an open EventBus connection.
   */
  private notifyOpen(): void {
    [...this.openSubscribers].forEach((listener) => listener());
    const waiters = [...this.openWaiters];
    this.openWaiters = [];
    waiters.forEach((listener) => listener());
  }

  /**
   * Notifies all callers currently waiting for a closed EventBus state.
   */
  private notifyClose(): void {
    [...this.closeSubscribers].forEach((listener) => listener());
  }
}

interface EventBusRuntimeState {
  readyState?: number;
  state?: string;
}
