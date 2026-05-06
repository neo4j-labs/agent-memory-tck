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
	endpoint      string
	apiKey        string
	tokenProvider TokenProvider
	headers       map[string]string
	httpClient    *http.Client
}

func newRestTransport(cfg Config, timeout time.Duration) Transport {
	return &restTransport{
		endpoint:      strings.TrimRight(cfg.Endpoint, "/"),
		apiKey:        cfg.APIKey,
		tokenProvider: cfg.TokenProvider,
		headers:       cfg.Headers,
		httpClient:    &http.Client{Timeout: timeout},
	}
}

func (t *restTransport) Connect(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, t.endpoint+"/conversations?limit=1", nil)
	if err != nil {
		return &ConnectionError{MemoryError{Message: "failed to create request", Cause: err}}
	}
	if err := t.applyHeaders(ctx, req, false); err != nil {
		return err
	}
	resp, err := t.httpClient.Do(req)
	if err != nil {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("connect to %s failed", t.endpoint), Cause: err}}
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
	"get_entity_by_name":       unsupportedRoute,
	"get_related_entities":     unsupportedRoute,
	"add_relationship":         unsupportedRoute,
	"merge_duplicate_entities": unsupportedRoute,
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
	"list_steps":                {method: http.MethodGet, path: "/reasoning/steps", queryParams: []string{"conversationId"}},
	"cypher_query":              {method: http.MethodPost, path: "/query", hasBody: true},
	"list_api_keys":             {method: http.MethodGet, path: "/auth/api-keys", queryParams: []string{"workspaceId"}},
	"create_api_key":            {method: http.MethodPost, path: "/auth/api-keys", hasBody: true},
	"revoke_api_key":            {method: http.MethodDelete, path: "/auth/api-keys/{keyId}", pathParams: []string{"keyId"}},
	"reveal_api_key":            {method: http.MethodGet, path: "/auth/api-keys/{keyId}/reveal", pathParams: []string{"keyId"}, queryParams: []string{"workspaceId"}},
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

func (t *restTransport) Call(ctx context.Context, method string, params map[string]interface{}, result interface{}) error {
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

	q := url.Values{}
	for _, name := range route.queryParams {
		v, ok := camel[name]
		if ok && v != nil {
			q.Set(name, fmt.Sprintf("%v", v))
			consumed[name] = true
		}
	}
	queryString := ""
	if len(q) > 0 {
		queryString = "?" + q.Encode()
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

	fullURL := t.endpoint + path + queryString
	req, err := http.NewRequestWithContext(ctx, route.method, fullURL, bodyReader)
	if err != nil {
		return &ConnectionError{MemoryError{Message: "failed to create request", Cause: err}}
	}
	if err := t.applyHeaders(ctx, req, route.hasBody); err != nil {
		return err
	}

	resp, err := t.httpClient.Do(req)
	if err != nil {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("request to %s failed", fullURL), Cause: err}}
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
