// Package main implements the HTTP bridge conformance server for the Go client.
//
// This server enables the Python TCK test suite to validate the Go client
// by proxying BaseAdapter method calls through the Go MemoryClient.
//
// Usage:
//
//	MEMORY_ENDPOINT=https://... go run ./conformance
//	# Then from the TCK repo:
//	pytest -m bronze --bridge-url http://localhost:3001
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"

	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

var client *memory.Client

func main() {
	endpoint := os.Getenv("MEMORY_ENDPOINT")
	if endpoint == "" {
		log.Fatal("Set MEMORY_ENDPOINT env var to the upstream service URL")
	}

	port := os.Getenv("TCK_BRIDGE_PORT")
	if port == "" {
		port = "3001"
	}

	var err error
	client, err = memory.New(memory.WithEndpoint(endpoint))
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}

	if err := client.Connect(context.Background()); err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}

	mux := http.NewServeMux()

	// Lifecycle
	mux.HandleFunc("POST /setup", handle(handleSetup))
	mux.HandleFunc("POST /teardown", handle(handleTeardown))
	mux.HandleFunc("POST /clear_all_data", handle(handleClearAllData))

	// Short-Term Memory
	mux.HandleFunc("POST /add_message", handle(handleAddMessage))
	mux.HandleFunc("POST /get_conversation", handle(handleGetConversation))
	mux.HandleFunc("POST /search_messages", handle(handleSearchMessages))
	mux.HandleFunc("POST /list_sessions", handle(handleListSessions))
	mux.HandleFunc("POST /delete_message", handle(handleDeleteMessage))
	mux.HandleFunc("POST /clear_session", handle(handleClearSession))

	// Long-Term Memory
	mux.HandleFunc("POST /add_entity", handle(handleAddEntity))
	mux.HandleFunc("POST /add_preference", handle(handleAddPreference))
	mux.HandleFunc("POST /add_fact", handle(handleAddFact))
	mux.HandleFunc("POST /search_entities", handle(handleSearchEntities))
	mux.HandleFunc("POST /search_preferences", handle(handleSearchPreferences))
	mux.HandleFunc("POST /get_entity_by_name", handle(handleGetEntityByName))
	mux.HandleFunc("POST /get_related_entities", handle(handleGetRelatedEntities))

	// Reasoning
	mux.HandleFunc("POST /start_trace", handle(handleStartTrace))
	mux.HandleFunc("POST /add_step", handle(handleAddStep))
	mux.HandleFunc("POST /record_tool_call", handle(handleRecordToolCall))
	mux.HandleFunc("POST /complete_trace", handle(handleCompleteTrace))
	mux.HandleFunc("POST /get_trace_with_steps", handle(handleGetTraceWithSteps))
	mux.HandleFunc("POST /list_traces", handle(handleListTraces))
	mux.HandleFunc("POST /get_tool_stats", handle(handleGetToolStats))

	// Gold
	mux.HandleFunc("POST /add_relationship", handle(handleAddRelationship))

	addr := ":" + port
	fmt.Printf("Go conformance server running on http://localhost%s\n", addr)
	fmt.Printf("Upstream: %s\n", endpoint)
	log.Fatal(http.ListenAndServe(addr, mux))
}

type handlerFunc func(ctx context.Context, body map[string]interface{}) (interface{}, error)

func handle(fn handlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		if r.ContentLength > 0 {
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				httpError(w, 400, err.Error())
				return
			}
		}
		if body == nil {
			body = map[string]interface{}{}
		}

		result, err := fn(r.Context(), body)
		if err != nil {
			httpError(w, 500, err.Error())
			return
		}

		if result == nil {
			w.WriteHeader(204)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(result)
	}
}

func httpError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func s(m map[string]interface{}, key string) string {
	v, _ := m[key].(string)
	return v
}

func i(m map[string]interface{}, key string, def int) int {
	v, ok := m[key].(float64)
	if !ok {
		return def
	}
	return int(v)
}

func f(m map[string]interface{}, key string, def float64) float64 {
	v, ok := m[key].(float64)
	if !ok {
		return def
	}
	return v
}

// --- Handlers ---

func handleSetup(_ context.Context, _ map[string]interface{}) (interface{}, error) {
	return map[string]bool{"ok": true}, nil
}

func handleTeardown(_ context.Context, _ map[string]interface{}) (interface{}, error) {
	return nil, nil
}

func handleClearAllData(ctx context.Context, _ map[string]interface{}) (interface{}, error) {
	return nil, client.ClearAllData(ctx)
}

func handleAddMessage(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.AddMessageParams){}
	if m, ok := body["metadata"].(map[string]interface{}); ok {
		opts = append(opts, memory.WithMetadata(m))
	}
	return client.ShortTerm.AddMessage(ctx, s(body, "session_id"), memory.MessageRole(s(body, "role")), s(body, "content"), opts...)
}

func handleGetConversation(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.GetConversationParams){}
	if _, ok := body["limit"]; ok {
		opts = append(opts, memory.WithLimit(i(body, "limit", 0)))
	}
	return client.ShortTerm.GetConversation(ctx, s(body, "session_id"), opts...)
}

