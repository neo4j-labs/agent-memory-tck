package memory

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

// MCPHandler returns an http.Handler that exposes the 12 standard MCP tools
// matching the hosted server at https://memory.neo4jlabs.com/mcp.
//
//	client, _ := memory.New(
//	    memory.WithEndpoint("https://memory.neo4jlabs.com/v1"),
//	    memory.WithAPIKey(os.Getenv("MEMORY_API_KEY")),
//	)
//	http.Handle("/mcp/", client.MCPHandler())
func (c *Client) MCPHandler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("POST /tools/list", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]interface{}{"tools": MCPTools()})
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
		result, err := MCPDispatch(r.Context(), c, req.Name, req.Arguments)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, result)
	})

	return mux
}

// MCPToolDefinition is a JSON Schema-compatible MCP tool descriptor.
type MCPToolDefinition struct {
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	InputSchema map[string]interface{} `json:"inputSchema"`
}

// MCPTools returns the 12 standard memory tools matching memory.neo4jlabs.com/mcp.
func MCPTools() []MCPToolDefinition {
	prop := func(t string, desc string) map[string]interface{} {
		return map[string]interface{}{"type": t, "description": desc}
	}
	obj := func(props map[string]interface{}, required ...string) map[string]interface{} {
		s := map[string]interface{}{"type": "object", "properties": props}
		if len(required) > 0 {
			s["required"] = required
		}
		return s
	}

	return []MCPToolDefinition{
		{Name: "memory_create_conversation", Description: "Create a new conversation session for a user.",
			InputSchema: obj(map[string]interface{}{
				"user_id":  prop("string", "User identifier"),
				"metadata": map[string]interface{}{"type": "object"},
			}, "user_id")},
		{Name: "memory_add_messages", Description: "Append one or more messages to a conversation.",
			InputSchema: obj(map[string]interface{}{
				"conversation_id": prop("string", "Conversation id"),
				"messages":        map[string]interface{}{"type": "array"},
			}, "conversation_id", "messages")},
		{Name: "memory_get_context", Description: "Three-tier context (reflections + observations + recent messages).",
			InputSchema: obj(map[string]interface{}{
				"conversation_id": prop("string", "Conversation id"),
			}, "conversation_id")},
		{Name: "memory_search_messages", Description: "Search messages within a conversation.",
			InputSchema: obj(map[string]interface{}{
				"conversation_id": prop("string", "Conversation id"),
				"query":           prop("string", "Search query"),
				"limit":           prop("number", "Max results"),
			}, "conversation_id", "query")},
		{Name: "memory_search_entities", Description: "Search the knowledge graph for entities.",
			InputSchema: obj(map[string]interface{}{
				"query": prop("string", "Search query"),
				"type":  prop("string", "Entity type filter"),
				"limit": prop("number", "Max results"),
			}, "query")},
		{Name: "memory_get_entity", Description: "Fetch one entity (with relationships) by id.",
			InputSchema: obj(map[string]interface{}{
				"entity_id": prop("string", "Entity id"),
			}, "entity_id")},
		{Name: "memory_add_entity", Description: "Manually create an entity.",
			InputSchema: obj(map[string]interface{}{
				"name":        prop("string", "Entity name"),
				"type":        prop("string", "Entity type"),
				"description": prop("string", "Description"),
			}, "name", "type")},
		{Name: "memory_get_entity_history", Description: "All conversations that mentioned this entity.",
			InputSchema: obj(map[string]interface{}{
				"entity_id": prop("string", "Entity id"),
			}, "entity_id")},
		{Name: "memory_record_step", Description: "Log a reasoning step under a conversation.",
			InputSchema: obj(map[string]interface{}{
				"conversation_id": prop("string", "Conversation id"),
				"reasoning":       prop("string", "Reasoning text"),
				"action_taken":    prop("string", "Action description"),
				"result":          prop("string", "Optional result"),
			}, "conversation_id", "reasoning", "action_taken")},
		{Name: "memory_record_tool_call", Description: "Log a tool invocation tied to a reasoning step.",
			InputSchema: obj(map[string]interface{}{
				"step_id":     prop("string", "Step id"),
				"tool_name":   prop("string", "Tool name"),
				"input":       prop("string", "Input as JSON string"),
				"output":      prop("string", "Output as JSON string"),
				"status":      prop("string", "success | error | timeout"),
				"duration_ms": prop("number", "Duration ms"),
			}, "tool_name", "status")},
		{Name: "memory_get_trace", Description: "Full reasoning trace for a conversation.",
			InputSchema: obj(map[string]interface{}{
				"conversation_id": prop("string", "Conversation id"),
			}, "conversation_id")},
		{Name: "memory_explain_decision", Description: "Detailed explanation of one reasoning step.",
			InputSchema: obj(map[string]interface{}{
				"step_id": prop("string", "Step id"),
			}, "step_id")},
	}
}

