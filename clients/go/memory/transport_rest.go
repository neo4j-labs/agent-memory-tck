package memory

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// restTransport speaks the hosted Neo4j Agent Memory REST API at
// https://memory.neo4jlabs.com/v1.
//
// It maps the bridge-style Call(method, params) calls to REST endpoints with
// snake_case ↔ camelCase translation on the wire. Methods that have no REST
// equivalent return *NotSupportedError.
type restTransport struct {
	// baseURL is the validated endpoint, parsed once at construction. Every
	// request URL is built by cloning this and only setting Path / RawQuery
	// on the clone — Scheme and Host are never derived from caller-supplied
	// data, only from the configured (and validated) endpoint. nil iff
	// invalidErr is set.
	baseURL       *url.URL
	apiKey        string
	tokenProvider TokenProvider
	headers       map[string]string
	httpClient    *http.Client
	// invalidErr holds the validation error (if any) from
	// validateRestEndpoint so we can surface it on the first Call rather
	// than panicking at construction time.
	invalidErr error
}

// endpointString returns the configured endpoint for use in error messages.
// Safe when baseURL is nil (construction-time validation failed).
func (t *restTransport) endpointString() string {
	if t.baseURL == nil {
		return ""
	}
	return t.baseURL.String()
}

// validateRestEndpoint enforces that the configured endpoint is an absolute
// http(s) URL with no embedded user-info, query, or fragment. Returns the
// parsed *url.URL so callers can clone it for each request without ever
// re-parsing untrusted bytes. Path params supplied by the caller are escaped
// via url.PathEscape later; this gate ensures the request's Scheme + Host
// are always anchored to a known base.
func validateRestEndpoint(endpoint string) (*url.URL, error) {
	if endpoint == "" {
		return nil, fmt.Errorf("rest endpoint is empty")
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("rest endpoint is not a valid URL: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil, fmt.Errorf("rest endpoint must use http(s): got %q", u.Scheme)
	}
	if u.Host == "" {
		return nil, fmt.Errorf("rest endpoint must include a host")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, fmt.Errorf("rest endpoint must not include user-info, query, or fragment")
	}
	u.Path = strings.TrimRight(u.Path, "/")
	return u, nil
}

func newRestTransport(cfg Config, timeout time.Duration) Transport {
	// Validate at construction time. Errors here surface through the first
	// Call invocation as ConnectionError, matching the existing pattern.
	baseURL, err := validateRestEndpoint(cfg.Endpoint)
	if err != nil {
		// Defer the error until first Call so New() stays infallible.
		return &restTransport{
			invalidErr: err,
			httpClient: &http.Client{Timeout: timeout},
		}
	}
	return &restTransport{
		baseURL:       baseURL,
		apiKey:        cfg.APIKey,
		tokenProvider: cfg.TokenProvider,
		headers:       cfg.Headers,
		httpClient:    &http.Client{Timeout: timeout},
	}
}

func (t *restTransport) Connect(ctx context.Context) error {
	if t.invalidErr != nil {
		return &ConnectionError{MemoryError{Message: t.invalidErr.Error(), Cause: t.invalidErr}}
	}
	connectURL := *t.baseURL
	connectURL.Path = connectURL.Path + "/conversations"
	connectURL.RawQuery = "limit=1"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, connectURL.String(), nil)
	if err != nil {
		return &ConnectionError{MemoryError{Message: "failed to create request", Cause: err}}
	}
	if err := t.applyHeaders(ctx, req, false); err != nil {
		return err
	}
	resp, err := t.httpClient.Do(req)
	if err != nil {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("connect to %s failed", t.endpointString()), Cause: err}}
	}
	defer resp.Body.Close()
	if resp.StatusCode == 401 || resp.StatusCode == 403 {
		return &AuthenticationError{MemoryError{Message: fmt.Sprintf("auth failed: %d", resp.StatusCode)}}
	}
	if resp.StatusCode >= 500 {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("server error: %d", resp.StatusCode)}}
	}
	return nil
}

func (t *restTransport) Close(_ context.Context) error { return nil }

type restRoute struct {
	method      string
	path        string
	pathParams  []string
	queryParams []string
	hasBody     bool
	shape       func(raw interface{}, camelParams map[string]interface{}) interface{}
}

var (
	noopRoute        = &restRoute{}
	unsupportedRoute = &restRoute{method: "UNSUPPORTED"}
	// compositeRoute marks methods with no single REST endpoint — they are
	// assembled from multiple hosted calls in callComposite.
	compositeRoute = &restRoute{method: "COMPOSITE"}
)

