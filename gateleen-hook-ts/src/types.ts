/**
 * HTTP methods supported for hook registration.
 */
export enum HttpMethods {
  PUT = 'PUT',
  POST = 'POST',
  DELETE = 'DELETE',
}

/**
 * Defines which backend resource should trigger hook callbacks.
 */
export interface HookDefinition {
  /**
   * The resource path to hook (e.g., '/api/users')
   */
  path: string;

  /**
   * HTTP methods that trigger the hook
   */
  methods: HttpMethods[];

  /**
   * If true, fetch current state of the resource on registration
   */
  fetch: boolean;

  /**
   * Optional regular expression used by Gateleen to restrict which
   * sub-resources of a hooked collection trigger the hook.
   */
  filter?: string;
}

/**
 * Metadata provided with each hook event callback.
 */
export interface CallbackParams {
  /**
   * The URI that triggered this event
   */
  uri: string;

  /**
   * HTTP headers from the original request. Gateleen delivers these as an
   * array of [name, value] tuples (not a plain object) over the event bus.
   */
  headers: [string, string][];

  /**
   * HTTP method used (PUT, POST, DELETE)
   */
  method: HttpMethods;

  /**
   * Unique channel ID for this hook
   */
  channelId: string;
}

/**
 * Handle used by consumers to remove an active hook subscription.
 */
export interface HookDeregisterer {
  /**
   * Stops listening for events and cleans up remote hook.
   */
  deregister(): void;
}
