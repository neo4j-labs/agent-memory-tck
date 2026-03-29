/**
 * Long-term memory operations.
 *
 * Provides entity, preference, and fact management with search capabilities.
 * All methods correspond to Silver-tier TCK requirements.
 */

import type { Transport } from "../transport/index.js";
import type {
  AddRelationshipOptions,
  Entity,
  Fact,
  GetRelatedEntitiesOptions,
  Preference,
  Relationship,
  SearchEntitiesOptions,
  SearchPreferencesOptions,
} from "../types.js";

/** Wire format (snake_case). */
interface WireEntity {
  id: string;
  name: string;
  type: string;
  subtype?: string;
  description?: string;
  embedding?: number[];
  canonical_name?: string;
  created_at: string;
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

function toEntity(w: WireEntity): Entity {
  return {
    id: w.id,
    name: w.name,
    type: w.type,
    subtype: w.subtype,
    description: w.description,
    embedding: w.embedding,
    canonicalName: w.canonical_name,
    createdAt: w.created_at,
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

export class LongTermMemory {
  constructor(private readonly transport: Transport) {}

  async addEntity(
    name: string,
    entityType: string,
    options?: { description?: string },
  ): Promise<Entity> {
    const wire = await this.transport.request<WireEntity>("add_entity", {
      name,
      entity_type: entityType,
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

  async addFact(
    subject: string,
    predicate: string,
    obj: string,
  ): Promise<Fact> {
    const wire = await this.transport.request<WireFact>("add_fact", {
      subject,
      predicate,
      obj,
    });
    return toFact(wire);
  }

  async searchEntities(
    query: string,
    options?: SearchEntitiesOptions,
  ): Promise<Entity[]> {
    const wire = await this.transport.request<WireEntity[]>("search_entities", {
      query,
      limit: options?.limit ?? 10,
    });
    return wire.map(toEntity);
  }

  async searchPreferences(
    query: string,
    options?: SearchPreferencesOptions,
  ): Promise<Preference[]> {
    const wire = await this.transport.request<WirePreference[]>(
      "search_preferences",
      {
        query,
        category: options?.category,
        limit: options?.limit ?? 10,
      },
    );
    return wire.map(toPreference);
  }

  async getEntityByName(name: string): Promise<Entity | null> {
    const wire = await this.transport.request<WireEntity | null>(
      "get_entity_by_name",
      { name },
    );
    return wire ? toEntity(wire) : null;
  }

  async getRelatedEntities(
    entityId: string,
    options?: GetRelatedEntitiesOptions,
  ): Promise<Entity[]> {
    const wire = await this.transport.request<WireEntity[]>(
      "get_related_entities",
      {
        entity_id: entityId,
        relationship_type: options?.relationshipType,
        depth: options?.depth ?? 1,
      },
    );
    return wire.map(toEntity);
  }

  async addRelationship(
    sourceId: string,
    targetId: string,
    relationshipType: string,
    options?: AddRelationshipOptions,
  ): Promise<Relationship> {
    const wire = await this.transport.request<WireRelationship>(
      "add_relationship",
      {
        source_id: sourceId,
        target_id: targetId,
        relationship_type: relationshipType,
        properties: options?.properties,
      },
    );
    return toRelationship(wire);
  }

  async mergeDuplicateEntities(
    sourceId: string,
    targetId: string,
    options?: { canonicalName?: string },
  ): Promise<Entity> {
    const wire = await this.transport.request<WireEntity>(
      "merge_duplicate_entities",
      {
        source_id: sourceId,
        target_id: targetId,
        canonical_name: options?.canonicalName,
      },
    );
    return toEntity(wire);
  }
}
