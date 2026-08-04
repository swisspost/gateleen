import { HttpMethods } from './types.ts';

export interface HookMessage<TPayload> {
  body: {
    payload: TPayload;
    uri: string;
    headers: [string, string][];
    method: HttpMethods;
  };
}
