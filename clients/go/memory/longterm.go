package memory

import "context"

// LongTermMemory provides entity, preference, fact, and graph operations.
type LongTermMemory struct {
	transport Transport
}

// AddEntity creates an entity in the knowledge graph.
func (l *LongTermMemory) AddEntity(ctx context.Context, name, entityType string, opts ...func(*AddEntityParams)) (*BaseEntity, error) {
	p := AddEntityParams{}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"name":        name,
		"entity_type": entityType,
		"type":        entityType,
	}
	if p.description != "" {
		params["description"] = p.description
	}
	var result BaseEntity
	if err := l.transport.Call(ctx, "add_entity", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type AddEntityParams struct{ description string }

// WithDescription sets the entity description.
func WithDescription(d string) func(*AddEntityParams) {
	return func(p *AddEntityParams) { p.description = d }
}

// AddPreference stores a user preference.
func (l *LongTermMemory) AddPreference(ctx context.Context, category, preference string, opts ...func(*AddPreferenceParams)) (*Preference, error) {
	p := AddPreferenceParams{}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"category":   category,
		"preference": preference,
	}
	if p.prefContext != "" {
		params["context"] = p.prefContext
	}
	var result Preference
	if err := l.transport.Call(ctx, "add_preference", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type AddPreferenceParams struct{ prefContext string }

// WithContext sets the preference context.
func WithContext(c string) func(*AddPreferenceParams) {
	return func(p *AddPreferenceParams) { p.prefContext = c }
}

// AddFact stores a subject-predicate-object fact triple.
func (l *LongTermMemory) AddFact(ctx context.Context, subject, predicate, obj string) (*Fact, error) {
	var result Fact
	if err := l.transport.Call(ctx, "add_fact", map[string]interface{}{
		"subject":   subject,
		"predicate": predicate,
		"obj":       obj,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// SearchEntities searches entities by semantic similarity.
func (l *LongTermMemory) SearchEntities(ctx context.Context, query string, limit int) ([]BaseEntity, error) {
	var result []BaseEntity
	if err := l.transport.Call(ctx, "search_entities", map[string]interface{}{
		"query": query,
		"limit": limit,
	}, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []BaseEntity{}, nil
	}
	return result, nil
}

// SearchPreferences searches preferences by semantic similarity.
func (l *LongTermMemory) SearchPreferences(ctx context.Context, query string, opts ...func(*SearchPrefsParams)) ([]Preference, error) {
	p := SearchPrefsParams{limit: 10}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"query": query,
		"limit": p.limit,
	}
	if p.category != "" {
		params["category"] = p.category
	}
	var result []Preference
	if err := l.transport.Call(ctx, "search_preferences", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []Preference{}, nil
	}
	return result, nil
}

type SearchPrefsParams struct {
	category string
	limit    int
}

// WithCategory filters preferences by category.
func WithCategory(c string) func(*SearchPrefsParams) {
	return func(p *SearchPrefsParams) { p.category = c }
}

// WithPrefsLimit sets the preference search limit.
func WithPrefsLimit(n int) func(*SearchPrefsParams) {
	return func(p *SearchPrefsParams) { p.limit = n }
}

// GetEntityByName looks up an entity by exact name.
func (l *LongTermMemory) GetEntityByName(ctx context.Context, name string) (*BaseEntity, error) {
	var result *BaseEntity
	if err := l.transport.Call(ctx, "get_entity_by_name", map[string]interface{}{
		"name": name,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// GetRelatedEntities retrieves entities related to the given entity.
func (l *LongTermMemory) GetRelatedEntities(ctx context.Context, entityID string, opts ...func(*RelatedParams)) ([]BaseEntity, error) {
	p := RelatedParams{depth: 1}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"entity_id": entityID,
		"depth":     p.depth,
	}
	if p.relationshipType != "" {
		params["relationship_type"] = p.relationshipType
	}
	var result []BaseEntity
	if err := l.transport.Call(ctx, "get_related_entities", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []BaseEntity{}, nil
	}
	return result, nil
}

type RelatedParams struct {
	relationshipType string
	depth            int
}

// WithRelType filters related entities by relationship type.
func WithRelType(t string) func(*RelatedParams) {
	return func(p *RelatedParams) { p.relationshipType = t }
}

// WithDepth sets the traversal depth.
func WithDepth(d int) func(*RelatedParams) {
	return func(p *RelatedParams) { p.depth = d }
}

// AddRelationship creates a typed relationship between two entities.
func (l *LongTermMemory) AddRelationship(ctx context.Context, sourceID, targetID, relType string) (*Relationship, error) {
	var result Relationship
	if err := l.transport.Call(ctx, "add_relationship", map[string]interface{}{
		"source_id":         sourceID,
		"target_id":         targetID,
		"relationship_type": relType,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// MergeDuplicateEntities merges two duplicate entities.
func (l *LongTermMemory) MergeDuplicateEntities(ctx context.Context, sourceID, targetID string) (*BaseEntity, error) {
	var result BaseEntity
	if err := l.transport.Call(ctx, "merge_duplicate_entities", map[string]interface{}{
		"source_id": sourceID,
		"target_id": targetID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ============================================================================
// Volume 5 / hosted-native methods
// ============================================================================

// ListEntities lists entities, optionally filtered by type.
func (l *LongTermMemory) ListEntities(ctx context.Context, entityType string, limit int) ([]BaseEntity, error) {
	params := map[string]interface{}{}
	if entityType != "" {
		params["type"] = entityType
	}
	if limit > 0 {
		params["limit"] = limit
	}
	var result []BaseEntity
	if err := l.transport.Call(ctx, "list_entities", params, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// GetEntity fetches one entity (with relationships) by id.
func (l *LongTermMemory) GetEntity(ctx context.Context, entityID string) (*BaseEntity, error) {
	var result BaseEntity
	if err := l.transport.Call(ctx, "get_entity", map[string]interface{}{
		"entity_id": entityID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// UpdateEntityParams configures UpdateEntity.
type UpdateEntityParams struct {
	Name        string
	Description string
}

// UpdateEntity updates an existing entity's name and/or description.
//
// The hosted PUT /v1/entities/{id} returns {"status": "updated"} rather
// than the full entity, so when the response lacks an "id" we fall back to
// a follow-up GET to keep the public contract — "update returns the
// updated entity". Bridge transports return the entity directly, so the
// raw-decode path also works.
func (l *LongTermMemory) UpdateEntity(ctx context.Context, entityID string, p UpdateEntityParams) (*BaseEntity, error) {
	params := map[string]interface{}{
		"entity_id": entityID,
	}
	if p.Name != "" {
		params["name"] = p.Name
	}
	if p.Description != "" {
		params["description"] = p.Description
	}
	var result BaseEntity
	if err := l.transport.Call(ctx, "update_entity", params, &result); err != nil {
		return nil, err
	}
	if result.ID == "" {
		return l.GetEntity(ctx, entityID)
	}
	return &result, nil
}

// DeleteEntity deletes an entity by id.
func (l *LongTermMemory) DeleteEntity(ctx context.Context, entityID string) error {
	return l.transport.Call(ctx, "delete_entity", map[string]interface{}{
		"entity_id": entityID,
	}, nil)
}

// SetEntityFeedback scores an entity 0-1 and optionally marks it confirmed.
func (l *LongTermMemory) SetEntityFeedback(ctx context.Context, entityID string, userScore float64, confirmed bool) (*EntityFeedbackResult, error) {
	var result EntityFeedbackResult
	if err := l.transport.Call(ctx, "set_entity_feedback", map[string]interface{}{
		"entity_id":  entityID,
		"user_score": userScore,
		"confirmed":  confirmed,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GetEntityHistory returns all cross-conversation mentions of this entity.
func (l *LongTermMemory) GetEntityHistory(ctx context.Context, entityID string) (*EntityHistory, error) {
	var result EntityHistory
	if err := l.transport.Call(ctx, "get_entity_history", map[string]interface{}{
		"entity_id": entityID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// MergeEntities merges sourceID into targetID via the hosted REST endpoint.
func (l *LongTermMemory) MergeEntities(ctx context.Context, sourceID, targetID string) (*EntityMergeResult, error) {
	var result EntityMergeResult
	if err := l.transport.Call(ctx, "merge_entities", map[string]interface{}{
		"source_id": sourceID,
		"target_id": targetID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GetEntityGraph returns a full-graph view (nodes + edges).
func (l *LongTermMemory) GetEntityGraph(ctx context.Context) (*EntityGraph, error) {
	var result EntityGraph
	if err := l.transport.Call(ctx, "get_entity_graph", nil, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
