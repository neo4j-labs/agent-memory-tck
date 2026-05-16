/**
 * Observability primitives — User-Agent header building and the typed
 * logger event stream emitted by both transports.
 */

import { VERSION } from "./version.js";

/**
 * Build the default User-Agent string. Mirrors the convention used by other
 * Anthropic / OpenAI SDKs: `<package>/<version> (<runtime>; <platform>)`.
 *
 * Detection is best-effort and silently degrades on edge runtimes where
 * `process` is unavailable.
 */
export function defaultUserAgent(): string {
  const runtime = detectRuntime();
  return runtime
    ? `@neo4j-labs/agent-memory/${VERSION} (${runtime})`
    : `@neo4j-labs/agent-memory/${VERSION}`;
}

export function supportsUserAgentHeader(): boolean {
  return detectRuntime() !== null;
}

function detectRuntime(): string | null {
  // Deno
  const denoObj = (globalThis as { Deno?: { version?: { deno?: string } } }).Deno;
  if (denoObj?.version?.deno) {
    return `deno/${denoObj.version.deno}`;
  }
  // Bun
  const bunObj = (globalThis as { Bun?: { version?: string } }).Bun;
  if (bunObj?.version) {
    return `bun/${bunObj.version}`;
  }
  // Node
  if (typeof process !== "undefined" && process.versions?.node) {
    const platform = process.platform ?? "unknown";
    return `node/${process.versions.node}; ${platform}`;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Logger
// ---------------------------------------------------------------------------

/** Single event emitted to the user-supplied logger. */
export type LogEvent =
  | {
      kind: "request";
      method: string;
      url: string;
      httpMethod?: string;
    }
  | {
      kind: "response";
      method: string;
      url: string;
      status: number;
      requestId?: string;
      durationMs: number;
    }
  | {
      kind: "error";
      method: string;
      url: string;
      status?: number;
      requestId?: string;
      durationMs: number;
      message: string;
    };

export type Logger = (event: LogEvent) => void;

/**
 * Extract a request-id from a Response. The hosted service emits one of
 * `x-request-id`, `request-id`, or `x-amzn-RequestId` depending on the edge
 * the request lands on. Caller-side correlation works as long as one is
 * present.
 */
export function extractRequestId(headers: Headers): string | undefined {
  return (
    headers.get("x-request-id") ??
    headers.get("request-id") ??
    headers.get("x-amzn-requestid") ??
    undefined
  );
}