// MCPDispatch executes one of the 12 standard MCP tools against a Client.
func MCPDispatch(ctx context.Context, c *Client, name string, args map[string]interface{}) (interface{}, error) {
	switch name {
	case "memory_create_conversation":
		return c.ShortTerm.CreateConversation(ctx, CreateConversationParams{
			UserID:   getStr(args, "user_id"),
			Metadata: getMap(args, "metadata"),
		})
	case "memory_add_messages":
		conversationID := getStr(args, "conversation_id")
		raw, _ := args["messages"].([]interface{})
		msgs := make([]BulkMessageInput, 0, len(raw))
		for _, item := range raw {
			m, _ := item.(map[string]interface{})
			if m == nil {
				continue
			}
			msgs = append(msgs, BulkMessageInput{
				Role:     MessageRole(getStr(m, "role")),
				Content:  getStr(m, "content"),
				Metadata: getMap(m, "metadata"),
			})
		}
		if len(msgs) == 1 {
			opts := []func(*AddMessageParams){}
			if msgs[0].Metadata != nil {
				opts = append(opts, WithMetadata(msgs[0].Metadata))
			}
			msg, err := c.ShortTerm.AddMessage(ctx, conversationID, msgs[0].Role, msgs[0].Content, opts...)
			if err != nil {
				return nil, err
			}
			return []*Message{msg}, nil
		}
		return c.ShortTerm.BulkAddMessages(ctx, conversationID, msgs)
	case "memory_get_context":
		return c.ShortTerm.GetContext(ctx, getStr(args, "conversation_id"))
	case "memory_search_messages":
		return c.ShortTerm.SearchMessages(ctx, getStr(args, "query"),
			WithSessionID(getStr(args, "conversation_id")),
			WithSearchLimit(getInt(args, "limit", 10)),
		)
	case "memory_search_entities":
		return c.LongTerm.SearchEntities(ctx, getStr(args, "query"), getInt(args, "limit", 10))
	case "memory_get_entity":
		return c.LongTerm.GetEntity(ctx, getStr(args, "entity_id"))
	case "memory_add_entity":
		opts := []func(*AddEntityParams){}
		if d := getStr(args, "description"); d != "" {
			opts = append(opts, WithDescription(d))
		}
		return c.LongTerm.AddEntity(ctx, getStr(args, "name"), getStr(args, "type"), opts...)
	case "memory_get_entity_history":
		return c.LongTerm.GetEntityHistory(ctx, getStr(args, "entity_id"))
	case "memory_record_step":
		return c.Reasoning.RecordStep(ctx, RecordStepInput{
			ConversationID: getStr(args, "conversation_id"),
			Reasoning:      getStr(args, "reasoning"),
			ActionTaken:    getStr(args, "action_taken"),
			Result:         getStr(args, "result"),
		})
	case "memory_record_tool_call":
		stepID := getStr(args, "step_id")
		toolName := getStr(args, "tool_name")
		argsMap := map[string]interface{}{"input": getStr(args, "input")}
		opts := []func(*RecordToolCallParams){
			WithStatus(ToolCallStatus(getStr(args, "status"))),
		}
		if out := getStr(args, "output"); out != "" {
			opts = append(opts, WithResult(out))
		}
		if d := getInt(args, "duration_ms", 0); d > 0 {
			opts = append(opts, WithDurationMs(d))
		}
		return c.Reasoning.RecordToolCall(ctx, stepID, toolName, argsMap, opts...)
	case "memory_get_trace":
		return c.Reasoning.GetTraceByConversation(ctx, getStr(args, "conversation_id"))
	case "memory_explain_decision":
		return c.Reasoning.ExplainStep(ctx, getStr(args, "step_id"))
	default:
		return nil, fmt.Errorf("unknown memory tool: %s", name)
	}
}

func getStr(m map[string]interface{}, key string) string {
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

func getMap(m map[string]interface{}, key string) map[string]interface{} {
	v, ok := m[key].(map[string]interface{})
	if !ok {
		return nil
	}
	return v
}

func getInt(m map[string]interface{}, key string, def int) int {
	v, ok := m[key].(float64)
	if !ok {
		return def
	}
	return int(v)
}

func writeJSON(w http.ResponseWriter, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
