/**
 * Long-term memory operations.
 *
 * Bridge methods (Silver tier) plus Volume 5 / hosted-native methods for
 * entity feedback, history, merge-by-id, graph view, and provenance.
 */

import type { Transport } from "../transport/index.js";
import type {
  AddRelationshipOptions,
  Entity,
  EntityFeedbackResult,
  EntityGraph,
  EntityGraphEdge,
  EntityGraphNode,
  EntityHistory,
  EntityMention,
  EntityMergeResult,
  EntityRelationshipRef,
  Fact,
  GetRelatedEntitiesOptions,
  ListEntitiesOptions,
  Preference,
  Relationship,
  SearchEntitiesOptions,
  SearchPreferencesOptions,
  SetEntityFeedbackOptions,
  UpdateEntityOptions,
} from "../types.js";

interface WireEntity {
  id: string;
  name: string;
  type: string;
  subtype?: string;
  description?: string;
  embedding?: number[];
  canonical_name?: string;
  created_at?: string;
  updated_at?: string;
  confidence?: number;
  source_stage?: string;
  relationships?: WireEntityRelRef[];
}

interface WireEntityRelRef {
  id: string;
  type: string;
  target_id: string;
  target_name?: string;
  properties?: Record<string, unknown>;
}

interface WirePreference {
  id: string;
  category: string;
  preference: string;
  context?: string;
  embedding?: number[];
}

interface WireFact {
  id: string;
  subject: string;
  predicate: string;
  object: string;
  embedding?: number[];
}

interface WireRelationship {
  id: string;
  source_id: string;
  target_id: string;
  relationship_type: string;
  properties?: Record<string, unknown>;
}

interface WireEntityHistory {
  entity_id: string;
  mentions: WireMention[];
}

interface WireMention {
  conversation_id: string;
  message_id?: string;
  content: string;
  timestamp: string;
}

interface WireGraphNode {
  id: string;
  name: string;
  type: string;
}

interface WireGraphEdge {
  id: string;
  source: string;
  target: string;
  type: string;
}

interface WireGraph {
  nodes?: WireGraphNode[];
  edges?: WireGraphEdge[];
}

function toEntity(w: WireEntity): Entity {
  return {
    id: w.id,
    name: w.name,
    type: w.type,
    subtype: w.subtype,
    description: w.description,
    embedding: w.embedding,
    canonicalName: w.canonical_name,
    createdAt: w.created_at ?? "",
    updatedAt: w.updated_at,
    confidence: w.confidence,
    sourceStage: w.source_stage,
    relationships: w.relationships?.map(toRelRef),
  };
}

function toRelRef(w: WireEntityRelRef): EntityRelationshipRef {
  return {
    id: w.id,
    type: w.type,
    targetId: w.target_id,
    targetName: w.target_name,
    properties: w.properties,
  };
}

function toPreference(w: WirePreference): Preference {
  return {
    id: w.id,
    category: w.category,
    preference: w.preference,
    context: w.context,
    embedding: w.embedding,
  };
}

function toFact(w: WireFact): Fact {
  return {
    id: w.id,
    subject: w.subject,
    predicate: w.predicate,
    object: w.object,
    embedding: w.embedding,
  };
}

function toRelationship(w: WireRelationship): Relationship {
  return {
    id: w.id,
    sourceId: w.source_id,
    targetId: w.target_id,
    relationshipType: w.relationship_type,
    properties: w.properties ?? {},
  };
}

function toMention(w: WireMention): EntityMention {
  return {
    conversationId: w.conversation_id,
    messageId: w.message_id,
    content: w.content,
    timestamp: w.timestamp,
  };
}

function toGraphNode(w: WireGraphNode): EntityGraphNode {
  return { id: w.id, name: w.name, type: w.type };
}

function toGraphEdge(w: WireGraphEdge): EntityGraphEdge {
  return { id: w.id, source: w.source, target: w.target, type: w.type };
}

export class LongTermMemory {
  constructor(private readonly transport: Transport) {}

  // ---- Silver tier (bridge) ----------------------------------------------

  async addEntity(
    name: string,
    entityType: string,
    options?: { description?: string },
  ): Promise<Entity> {
    const wire = await this.transport.request<WireEntity>("add_entity", {
      name,
      entity_type: entityType,
      type: entityType,
      description: options?.description,
    });
    return toEntity(wire);
  }

  async addPreference(
    category: string,
    preference: string,
    options?: { context?: string },
  ): Promise<Preference> {
    const wire = await this.transport.request<WirePreference>("add_preference", {
      category,
      preference,
      context: options?.context,
    });
    return toPreference(wire);
  }

  async addFact(subject: string, predicate: string, obj: string): Promise<Fact> {
    const wire = await this.transport.request<WireFact>("add_fact", {
      subject,
      predicate,
      obj,
    });
    return toFact(wire);
  }

  async searchEntities(query: string, options?: SearchEntitiesOptions): Promise<Entity[]> {
    const wire = await this.transport.request<WireEntity[]>("search_entities", {
      query,
      type: options?.type,
      limit: options?.limit ?? 10,
    });
    return wire.map(toEntity);
  }

