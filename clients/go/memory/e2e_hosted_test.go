//go:build e2e

// End-to-end tests against the live hosted Neo4j Agent Memory Service.
//
// Build tag `e2e` ensures these tests only run when explicitly invoked:
//
//	go test -tags=e2e ./memory/
//
// They read MEMORY_API_KEY (and optionally MEMORY_ENDPOINT) from a `.env`
// file at the repo root or from environment variables (CI). When the key
// is empty, every test calls t.Skip().
package memory_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/joho/godotenv"
	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

func init() {
	// Walk up looking for the first .env we can use.
	cwd, err := os.Getwd()
	if err != nil {
		return
	}
	for i := 0; i < 6; i++ {
		candidate := filepath.Join(cwd, ".env")
		if _, err := os.Stat(candidate); err == nil {
			_ = godotenv.Load(candidate)
			break
		}
		parent := filepath.Dir(cwd)
		if parent == cwd {
			break
		}
		cwd = parent
	}
}

func e2eEndpoint() string {
	if v := os.Getenv("MEMORY_ENDPOINT"); v != "" {
		return v
	}
	return "https://memory.neo4jlabs.com/v1"
}

func e2eAPIKey(t *testing.T) string {
	t.Helper()
	key := strings.TrimSpace(os.Getenv("MEMORY_API_KEY"))
	if key == "" {
		t.Skip("MEMORY_API_KEY not set; skipping e2e test")
	}
	return key
}

func e2eClient(t *testing.T) *memory.Client {
	t.Helper()
	c, err := memory.New(
		memory.WithEndpoint(e2eEndpoint()),
		memory.WithAPIKey(e2eAPIKey(t)),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := c.Connect(context.Background()); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	t.Cleanup(func() { _ = c.Close(context.Background()) })
	return c
}

func e2eUserID(t *testing.T) string {
	t.Helper()
	prefix := os.Getenv("MEMORY_E2E_USER_ID")
	if prefix == "" {
		prefix = "tck-e2e-go"
	}
	buf := make([]byte, 4)
	_, _ = rand.Read(buf)
	return prefix + "-" + hex.EncodeToString(buf)
}

func e2eTransientConv(t *testing.T, c *memory.Client) string {
	t.Helper()
	conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{
		UserID: e2eUserID(t),
	})
	if err != nil {
		t.Fatalf("CreateConversation: %v", err)
	}
	t.Cleanup(func() {
		_ = c.ShortTerm.DeleteConversation(context.Background(), conv.ID)
	})
	return conv.ID
}

// ---------------------------------------------------------------------------
// Connection + auth
// ---------------------------------------------------------------------------

func TestE2EConnect(t *testing.T) {
	_ = e2eClient(t)
}

