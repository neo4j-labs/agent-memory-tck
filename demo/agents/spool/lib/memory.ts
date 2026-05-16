/**
 * MemoryClient singleton + mode detection.
 *
 * `live` mode: MEMORY_API_KEY is set — talk to the real hosted service.
 * `stub`  mode: MEMORY_API_KEY is unset — no NAMS calls; the UI shows
 *              canned data so the demo is browseable without keys.
 */

import { MemoryClient } from "@neo4j-labs/agent-memory";

const ENDPOINT = process.env.MEMORY_ENDPOINT ?? "https://memory.neo4jlabs.com/v1";
const API_KEY = (process.env.MEMORY_API_KEY ?? "").trim();

export const isLive = API_KEY.length > 0;

let _client: MemoryClient | null = null;

export function getMemoryClient(): MemoryClient {
  if (!isLive) {
    throw new Error("getMemoryClient called in stub mode — guard with isLive first");
  }
  if (!_client) {
    _client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
  }
  return _client;
}

/** Whether the API route should use the real Strands agent (vs. the stub). */
export const hasModelKey =
  (process.env.OPENAI_API_KEY ?? "").trim().length > 0 ||
  (process.env.AWS_REGION ?? "").trim().length > 0;
