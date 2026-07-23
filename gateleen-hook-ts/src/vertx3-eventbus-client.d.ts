export type MessageHandler = (error: unknown, message: unknown) => void;

export default class EventBus {
  constructor(url: string);
  onopen: (() => void) | null;
  onclose: (() => void) | null;
  registerHandler(address: string, handler: MessageHandler): void;
  unregisterHandler(address: string, handler: MessageHandler): void;
  /**
   * Enables or disables automatic reconnection. When enabled, the client
   * transparently re-opens the underlying WebSocket (with backoff) after an
   * unexpected close and fires `onopen` again once reconnected. Disabled by
   * default in the underlying vertx3-eventbus-client.
   */
  enableReconnect(enable: boolean): void;
}
