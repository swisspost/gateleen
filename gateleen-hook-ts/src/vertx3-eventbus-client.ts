import EventBus from 'vertx3-eventbus-client';

export type MessageHandler = (error: unknown, message: unknown) => void;

export default EventBus;
