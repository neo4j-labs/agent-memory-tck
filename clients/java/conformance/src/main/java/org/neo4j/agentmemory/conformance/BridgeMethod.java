package org.neo4j.agentmemory.conformance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@FunctionalInterface
interface BridgeMethod {
    JsonNode invoke(ObjectNode body) throws Exception;
}