var restRoutes = map[string]*restRoute{
	"setup":          noopRoute,
	"teardown":       noopRoute,
	"clear_all_data": noopRoute,

	"add_message": {method: http.MethodPost, path: "/conversations/{sessionId}/messages", pathParams: []string{"sessionId"}, hasBody: true},
	"get_conversation": {method: http.MethodGet, path: "/conversations/{sessionId}/messages", pathParams: []string{"sessionId"}, queryParams: []string{"limit"},
		shape: func(raw interface{}, p map[string]interface{}) interface{} {
			messages := raw
			if m, ok := raw.(map[string]interface{}); ok {
				if msgs, ok := m["messages"]; ok {
					messages = msgs
				}
			}
			return map[string]interface{}{
				"id":         p["sessionId"],
				"session_id": p["sessionId"],
				"messages":   messages,
			}
		}},
	"list_sessions": {method: http.MethodGet, path: "/conversations", queryParams: []string{"limit"},
		shape: func(raw interface{}, _ map[string]interface{}) interface{} {
			m, ok := raw.(map[string]interface{})
			if !ok {
				return []interface{}{}
			}
			conv, _ := m["conversations"].([]interface{})
			out := make([]interface{}, 0, len(conv))
			for _, c := range conv {
				cm, _ := c.(map[string]interface{})
				if cm == nil {
					continue
				}
				out = append(out, map[string]interface{}{
					"session_id":    cm["id"],
					"message_count": cm["messageCount"],
					"created_at":    cm["createdAt"],
					"updated_at":    cm["updatedAt"],
				})
			}
			return out
		}},
	"search_messages": {method: http.MethodPost, path: "/conversations/{sessionId}/search", pathParams: []string{"sessionId"}, hasBody: true,
		shape: func(raw interface{}, _ map[string]interface{}) interface{} {
			if m, ok := raw.(map[string]interface{}); ok {
				if msgs, ok := m["messages"]; ok {
					return msgs
				}
			}
			return raw
		}},
	"clear_session":            {method: http.MethodDelete, path: "/conversations/{sessionId}", pathParams: []string{"sessionId"}},
	"delete_message":           unsupportedRoute,
	"add_entity":               {method: http.MethodPost, path: "/entities", hasBody: true},
	"search_entities":          {method: http.MethodPost, path: "/entities/search", hasBody: true, shape: extractEntities},
	"add_preference":           unsupportedRoute,
	"add_fact":                 unsupportedRoute,
	"search_preferences":       unsupportedRoute,
	"get_entity_by_name":       {method: http.MethodGet, path: "/entities/by-name", queryParams: []string{"name", "type", "limit"}, shape: extractFirstEntity},
	"get_related_entities":     compositeRoute,
	"add_relationship":         {method: http.MethodPost, path: "/relationships", hasBody: true},
	"merge_duplicate_entities": compositeRoute,
	"start_trace":              unsupportedRoute,
	"add_step":                 unsupportedRoute,
	"record_tool_call":         {method: http.MethodPost, path: "/reasoning/tool-calls", hasBody: true},
	"complete_trace":           unsupportedRoute,
	"get_trace_with_steps":     unsupportedRoute,
	"list_traces":              unsupportedRoute,
	"get_tool_stats":           unsupportedRoute,
	"get_similar_traces":       unsupportedRoute,

	// ---- Volume 5 / hosted-native routes -------------------------------
	"create_conversation":       {method: http.MethodPost, path: "/conversations", hasBody: true},
	"list_conversations":        {method: http.MethodGet, path: "/conversations", queryParams: []string{"limit"}, shape: extractConversations},
	"get_conversation_metadata": {method: http.MethodGet, path: "/conversations/{conversationId}", pathParams: []string{"conversationId"}},
	"delete_conversation":       {method: http.MethodDelete, path: "/conversations/{conversationId}", pathParams: []string{"conversationId"}},
	"get_context":               {method: http.MethodGet, path: "/conversations/{conversationId}/context", pathParams: []string{"conversationId"}},
	"bulk_add_messages":         {method: http.MethodPost, path: "/conversations/{conversationId}/messages/bulk", pathParams: []string{"conversationId"}, hasBody: true, shape: extractMessages},
	"get_observations":          {method: http.MethodGet, path: "/conversations/{conversationId}/observations", pathParams: []string{"conversationId"}, queryParams: []string{"limit"}, shape: extractObservations},
	"get_reflections":           {method: http.MethodGet, path: "/conversations/{conversationId}/reflections", pathParams: []string{"conversationId"}, shape: extractReflections},
	"list_entities":             {method: http.MethodGet, path: "/entities", queryParams: []string{"type", "limit"}, shape: extractEntities},
	"get_entity":                {method: http.MethodGet, path: "/entities/{entityId}", pathParams: []string{"entityId"}},
	"update_entity":             {method: http.MethodPut, path: "/entities/{entityId}", pathParams: []string{"entityId"}, hasBody: true},
	"delete_entity":             {method: http.MethodDelete, path: "/entities/{entityId}", pathParams: []string{"entityId"}},
	"set_entity_feedback":       {method: http.MethodPut, path: "/entities/{entityId}/feedback", pathParams: []string{"entityId"}, hasBody: true},
	"get_entity_history":        {method: http.MethodGet, path: "/entities/{entityId}/history", pathParams: []string{"entityId"}},
	"merge_entities":            {method: http.MethodPost, path: "/entities/{sourceId}/merge", pathParams: []string{"sourceId"}, hasBody: true},
	"get_entity_graph":          {method: http.MethodGet, path: "/entities/graph"},
	"explain_step":              {method: http.MethodGet, path: "/reasoning/explain/{stepId}", pathParams: []string{"stepId"}},
	"get_trace_by_conversation": {method: http.MethodGet, path: "/reasoning/trace/{conversationId}", pathParams: []string{"conversationId"}},
	"get_entity_provenance":     {method: http.MethodGet, path: "/reasoning/provenance/{entityId}", pathParams: []string{"entityId"}},
	"record_step":               {method: http.MethodPost, path: "/reasoning/steps", hasBody: true},
	"list_steps":                {method: http.MethodGet, path: "/reasoning/steps", queryParams: []string{"conversation_id"}, shape: extractSteps},
	"cypher_query":              {method: http.MethodPost, path: "/query", hasBody: true},
	"list_api_keys":             {method: http.MethodGet, path: "/auth/api-keys", queryParams: []string{"workspace_id"}, shape: extractAPIKeys},
	"create_api_key":            {method: http.MethodPost, path: "/auth/api-keys", hasBody: true},
	"revoke_api_key":            {method: http.MethodDelete, path: "/auth/api-keys/{keyId}", pathParams: []string{"keyId"}},
	"reveal_api_key":            {method: http.MethodGet, path: "/auth/api-keys/{keyId}/reveal", pathParams: []string{"keyId"}, queryParams: []string{"workspace_id"}},
	"refresh_access_token":      {method: http.MethodPost, path: "/auth/refresh", hasBody: true},
}

