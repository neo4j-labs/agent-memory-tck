package memory

import (
	"reflect"
	"testing"
)

func TestSnakeKey(t *testing.T) {
	cases := map[string]string{
		"":            "",
		"id":          "id",
		"userId":      "user_id",
		"createdAt":   "created_at",
		"messageId":   "message_id",
		"sourceStage": "source_stage",
		"v1Test":      "v1_test",
	}
	for in, want := range cases {
		if got := snakeKey(in); got != want {
			t.Errorf("snakeKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestCamelKey(t *testing.T) {
	cases := map[string]string{
		"":               "",
		"id":             "id",
		"user_id":        "userId",
		"created_at":     "createdAt",
		"message_id":     "messageId",
		"source_stage":   "sourceStage",
		"already_snake":  "alreadySnake",
	}
	for in, want := range cases {
		if got := camelKey(in); got != want {
			t.Errorf("camelKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSnakeToCamelDict(t *testing.T) {
	in := map[string]interface{}{"user_id": "alice", "metadata": map[string]interface{}{"is_active": true}}
	want := map[string]interface{}{"userId": "alice", "metadata": map[string]interface{}{"isActive": true}}
	got := snakeToCamel(in)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("snakeToCamel = %#v, want %#v", got, want)
	}
}

func TestCamelToSnakeDict(t *testing.T) {
	in := map[string]interface{}{"userId": "alice", "metadata": map[string]interface{}{"isActive": true}}
	want := map[string]interface{}{"user_id": "alice", "metadata": map[string]interface{}{"is_active": true}}
	got := camelToSnake(in)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("camelToSnake = %#v, want %#v", got, want)
	}
}

func TestCamelSnakeRoundtrip(t *testing.T) {
	original := map[string]interface{}{
		"user_id": "alice",
		"items": []interface{}{
			map[string]interface{}{"item_id": 1, "is_active": true},
		},
	}
	roundtrip := camelToSnake(snakeToCamel(original))
	if !reflect.DeepEqual(roundtrip, original) {
		t.Errorf("roundtrip not equal:\n got = %#v\nwant = %#v", roundtrip, original)
	}
}

func TestCasingIgnoresPrimitives(t *testing.T) {
	for _, v := range []interface{}{"hello", 42, true, nil, 3.14} {
		if got := snakeToCamel(v); !reflect.DeepEqual(got, v) {
			t.Errorf("snakeToCamel(%v) = %v, want %v", v, got, v)
		}
	}
}

func TestIsRestEndpoint(t *testing.T) {
	cases := map[string]bool{
		"https://memory.neo4jlabs.com/v1":  true,
		"https://memory.neo4jlabs.com/v2/": true,
		"https://memory.neo4jlabs.com":     false,
		"http://localhost:3001":            false,
		"http://localhost:3001/":           false,
		"https://example.com/v1/something": true,
		"https://example.com/api/v1":       true,
	}
	for in, want := range cases {
		if got := isRestEndpoint(in); got != want {
			t.Errorf("isRestEndpoint(%q) = %v, want %v", in, got, want)
		}
	}
}