func TestE2EBadKeyFails(t *testing.T) {
	_ = e2eAPIKey(t) // skip if no real key configured
	bad, err := memory.New(
		memory.WithEndpoint(e2eEndpoint()),
		memory.WithAPIKey("nams_obviously_not_real_token"),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer bad.Close(context.Background())
	err = bad.Connect(context.Background())
	if err == nil {
		t.Fatal("expected auth failure with bogus token")
	}
	var ae *memory.AuthenticationError
	if !errors.As(err, &ae) {
		t.Errorf("expected AuthenticationError, got %T: %v", err, err)
	}
}

// ---------------------------------------------------------------------------
// Short-Term
// ---------------------------------------------------------------------------

func TestE2ECreateAndListConversation(t *testing.T) {
	c := e2eClient(t)
	id := e2eTransientConv(t, c)
	if id == "" {
		t.Fatal("conversation id empty")
	}
	convs, err := c.ShortTerm.ListConversations(context.Background(), 50)
	if err != nil {
		t.Fatalf("ListConversations: %v", err)
	}
	if convs == nil {
		t.Error("expected non-nil slice")
	}
}

func TestE2EAddMessage(t *testing.T) {
	c := e2eClient(t)
	id := e2eTransientConv(t, c)
	msg, err := c.ShortTerm.AddMessage(context.Background(), id, memory.RoleUser,
		"John works at Acme Corp.")
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if msg.ID == "" {
		t.Error("expected message id")
	}
}

func TestE2EBulkAddMessages(t *testing.T) {
	c := e2eClient(t)
	id := e2eTransientConv(t, c)
	msgs := []memory.BulkMessageInput{
		{Role: memory.RoleUser, Content: "bulk-0"},
		{Role: memory.RoleUser, Content: "bulk-1"},
		{Role: memory.RoleUser, Content: "bulk-2"},
	}
	out, err := c.ShortTerm.BulkAddMessages(context.Background(), id, msgs)
	if err != nil {
		t.Fatalf("BulkAddMessages: %v", err)
	}
	if len(out) != 3 {
		t.Errorf("got %d messages, want 3", len(out))
	}
}

func TestE2EGetContext(t *testing.T) {
	c := e2eClient(t)
	id := e2eTransientConv(t, c)
	if _, err := c.ShortTerm.AddMessage(context.Background(), id, memory.RoleUser, "Hello."); err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	ctx, err := c.ShortTerm.GetContext(context.Background(), id)
	if err != nil {
		t.Fatalf("GetContext: %v", err)
	}
	// Reflections + observations may be empty for new conversations — only
	// require the shape.
	if ctx.RecentMessages == nil {
		t.Error("expected non-nil RecentMessages")
	}
}

// ---------------------------------------------------------------------------
// Long-Term
// ---------------------------------------------------------------------------

func TestE2EGetEntityGraph(t *testing.T) {
	c := e2eClient(t)
	graph, err := c.LongTerm.GetEntityGraph(context.Background())
	if err != nil {
		t.Fatalf("GetEntityGraph: %v", err)
	}
	if graph.Nodes == nil || graph.Edges == nil {
		t.Error("graph should expose Nodes and Edges slices")
	}
}

func TestE2ESearchEntities(t *testing.T) {
	c := e2eClient(t)
	if _, err := c.LongTerm.SearchEntities(context.Background(), "anything", 5); err != nil {
		t.Errorf("SearchEntities: %v", err)
	}
}

func TestE2EListEntities(t *testing.T) {
	c := e2eClient(t)
	if _, err := c.LongTerm.ListEntities(context.Background(), "", 5); err != nil {
		t.Errorf("ListEntities: %v", err)
	}
}

// ---------------------------------------------------------------------------
// Reasoning
// ---------------------------------------------------------------------------

func TestE2ERecordStepAndGetTrace(t *testing.T) {
	c := e2eClient(t)
	id := e2eTransientConv(t, c)

	step, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{
		ConversationID: id,
		Reasoning:      "test hypothesis",
		ActionTaken:    "ran assertion",
		Result:         "passed",
	})
	if err != nil {
		t.Fatalf("RecordStep: %v", err)
	}
	if step.ID == "" {
		t.Error("expected step id")
	}

	trace, err := c.Reasoning.GetTraceByConversation(context.Background(), id)
	if err != nil {
		t.Fatalf("GetTraceByConversation: %v", err)
	}
	if trace.ConversationID != id {
		t.Errorf("trace.ConversationID = %q, want %q", trace.ConversationID, id)
	}
}

// ---------------------------------------------------------------------------
// Cypher console
// ---------------------------------------------------------------------------

func TestE2ECypherReadOnly(t *testing.T) {
	c := e2eClient(t)
	res, err := c.Query.Cypher(context.Background(), memory.CypherInput{
		Cypher: "MATCH (n) RETURN count(n) AS total",
	})
	if err != nil {
		t.Fatalf("Cypher: %v", err)
	}
	if len(res.Columns) == 0 || res.Columns[0] != "total" {
		t.Errorf("columns = %v, want [total]", res.Columns)
	}
}
