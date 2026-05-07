/**
 * BridgeTransport — TCK bridge protocol transport.
 *
 * Speaks the bridge wire format (POST {endpoint}/{snake_case_method}) used by
 * conformance servers and the local reference adapter. Compatible with every
 * fetch-capable runtime (Node 20+, Bun, Deno, Workers, Edge).
 */

import { AuthenticationError, ConnectionError, TransportError } from "../errors.js";
import type { Transport } from "./index.js";

/** Strip trailing `/` from a URL without using a polynomial regex. */
function trimTrailingSlashes(s: string): string {
  let end = s.length;
  while (end > 0 && s.charCodeAt(end - 1) === 47) end--;
  return s.slice(0, end);
}

export interface BridgeTransportOptions {
  /** Base URL of the bridge endpoint (no trailing /v1). */
  endpoint: string;

  /** API key for Bearer auth. Optional for local bridge servers. */
  apiKey?: string;

  /** Request timeout in milliseconds. Default: 30000. */
  timeout?: number;

  /** Additional headers to include in every request. */
  headers?: Record<string, string>;
}

export class BridgeTransport implements Transport {
  private readonly endpoint: string;
  private readonly apiKey?: string;
  private readonly timeout: number;
  private readonly headers: Record<string, string>;

  constructor(options: BridgeTransportOptions) {
    this.endpoint = trimTrailingSlashes(options.endpoint);
    this.apiKey = options.apiKey;
    this.timeout = options.timeout ?? 30_000;
    this.headers = options.headers ?? {};
  }

  async connect(): Promise<void> {
    try {
      const response = await fetch(`${this.endpoint}/setup`, {
        method: "POST",
        headers: this.buildHeaders(),
        signal: AbortSignal.timeout(this.timeout),
      });
      if (response.status === 401 || response.status === 403) {
        throw new AuthenticationError(
          `Authentication failed: ${response.status} ${response.statusText}`,
        );
      }
    } catch (error) {
      if (error instanceof AuthenticationError) throw error;
      if (error instanceof TypeError) {
        throw new ConnectionError(
          `Failed to connect to ${this.endpoint}: ${(error as Error).message}`,
          { cause: error },
        );
      }
      if (error instanceof DOMException && error.name === "TimeoutError") {
        throw new ConnectionError(
          `Connection to ${this.endpoint} timed out after ${this.timeout}ms`,
          { cause: error },
        );
      }
      throw error;
    }
  }

  async close(): Promise<void> {}

  async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    const url = `${this.endpoint}/${method}`;

    const body: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        body[key] = value;
      }
    }

    let response: Response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: this.buildHeaders(),
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(this.timeout),
      });
    } catch (error) {
      if (error instanceof TypeError) {
        throw new ConnectionError(
          `Request to ${url} failed: ${(error as Error).message}`,
          { cause: error },
        );
      }
      throw error;
    }

    if (response.status === 401 || response.status === 403) {
      throw new AuthenticationError(
        `Authentication failed: ${response.status} ${response.statusText}`,
      );
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const text = await response.text();

    if (!response.ok) {
      let errorBody: unknown;
      try {
        errorBody = JSON.parse(text);
      } catch {
        errorBody = text;
      }
      const errorMessage =
        typeof errorBody === "object" && errorBody !== null && "error" in errorBody
          ? String((errorBody as Record<string, unknown>)["error"])
          : `HTTP ${response.status}`;
      throw new TransportError(`${method} failed: ${errorMessage}`, response.status, errorBody);
    }

    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  private buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...this.headers,
    };
    if (this.apiKey) {
      headers["Authorization"] = `Bearer ${this.apiKey}`;
    }
    return headers;
  }
}
