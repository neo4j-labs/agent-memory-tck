/**
 * Transport abstraction layer.
 *
 * The Transport interface decouples the memory client from the wire protocol.
 * Two transports ship in-box:
 *
 *   - BridgeTransport — speaks the TCK bridge protocol (POST /{snake_method},
 *     snake_case JSON). Used for conformance testing and the local reference
 *     adapter.
 *
 *   - RestTransport — speaks the hosted REST API at https://memory.neo4jlabs.com/v1
 *     (camelCase JSON, REST topology). Used for production deployments.
 */

export interface Transport {
  /** Send a request to the backend and return the parsed response. */
  request<T>(method: string, params: Record<string, unknown>): Promise<T>;

  /** Establish the connection. */
  connect(): Promise<void>;

  /** Close the connection and release resources. */
  close(): Promise<void>;
}

export { BridgeTransport } from "./bridge.js";
export type { BridgeTransportOptions } from "./bridge.js";
export { RestTransport } from "./rest.js";
export type { RestTransportOptions, TokenProvider } from "./rest.js";

// ----- Backwards compat: HttpTransport == BridgeTransport ------------------
import { BridgeTransport, type BridgeTransportOptions } from "./bridge.js";
/** @deprecated Use {@link BridgeTransport} (renamed in v0.2). */
export const HttpTransport = BridgeTransport;
export type HttpTransportOptions = BridgeTransportOptions;
