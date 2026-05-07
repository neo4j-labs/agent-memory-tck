package memory

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestRestRouteTable(t *testing.T) {
	required := []string{
		"setup", "teardown", "clear_all_data",
		"add_message", "get_conversation", "search_messages", "list_sessions",
		"clear_session", "add_entity", "search_entities", "record_tool_call",

		// Volume 5 / Platinum
		"create_conversation", "list_conversations", "get_conversation_metadata",
		"delete_conversation", "get_context", "bulk_add_messages",
		"get_observations", "get_reflections",
		"list_entities", "get_entity", "update_entity", "delete_entity",
		"set_entity_feedback", "get_entity_history", "merge_entities", "get_entity_graph",
		"explain_step", "get_trace_by_conversation", "get_entity_provenance",
		"record_step", "list_steps", "cypher_query",
		"list_api_keys", "create_api_key", "revoke_api_key",
		"reveal_api_key", "refresh_access_token",
	}
	for _, m := range required {
		if _, ok := restRoutes[m]; !ok {
			t.Errorf("missing route: %s", m)
		}
	}

	if restRoutes["setup"] != noopRoute {
		t.Error("setup should be a noop")
	}
	if restRoutes["teardown"] != noopRoute {
		t.Error("teardown should be a noop")
	}

	for _, m := range []string{
		"add_preference", "add_fact", "search_preferences", "get_entity_by_name",
		"get_related_entities", "add_relationship", "merge_duplicate_entities",
		"start_trace", "add_step", "complete_trace", "get_trace_with_steps",
		"list_traces", "get_tool_stats", "get_similar_traces", "delete_message",
	} {
		r, ok := restRoutes[m]
		if !ok {
			t.Errorf("expected unsupported entry: %s", m)
			continue
		}
		if r != unsupportedRoute {
			t.Errorf("expected %s to be unsupportedRoute", m)
		}
	}
}

func TestRestTransportUnknownMethod(t *testing.T) {
	rt := newRestTransport(Config{Endpoint: "https://example.com/v1", APIKey: "k"}, 0).(*restTransport)
	err := rt.Call(context.Background(), "not_a_real_method", nil, nil)
	if err == nil {
		t.Fatal("expected error")
	}
	var nse *NotSupportedError
	if !errors.As(err, &nse) {
		t.Errorf("expected NotSupportedError, got %T: %v", err, err)
	}
	if !strings.Contains(err.Error(), "not a real method") && !strings.Contains(err.Error(), "not_a_real_method") {
		t.Errorf("error message should mention method name; got %q", err.Error())
	}
}

func TestRestTransportUnsupportedMethod(t *testing.T) {
	rt := newRestTransport(Config{Endpoint: "https://example.com/v1", APIKey: "k"}, 0).(*restTransport)
	err := rt.Call(context.Background(), "add_preference",
		map[string]interface{}{"category": "x", "preference": "y"}, nil)
	if err == nil {
		t.Fatal("expected error")
	}
	var nse *NotSupportedError
	if !errors.As(err, &nse) {
		t.Errorf("expected NotSupportedError, got %T: %v", err, err)
	}
	if !strings.Contains(err.Error(), "no equivalent") {
		t.Errorf("error should explain why; got %q", err.Error())
	}
}

func TestNewTransportPicksRestForV1(t *testing.T) {
	rt := newTransport(Config{Endpoint: "https://memory.neo4jlabs.com/v1"})
	if _, ok := rt.(*restTransport); !ok {
		t.Errorf("expected *restTransport for /v1 endpoint, got %T", rt)
	}
}

func TestNewTransportPicksBridgeForLocalhost(t *testing.T) {
	bt := newTransport(Config{Endpoint: "http://localhost:3001"})
	if _, ok := bt.(*bridgeTransport); !ok {
		t.Errorf("expected *bridgeTransport for localhost endpoint, got %T", bt)
	}
}

func TestNewTransportRespectsExplicitMode(t *testing.T) {
	bt := newTransport(Config{
		Endpoint:      "https://memory.neo4jlabs.com/v1",
		TransportMode: TransportBridge,
	})
	if _, ok := bt.(*bridgeTransport); !ok {
		t.Errorf("explicit bridge mode ignored; got %T", bt)
	}
}