func handleSearchMessages(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.SearchMessagesParams){
		memory.WithSearchLimit(i(body, "limit", 10)),
		memory.WithThreshold(f(body, "threshold", 0.7)),
	}
	if sid := s(body, "session_id"); sid != "" {
		opts = append(opts, memory.WithSessionID(sid))
	}
	return client.ShortTerm.SearchMessages(ctx, s(body, "query"), opts...)
}

func handleListSessions(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.ShortTerm.ListSessions(ctx, memory.WithListLimit(i(body, "limit", 100)))
}

func handleDeleteMessage(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	deleted, err := client.ShortTerm.DeleteMessage(ctx, s(body, "message_id"))
	return map[string]bool{"deleted": deleted}, err
}

func handleClearSession(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return nil, client.ShortTerm.ClearSession(ctx, s(body, "session_id"))
}

func handleAddEntity(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.AddEntityParams){}
	if d := s(body, "description"); d != "" {
		opts = append(opts, memory.WithDescription(d))
	}
	return client.LongTerm.AddEntity(ctx, s(body, "name"), s(body, "entity_type"), opts...)
}

func handleAddPreference(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.AddPreferenceParams){}
	if c := s(body, "context"); c != "" {
		opts = append(opts, memory.WithContext(c))
	}
	return client.LongTerm.AddPreference(ctx, s(body, "category"), s(body, "preference"), opts...)
}

func handleAddFact(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.LongTerm.AddFact(ctx, s(body, "subject"), s(body, "predicate"), s(body, "obj"))
}

func handleSearchEntities(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.LongTerm.SearchEntities(ctx, s(body, "query"), i(body, "limit", 10))
}

func handleSearchPreferences(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.SearchPrefsParams){
		memory.WithPrefsLimit(i(body, "limit", 10)),
	}
	if c := s(body, "category"); c != "" {
		opts = append(opts, memory.WithCategory(c))
	}
	return client.LongTerm.SearchPreferences(ctx, s(body, "query"), opts...)
}

func handleGetEntityByName(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.LongTerm.GetEntityByName(ctx, s(body, "name"))
}

func handleGetRelatedEntities(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.RelatedParams){}
	if t := s(body, "relationship_type"); t != "" {
		opts = append(opts, memory.WithRelType(t))
	}
	if _, ok := body["depth"]; ok {
		opts = append(opts, memory.WithDepth(i(body, "depth", 1)))
	}
	return client.LongTerm.GetRelatedEntities(ctx, s(body, "entity_id"), opts...)
}

func handleStartTrace(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.Reasoning.StartTrace(ctx, s(body, "session_id"), s(body, "task"))
}

func handleAddStep(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.AddStepParams){}
	if t := s(body, "thought"); t != "" {
		opts = append(opts, memory.WithThought(t))
	}
	if a := s(body, "action"); a != "" {
		opts = append(opts, memory.WithAction(a))
	}
	if o := s(body, "observation"); o != "" {
		opts = append(opts, memory.WithObservation(o))
	}
	return client.Reasoning.AddStep(ctx, s(body, "trace_id"), opts...)
}

func handleRecordToolCall(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	args, _ := body["arguments"].(map[string]interface{})
	if args == nil {
		args = map[string]interface{}{}
	}
	opts := []func(*memory.RecordToolCallParams){}
	if st := s(body, "status"); st != "" {
		opts = append(opts, memory.WithStatus(memory.ToolCallStatus(st)))
	}
	if r, ok := body["result"]; ok {
		opts = append(opts, memory.WithResult(r))
	}
	if _, ok := body["duration_ms"]; ok {
		opts = append(opts, memory.WithDurationMs(i(body, "duration_ms", 0)))
	}
	if e := s(body, "error"); e != "" {
		opts = append(opts, memory.WithError(e))
	}
	return client.Reasoning.RecordToolCall(ctx, s(body, "step_id"), s(body, "tool_name"), args, opts...)
}

func handleCompleteTrace(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.CompleteTraceParams){}
	if o := s(body, "outcome"); o != "" {
		opts = append(opts, memory.WithOutcome(o))
	}
	if v, ok := body["success"].(bool); ok {
		opts = append(opts, memory.WithSuccess(v))
	}
	return client.Reasoning.CompleteTrace(ctx, s(body, "trace_id"), opts...)
}

func handleGetTraceWithSteps(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.Reasoning.GetTraceWithSteps(ctx, s(body, "trace_id"))
}

func handleListTraces(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	opts := []func(*memory.ListTracesParams){
		memory.WithTracesLimit(i(body, "limit", 100)),
	}
	if sid := s(body, "session_id"); sid != "" {
		opts = append(opts, memory.WithTraceSessionID(sid))
	}
	return client.Reasoning.ListTraces(ctx, opts...)
}

func handleGetToolStats(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.Reasoning.GetToolStats(ctx, s(body, "tool_name"))
}

func handleAddRelationship(ctx context.Context, body map[string]interface{}) (interface{}, error) {
	return client.LongTerm.AddRelationship(ctx, s(body, "source_id"), s(body, "target_id"), s(body, "relationship_type"))
}
