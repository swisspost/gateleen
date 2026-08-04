import EventBus from 'vertx3-eventbus-client';
import { HookMessage } from './hook-message.ts';

export type MessageHandler<T> = (error: unknown, message: HookMessage<T>) => void;

export default EventBus;