func extractEntities(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if e, ok := m["entities"]; ok {
			return e
		}
	}
	return raw
}

// extractFirstEntity unwraps GET /entities/by-name's ordered
// {"entities": [...]} envelope to first-or-nil: the first element is the best
// match; an empty list means "no match" and maps to a nil result, never an
// error.
func extractFirstEntity(raw interface{}, _ map[string]interface{}) interface{} {
	m, ok := raw.(map[string]interface{})
	if !ok {
		return raw
	}
	entities, ok := m["entities"].([]interface{})
	if !ok || len(entities) == 0 {
		return nil
	}
	return entities[0]
}

func extractConversations(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if c, ok := m["conversations"]; ok {
			return c
		}
	}
	return raw
}

func extractMessages(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if x, ok := m["messages"]; ok {
			return x
		}
	}
	return raw
}

func extractObservations(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if x, ok := m["observations"]; ok {
			return x
		}
	}
	return raw
}

func extractReflections(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if x, ok := m["reflections"]; ok {
			return x
		}
	}
	return raw
}

func extractSteps(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if x, ok := m["steps"]; ok {
			return x
		}
	}
	return raw
}

func extractAPIKeys(raw interface{}, _ map[string]interface{}) interface{} {
	if m, ok := raw.(map[string]interface{}); ok {
		if x, ok := m["keys"]; ok {
			return x
		}
		if x, ok := m["api_keys"]; ok {
			return x
		}
	}
	return raw
}