  async searchPreferences(
    query: string,
    options?: SearchPreferencesOptions,
  ): Promise<Preference[]> {
    const wire = await this.transport.request<WirePreference[]>("search_preferences", {
      query,
      category: options?.category,
      limit: options?.limit ?? 10,
    });
    return wire.map(toPreference);
  }

  async getEntityByName(name: string): Promise<Entity | null> {
    const wire = await this.transport.request<WireEntity | null>("get_entity_by_name", {
      name,
    });
    return wire ? toEntity(wire) : null;
  }

  async getRelatedEntities(
    entityId: string,
    options?: GetRelatedEntitiesOptions,
  ): Promise<Entity[]> {
    const wire = await this.transport.request<WireEntity[]>("get_related_entities", {
      entity_id: entityId,
      relationship_type: options?.relationshipType,
      depth: options?.depth ?? 1,
    });
    return wire.map(toEntity);
  }

  async addRelationship(
    sourceId: string,
    targetId: string,
    relationshipType: string,
    options?: AddRelationshipOptions,
  ): Promise<Relationship> {
    const wire = await this.transport.request<WireRelationship>("add_relationship", {
      source_id: sourceId,
      target_id: targetId,
      relationship_type: relationshipType,
      properties: options?.properties,
    });
    return toRelationship(wire);
  }

  async mergeDuplicateEntities(
    sourceId: string,
    targetId: string,
    options?: { canonicalName?: string },
  ): Promise<Entity> {
    const wire = await this.transport.request<WireEntity>("merge_duplicate_entities", {
      source_id: sourceId,
      target_id: targetId,
      canonical_name: options?.canonicalName,
    });
    return toEntity(wire);
  }

  // ---- Volume 5 / hosted-native methods -----------------------------------

  /** List all entities, optionally filtered by entity type. */
  async listEntities(options?: ListEntitiesOptions): Promise<Entity[]> {
    const wire = await this.transport.request<WireEntity[]>("list_entities", {
      type: options?.type,
      limit: options?.limit,
    });
    return wire.map(toEntity);
  }

  /** Fetch one entity (with relationships) by id. */
  async getEntity(entityId: string): Promise<Entity> {
    const wire = await this.transport.request<WireEntity>("get_entity", {
      entity_id: entityId,
    });
    return toEntity(wire);
  }

  /** Update an existing entity's name and/or description.
   *
   * The hosted PUT /v1/entities/{id} returns `{status: "updated"}` rather
   * than the full entity, so when the response lacks an `id` we follow up
   * with a GET to keep the SDK contract — "update returns the updated
   * Entity". Bridge transports return the entity directly, so we tolerate
   * both shapes.
   */
  async updateEntity(entityId: string, options: UpdateEntityOptions): Promise<Entity> {
    const wire = await this.transport.request<WireEntity | { status: string }>(
      "update_entity",
      {
        entity_id: entityId,
        name: options.name,
        description: options.description,
      },
    );
    if (wire && typeof wire === "object" && "id" in wire && (wire as WireEntity).id) {
      return toEntity(wire as WireEntity);
    }
    return this.getEntity(entityId);
  }

  /** Delete an entity and its relationships. */
  async deleteEntity(entityId: string): Promise<void> {
    await this.transport.request("delete_entity", { entity_id: entityId });
  }

  /** Score an entity 0-1 and optionally mark it human-confirmed. */
  async setEntityFeedback(
    entityId: string,
    options: SetEntityFeedbackOptions,
  ): Promise<EntityFeedbackResult> {
    const result = await this.transport.request<{ id: string; updated: boolean }>(
      "set_entity_feedback",
      {
        entity_id: entityId,
        user_score: options.userScore,
        confirmed: options.confirmed,
      },
    );
    return { id: result.id, updated: result.updated };
  }

  /** All cross-conversation mentions of this entity. */
  async getEntityHistory(entityId: string): Promise<EntityHistory> {
    const wire = await this.transport.request<WireEntityHistory>("get_entity_history", {
      entity_id: entityId,
    });
    return {
      entityId: wire.entity_id,
      mentions: (wire.mentions ?? []).map(toMention),
    };
  }

  /** Merge `sourceId` into `targetId`, leaving a SAME_AS provenance link. */
  async mergeEntities(sourceId: string, targetId: string): Promise<EntityMergeResult> {
    const wire = await this.transport.request<{
      source_id: string;
      target_id: string;
      status: string;
    }>("merge_entities", {
      source_id: sourceId,
      target_id: targetId,
    });
    return { sourceId: wire.source_id, targetId: wire.target_id, status: wire.status };
  }

  /** Full-graph view of all entities + edges. Pair with NVL for visualization. */
  async getEntityGraph(): Promise<EntityGraph> {
    const wire = await this.transport.request<WireGraph>("get_entity_graph", {});
    return {
      nodes: (wire.nodes ?? []).map(toGraphNode),
      edges: (wire.edges ?? []).map(toGraphEdge),
    };
  }
}
