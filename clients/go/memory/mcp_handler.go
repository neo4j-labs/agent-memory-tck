package memory

import (
	"encoding/json"
	"net/http"
)

// MCPHandler returns an http.Handler that exposes all memory operations
// as MCP-compatible endpoints. Mount this on any Go HTTP server.
//
//	client, _ := memory.New(memory.WithEndpoint("https://..."))
//	http.Handle("/mcp/", client.MCPHandler())
func (c *Client) MCPHandler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("POST /tools/list", func(w http.ResponseWriter, r *http.Request) {
		tools := []map[string]interface{}{
			{
				"name":        "memory.addMessage",
				"description": "Add a message to a conversation session.",
			},
			{
				"name":        "memory.getConversation",
				"description": "Retrieve conversation messages by session ID.",
			},
			{
				"name":        "memory.searchMessages",
				"description": "Search messages by semantic similarity.",
			},
			{
				"name":        "memory.addEntity",
				"description": "Create an entity in the knowledge graph.",
			},
			{
				"name":        "memory.searchEntities",
				"description": "Search entities by semantic similarity.",
			},
			{
				"name":        "memory.addFact",
				"description": "Store a subject-predicate-object fact triple.",
			},
			{
				"name":        "memory.listSessions",
				"description": "List all conversation sessions.",
			},
		}
		writeJSON(w, map[string]interface{}{"tools": tools})
	})

	mux.HandleFunc("POST /tools/call", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Name      string                 `json:"name"`
			Arguments map[string]interface{} `json:"arguments"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}

		ctx := r.Context()
		var result interface{}
		var err error

		switch req.Name {
		case "memory.addMessage":
			result, err = c.ShortTerm.AddMessage(ctx,
				str(req.Arguments, "sessionId"),
				MessageRole(str(req.Arguments, "role")),
				str(req.Arguments, "content"),
			)
		case "memory.getConversation":
			result, err = c.ShortTerm.GetConversation(ctx, str(req.Arguments, "sessionId"))
		case "memory.searchMessages":
			result, err = c.ShortTerm.SearchMessages(ctx, str(req.Arguments, "query"))
		case "memory.addEntity":
			result, err = c.LongTerm.AddEntity(ctx,
				str(req.Arguments, "name"),
				str(req.Arguments, "entityType"),
			)
		case "memory.searchEntities":
			result, err = c.LongTerm.SearchEntities(ctx, str(req.Arguments, "query"), 10)
		case "memory.addFact":
			result, err = c.LongTerm.AddFact(ctx,
				str(req.Arguments, "subject"),
				str(req.Arguments, "predicate"),
				str(req.Arguments, "object"),
			)
		case "memory.listSessions":
			result, err = c.ShortTerm.ListSessions(ctx)
		default:
			writeError(w, http.StatusBadRequest, "unknown tool: "+req.Name)
			return
		}

		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, result)
	})

	return mux
}

func str(m map[string]interface{}, key string) string {
	v, ok := m[key]
	if !ok {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return s
}

func writeJSON(w http.ResponseWriter, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