func (t *restTransport) Call(ctx context.Context, method string, params map[string]interface{}, result interface{}) error {
	if t.invalidErr != nil {
		return &ConnectionError{MemoryError{Message: t.invalidErr.Error(), Cause: t.invalidErr}}
	}

	route, ok := restRoutes[method]
	if !ok {
		return &NotSupportedError{MemoryError{Message: fmt.Sprintf("method %q is not supported by RestTransport", method)}}
	}
	if route == noopRoute {
		return nil
	}
	if route == unsupportedRoute {
		return &NotSupportedError{MemoryError{Message: fmt.Sprintf("method %q has no equivalent in the hosted REST API", method)}}
	}
	if route == compositeRoute {
		return t.callComposite(ctx, method, params, result)
	}

	camel, _ := snakeToCamel(params).(map[string]interface{})
	if camel == nil {
		camel = map[string]interface{}{}
	}

	consumed := map[string]bool{}

	path := route.path
	for _, name := range route.pathParams {
		v, ok := camel[name]
		if !ok || v == nil || v == "" {
			return &TransportError{
				MemoryError: MemoryError{Message: fmt.Sprintf("missing path param %q for method %q", name, method)},
				StatusCode:  400,
			}
		}
		path = strings.ReplaceAll(path, "{"+name+"}", url.PathEscape(fmt.Sprintf("%v", v)))
		consumed[name] = true
	}

	// The hosted REST API uses snake_case for query params
	// (conversation_id, workspace_id, etc.) — NOT camelCase. Look up the
	// value from the original snake-cased params dict and emit the
	// snake_case key on the wire. We mark both the snake form (consumed in
	// the original params) and the camel form (consumed in the camel dict
	// for body construction) so neither leaks into the body.
	q := url.Values{}
	for _, name := range route.queryParams {
		var v interface{}
		if got, ok := params[name]; ok && got != nil {
			v = got
		} else {
			camelName := camelKey(name)
			if got, ok := camel[camelName]; ok && got != nil {
				v = got
			}
		}
		if v != nil {
			q.Set(name, fmt.Sprintf("%v", v))
			consumed[name] = true
			consumed[camelKey(name)] = true
		}
	}

	var bodyReader io.Reader
	if route.hasBody {
		body := map[string]interface{}{}
		for k, v := range camel {
			if !consumed[k] && v != nil {
				body[k] = v
			}
		}
		buf, err := json.Marshal(body)
		if err != nil {
			return &MemoryError{Message: "failed to marshal body", Cause: err}
		}
		bodyReader = bytes.NewReader(buf)
	}

	// Build the request URL by cloning the validated base URL and only
	// setting Path / RawQuery on the clone. Scheme + Host therefore always
	// come from the endpoint vetted by validateRestEndpoint at construction
	// — they are never reassembled from caller-supplied bytes. The path
	// comes from a static route table with path params escaped via
	// url.PathEscape above.
	reqURL := *t.baseURL
	reqURL.Path = reqURL.Path + path
	if len(q) > 0 {
		reqURL.RawQuery = q.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, route.method, reqURL.String(), bodyReader)
	if err != nil {
		return &ConnectionError{MemoryError{Message: "failed to create request", Cause: err}}
	}
	if err := t.applyHeaders(ctx, req, route.hasBody); err != nil {
		return err
	}

	resp, err := t.httpClient.Do(req)
	if err != nil {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("request to %s failed", reqURL.String()), Cause: err}}
	}
	defer resp.Body.Close()

	if resp.StatusCode == 401 || resp.StatusCode == 403 {
		return &AuthenticationError{MemoryError{Message: fmt.Sprintf("auth failed: %d", resp.StatusCode)}}
	}
	if resp.StatusCode == 204 {
		return nil
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return &MemoryError{Message: "failed to read response", Cause: err}
	}

	if resp.StatusCode >= 400 {
		var errResp struct {
			Error string `json:"error"`
		}
		if json.Unmarshal(respBody, &errResp) == nil && errResp.Error != "" {
			return &TransportError{
				MemoryError: MemoryError{Message: fmt.Sprintf("%s failed: %s", method, errResp.Error)},
				StatusCode:  resp.StatusCode,
			}
		}
		return &TransportError{
			MemoryError: MemoryError{Message: fmt.Sprintf("%s failed: HTTP %d", method, resp.StatusCode)},
			StatusCode:  resp.StatusCode,
		}
	}

	if result == nil || len(respBody) == 0 {
		return nil
	}

	var raw interface{}
	if err := json.Unmarshal(respBody, &raw); err != nil {
		return &MemoryError{Message: "failed to decode response", Cause: err}
	}

	if route.shape != nil {
		raw = route.shape(raw, camel)
	}
	snake := camelToSnake(raw)

	// Re-marshal/unmarshal to convert into the typed struct the caller passed.
	buf, err := json.Marshal(snake)
	if err != nil {
		return &MemoryError{Message: "failed to re-marshal response", Cause: err}
	}
	if err := json.Unmarshal(buf, result); err != nil {
		return &MemoryError{Message: "failed to decode shaped response", Cause: err}
	}
	return nil
}

