export type MessageHandler = (error: unknown, message: unknown) => void;

export default class EventBus {
  constructor(url: string);
  onopen: (() => void) | null;
  onclose: (() => void) | null;
  registerHandler(address: string, handler: MessageHandler): void;
  unregisterHandler(address: string, handler: MessageHandler): void;
}
