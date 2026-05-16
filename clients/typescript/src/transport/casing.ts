/**
 * snake_case ↔ camelCase translation for wire payloads.
 *
 * Keep deeply nested structures (arrays, objects, primitives) intact.
 * Pure helpers — no transport dependencies.
 */

const SNAKE_RE = /_([a-z0-9])/g;
const CAMEL_RE = /([A-Z])/g;

function snakeKey(key: string): string {
  return key.replace(CAMEL_RE, (_, c) => `_${(c as string).toLowerCase()}`);
}

function camelKey(key: string): string {
  return key.replace(SNAKE_RE, (_, c) => (c as string).toUpperCase());
}

export function snakeToCamel<T = unknown>(value: unknown): T {
  if (Array.isArray(value)) {
    return value.map((v) => snakeToCamel(v)) as T;
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      out[camelKey(k)] = snakeToCamel(v);
    }
    return out as T;
  }
  return value as T;
}

export function camelToSnake<T = unknown>(value: unknown): T {
  if (Array.isArray(value)) {
    return value.map((v) => camelToSnake(v)) as T;
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      out[snakeKey(k)] = camelToSnake(v);
    }
    return out as T;
  }
  return value as T;
}
