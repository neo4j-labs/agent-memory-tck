package memory

import "context"

// LongTermMemory provides entity, preference, and fact operations.
type LongTermMemory struct {
	transport *transport
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
	}
	if p.description != "" {
		params["description"] = p.description
	}
	var result BaseEntity
	if err := l.transport.call(ctx, "add_entity", params, &result); err != nil {
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
	if err := l.transport.call(ctx, "add_preference", params, &result); err != nil {
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
	if err := l.transport.call(ctx, "add_fact", map[string]interface{}{
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
	if err := l.transport.call(ctx, "search_entities", map[string]interface{}{
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
	if err := l.transport.call(ctx, "search_preferences", params, &result); err != nil {
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
	if err := l.transport.call(ctx, "get_entity_by_name", map[string]interface{}{
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
	if err := l.transport.call(ctx, "get_related_entities", params, &result); err != nil {
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
	if err := l.transport.call(ctx, "add_relationship", map[string]interface{}{
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
	if err := l.transport.call(ctx, "merge_duplicate_entities", map[string]interface{}{
		"source_id": sourceID,
		"target_id": targetID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
