/**
 * HTTP transport for the Cypherlite Cloud service.
 *
 * Uses only the standard fetch() API — compatible with Node.js 20+,
 * Bun, Deno, Cloudflare Workers, and Vercel Edge Runtime.
 * No node:-prefixed imports are used.
 */

import { AuthenticationError, ConnectionError, TransportError } from "../errors.js";
import type { Transport } from "./index.js";

export interface HttpTransportOptions {
  /** Base URL of the service endpoint. */
  endpoint: string;

  /** API key for authentication. */
  apiKey?: string;

  /** Request timeout in milliseconds. Default: 30000. */
  timeout?: number;

  /** Additional headers to include in every request. */
  headers?: Record<string, string>;
}

export class HttpTransport implements Transport {
  private readonly endpoint: string;
  private readonly apiKey?: string;
  private readonly timeout: number;
  private readonly headers: Record<string, string>;

  constructor(options: HttpTransportOptions) {
    this.endpoint = options.endpoint.replace(/\/+$/, "");
    this.apiKey = options.apiKey;
    this.timeout = options.timeout ?? 30_000;
    this.headers = options.headers ?? {};
  }

  async connect(): Promise<void> {
    // HTTP is stateless — no persistent connection to establish.
    // We do a lightweight health check to validate the endpoint.
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
      // AbortSignal.timeout throws a DOMException with name "TimeoutError"
      if (error instanceof DOMException && error.name === "TimeoutError") {
        throw new ConnectionError(
          `Connection to ${this.endpoint} timed out after ${this.timeout}ms`,
          { cause: error },
        );
      }
      throw error;
    }
  }

  async close(): Promise<void> {
    // HTTP is stateless — nothing to close.
  }

  async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    const url = `${this.endpoint}/${method}`;

    // Strip undefined/null values from params
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
      throw new TransportError(
        `${method} failed: ${errorMessage}`,
        response.status,
        errorBody,
      );
    }

    if (!text) {
      return undefined as T;
    }

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