// callComposite dispatches methods that have no single REST endpoint and are
// instead assembled from multiple hosted calls.
func (t *restTransport) callComposite(ctx context.Context, method string, params map[string]interface{}, result interface{}) error {
	switch method {
	case "get_related_entities":
		return t.getRelatedEntitiesComposite(ctx, params, result)
	case "merge_duplicate_entities":
		return t.mergeDuplicateEntitiesComposite(ctx, params, result)
	}
	return &NotSupportedError{MemoryError{Message: fmt.Sprintf("method %q has no composite handler", method)}}
}

// getRelatedEntitiesComposite maps GET /entities/{id}'s 1-hop relationships
// list (relType/targetId/targetName/targetType, both directions) to entities,
// filtering by relationship_type client-side. Depth > 1 has no hosted
// equivalent.
func (t *restTransport) getRelatedEntitiesComposite(ctx context.Context, params map[string]interface{}, result interface{}) error {
	if depth, ok := intParam(params["depth"]); ok && depth > 1 {
		return &NotSupportedError{MemoryError{Message: `method "get_related_entities" with depth > 1 has no equivalent in the hosted REST API`}}
	}
	var entity map[string]interface{}
	if err := t.Call(ctx, "get_entity", map[string]interface{}{
		"entity_id": params["entity_id"],
	}, &entity); err != nil {
		return err
	}
	relType, _ := params["relationship_type"].(string)
	rels, _ := entity["relationships"].([]interface{})
	out := make([]map[string]interface{}, 0, len(rels))
	for _, r := range rels {
		rm, _ := r.(map[string]interface{})
		if rm == nil {
			continue
		}
		if relType != "" {
			if rt, _ := rm["rel_type"].(string); rt != relType {
				continue
			}
		}
		out = append(out, map[string]interface{}{
			"id":   rm["target_id"],
			"name": rm["target_name"],
			"type": rm["target_type"],
		})
	}
	return decodeInto(out, result)
}

// mergeDuplicateEntitiesComposite chains POST /entities/{id}/merge, an
// optional PUT /entities/{id} rename (canonical_name), and a final GET so the
// caller receives the surviving entity. The merge response's target_id is
// canonical-resolved, so it wins over the caller-supplied one.
func (t *restTransport) mergeDuplicateEntitiesComposite(ctx context.Context, params map[string]interface{}, result interface{}) error {
	var merge struct {
		Status   string `json:"status"`
		SourceID string `json:"source_id"`
		TargetID string `json:"target_id"`
	}
	if err := t.Call(ctx, "merge_entities", map[string]interface{}{
		"source_id": params["source_id"],
		"target_id": params["target_id"],
	}, &merge); err != nil {
		return err
	}
	targetID := merge.TargetID
	if targetID == "" {
		targetID, _ = params["target_id"].(string)
	}
	if name, _ := params["canonical_name"].(string); name != "" {
		if err := t.Call(ctx, "update_entity", map[string]interface{}{
			"entity_id": targetID,
			"name":      name,
		}, nil); err != nil {
			return err
		}
	}
	return t.Call(ctx, "get_entity", map[string]interface{}{"entity_id": targetID}, result)
}

// decodeInto round-trips a composite-built value through JSON into the typed
// struct the caller passed (mirrors the tail of Call).
func decodeInto(value interface{}, result interface{}) error {
	if result == nil {
		return nil
	}
	buf, err := json.Marshal(value)
	if err != nil {
		return &MemoryError{Message: "failed to re-marshal response", Cause: err}
	}
	if err := json.Unmarshal(buf, result); err != nil {
		return &MemoryError{Message: "failed to decode shaped response", Cause: err}
	}
	return nil
}

// intParam coerces the numeric types a params map can carry into an int.
func intParam(v interface{}) (int, bool) {
	switch n := v.(type) {
	case int:
		return n, true
	case int32:
		return int(n), true
	case int64:
		return int(n), true
	case float64:
		return int(n), true
	}
	return 0, false
}

func (t *restTransport) applyHeaders(ctx context.Context, req *http.Request, hasBody bool) error {
	if hasBody {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range t.headers {
		req.Header.Set(k, v)
	}
	token := t.apiKey
	if t.tokenProvider != nil {
		fresh, err := t.tokenProvider(ctx)
		if err != nil {
			return &AuthenticationError{MemoryError{Message: "token provider failed", Cause: err}}
		}
		token = fresh
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return nil
}
