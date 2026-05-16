/**
 * Testing-only exports.
 *
 * `BridgeTransport` speaks the TCK bridge protocol (POST /{snake_method},
 * snake_case JSON). Use it for cross-language conformance testing against
 * the neo4j-agent-memory TCK or a local reference server. Production
 * applications should use the default `MemoryClient` (which speaks REST to
 * the hosted Neo4j Agent Memory Service).
 */

export { BridgeTransport } from "./transport/bridge.js";
export type { BridgeTransportOptions } from "./transport/bridge.js";
