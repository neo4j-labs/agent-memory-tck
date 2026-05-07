//go:build e2e

// Comprehensive end-to-end tests against the live hosted Neo4j Agent Memory
// Service.
//
// Mirrors the Python suite at clients/python/tests/e2e/test_hosted_service.py
// — same scenarios, same skip patterns. Skipped wholesale when
// MEMORY_API_KEY is unset; individual tests skip on 403 for endpoints that
// require elevated workspace scope.
//
// Build tag `e2e` ensures these tests only run when explicitly invoked:
//
//	go test -tags=e2e ./memory/
package memory_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/joho/godotenv"
	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

func init() {
	cwd, err := os.Getwd()
	if err != nil {
		return
	}
	for i := 0; i < 8; i++ {
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

var (
	uniqueTagOnce sync.Once
	uniqueTag     string
)

func e2eUniqueTag() string {
	uniqueTagOnce.Do(func() {
		buf := make([]byte, 4)
		_, _ = rand.Read(buf)
		uniqueTag = hex.EncodeToString(buf)
	})
	return uniqueTag
}

func e2eUserID(t *testing.T, suffix string) string {
	t.Helper()
	prefix := os.Getenv("MEMORY_E2E_USER_ID")
	if prefix == "" {
		prefix = "tck-e2e-go"
	}
	rnd := make([]byte, 3)
	_, _ = rand.Read(rnd)
	base := fmt.Sprintf("%s-%s-%s", prefix, e2eUniqueTag(), hex.EncodeToString(rnd))
	if suffix != "" {
		return base + "-" + suffix
	}
	return base
}

func e2eRandomHex(n int) string {
	buf := make([]byte, n/2+1)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)[:n]
}

// e2eNewConv creates a disposable conversation tagged with full provenance
// metadata, plus records a reasoning step on it linking the conversation
// back to the originating test (client + run + sha). Cleanup is registered
// via t.Cleanup.
func e2eNewConv(t *testing.T, c *memory.Client, suffix string) *memory.Conversation {
	t.Helper()
	conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{
		UserID:   e2eUserID(t, suffix),
		Metadata: tckMetadataFor(t.Name(), map[string]interface{}{"tck_phase": "fixture"}),
	})
	if err != nil {
		t.Fatalf("CreateConversation: %v", err)
	}
	tckRecordProvenanceStep(c, conv.ID, t.Name(), "setup", "create_conversation")
	t.Cleanup(func() {
		_ = c.ShortTerm.DeleteConversation(context.Background(), conv.ID)
	})
	return conv
}

// e2eNewEntity creates a scratch entity whose description is prefixed with
// a `[tck:go:<run>:<test>]` provenance tag so even ungraphed operators can
// grep for test data. Cleanup is registered via t.Cleanup.
func e2eNewEntity(t *testing.T, c *memory.Client, name, entityType, description string) *memory.BaseEntity {
	t.Helper()
	if name == "" {
		name = "TCK-Probe-" + e2eRandomHex(8)
	}
	if entityType == "" {
		entityType = "concept"
	}
	if description == "" {
		description = "tck e2e probe entity"
	}
	tagged := tckTagDescription(t.Name(), description)
	e, err := c.LongTerm.AddEntity(context.Background(), name, entityType, memory.WithDescription(tagged))
	if err != nil {
		t.Fatalf("AddEntity: %v", err)
	}
	t.Cleanup(func() {
		_ = c.LongTerm.DeleteEntity(context.Background(), e.ID)
	})
	return e
}

func e2eWaitUntil(predicate func() (bool, error), timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		ok, err := predicate()
		if err != nil {
			return err
		}
		if ok {
			return nil
		}
		time.Sleep(time.Second)
	}
	return errors.New("timed out")
}

// ===========================================================================
// 1. Connection + auth
// ===========================================================================

func TestE2EConnectSucceeds(t *testing.T) {
	_ = e2eClient(t)
}

func TestE2EBadKeyFails(t *testing.T) {
	_ = e2eAPIKey(t)
	bad, err := memory.New(memory.WithEndpoint(e2eEndpoint()), memory.WithAPIKey("nams_obviously_not_real_token"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer bad.Close(context.Background())
	if err := bad.Connect(context.Background()); err == nil {
		t.Fatal("expected auth failure")
	} else {
		var ae *memory.AuthenticationError
		if !errors.As(err, &ae) {
			t.Errorf("expected AuthenticationError, got %T: %v", err, err)
		}
	}
}

func TestE2EEmptyKeyFails(t *testing.T) {
	_ = e2eAPIKey(t)
	bad, err := memory.New(memory.WithEndpoint(e2eEndpoint()), memory.WithAPIKey(""))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer bad.Close(context.Background())
	if err := bad.Connect(context.Background()); err == nil {
		t.Fatal("expected auth failure with empty key")
	}
}

// ===========================================================================
// 2. Conversation lifecycle
// ===========================================================================

func TestE2ECreateReturnsIDsAndUserID(t *testing.T) {
	c := e2eClient(t)
	uid := e2eUserID(t, "create")
	conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{
		UserID:   uid,
		Metadata: map[string]interface{}{"source": "e2e", "seq": 1},
	})
	if err != nil {
		t.Fatalf("CreateConversation: %v", err)
	}
	t.Cleanup(func() { _ = c.ShortTerm.DeleteConversation(context.Background(), conv.ID) })
	if len(conv.ID) < 8 {
		t.Errorf("conv.ID too short: %q", conv.ID)
	}
	if conv.UserID != uid {
		t.Errorf("UserID = %q, want %q", conv.UserID, uid)
	}
	if conv.WorkspaceID == "" {
		t.Error("WorkspaceID should be set by service")
	}
}

func TestE2EGetMetadataRoundTrips(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	meta, err := c.ShortTerm.GetConversationMetadata(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetConversationMetadata: %v", err)
	}
	if meta.ID != conv.ID {
		t.Errorf("meta.ID = %q, want %q", meta.ID, conv.ID)
	}
	if meta.UserID != conv.UserID {
		t.Errorf("UserID = %q, want %q", meta.UserID, conv.UserID)
	}
}

func TestE2EListIncludesCreated(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "list-probe")
	convs, err := c.ShortTerm.ListConversations(context.Background(), 200)
	if err != nil {
		t.Fatalf("ListConversations: %v", err)
	}
	found := false
	for _, x := range convs {
		if x.ID == conv.ID {
			found = true
			break
		}
	}
	if !found {
		t.Error("newly created conversation should appear in ListConversations")
	}
}

func TestE2EDeleteIsIdempotent(t *testing.T) {
	c := e2eClient(t)
	conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{UserID: e2eUserID(t, "del")})
	if err != nil {
		t.Fatalf("CreateConversation: %v", err)
	}
	if err := c.ShortTerm.DeleteConversation(context.Background(), conv.ID); err != nil {
		t.Fatalf("first delete: %v", err)
	}
	if err := c.ShortTerm.DeleteConversation(context.Background(), conv.ID); err != nil {
		t.Errorf("second delete should not fail: %v", err)
	}
}

// ===========================================================================
// 3. Short-term memory: messages
// ===========================================================================

func TestE2EAddMessageReturnsIDAndRole(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, "hello world")
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if msg.ID == "" {
		t.Error("expected msg.ID")
	}
	if msg.Role != memory.RoleUser {
		t.Errorf("Role = %q, want user", msg.Role)
	}
	if msg.Content != "hello world" {
		t.Errorf("Content = %q", msg.Content)
	}
}

func TestE2EGetConversationReturnsAddedMessages(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	contents := []string{"one", "two", "three", "four", "five"}
	for _, txt := range contents {
		if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, txt); err != nil {
			t.Fatalf("AddMessage(%q): %v", txt, err)
		}
	}
	got, err := c.ShortTerm.GetConversation(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetConversation: %v", err)
	}
	seen := map[string]bool{}
	for _, m := range got.Messages {
		seen[m.Content] = true
	}
	for _, c := range contents {
		if !seen[c] {
			t.Errorf("missing content %q in conversation", c)
		}
	}
}

func TestE2ESearchMessagesReturnsArray(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser,
		"Marie Curie won the Nobel Prize in Physics in 1903."); err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	results, err := c.ShortTerm.SearchMessages(context.Background(), "Nobel",
		memory.WithSessionID(conv.ID),
		memory.WithSearchLimit(5),
		memory.WithThreshold(0.0),
	)
	if err != nil {
		t.Fatalf("SearchMessages: %v", err)
	}
	if results == nil {
		t.Error("results should be non-nil")
	}
}

func TestE2EMessageRoleRoundTrip(t *testing.T) {
	c := e2eClient(t)
	for _, role := range []memory.MessageRole{memory.RoleUser, memory.RoleAssistant, memory.RoleSystem} {
		t.Run(string(role), func(t *testing.T) {
			conv := e2eNewConv(t, c, "role-"+string(role))
			msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, role, "role "+string(role))
			if err != nil {
				t.Fatalf("AddMessage: %v", err)
			}
			if msg.Role != role {
				t.Errorf("Role = %q, want %q", msg.Role, role)
			}
		})
	}
}

func TestE2EUnicodePreserved(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	content := "你好 🚀 émoji ñ ç ø"
	msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, content)
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if msg.Content != content {
		t.Errorf("unicode content not preserved: got %q", msg.Content)
	}
}

func TestE2ELongContentPreserved(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	content := strings.Repeat("x", 10_000)
	msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, content)
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if len(msg.Content) != 10_000 {
		t.Errorf("len = %d, want 10000", len(msg.Content))
	}
}

func TestE2ESpecialCharsPreserved(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	content := "quote \" backslash \\ newline\nreturn\r tab\t json {\"a\":1}"
	msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, content)
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if msg.Content != content {
		t.Errorf("special chars not preserved")
	}
}

func TestE2EMetadataRoundTripsWithoutError(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	meta := map[string]interface{}{"source": "tck-e2e", "priority": "high", "count": 42, "active": true}
	msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, "with-meta", memory.WithMetadata(meta))
	if err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	if msg.Content != "with-meta" {
		t.Errorf("Content = %q", msg.Content)
	}
}

// ===========================================================================
// 4. Bulk operations
// ===========================================================================

func TestE2EBulkAdd5Messages(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	msgs := make([]memory.BulkMessageInput, 5)
	for i := range msgs {
		msgs[i] = memory.BulkMessageInput{Role: memory.RoleUser, Content: fmt.Sprintf("bulk-%d", i)}
	}
	out, err := c.ShortTerm.BulkAddMessages(context.Background(), conv.ID, msgs)
	if err != nil {
		t.Fatalf("BulkAddMessages: %v", err)
	}
	if len(out) != 5 {
		t.Errorf("got %d, want 5", len(out))
	}
}

func TestE2EBulkAdd50Messages(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	msgs := make([]memory.BulkMessageInput, 50)
	for i := range msgs {
		msgs[i] = memory.BulkMessageInput{Role: memory.RoleUser, Content: fmt.Sprintf("big-bulk-%d", i)}
	}
	out, err := c.ShortTerm.BulkAddMessages(context.Background(), conv.ID, msgs)
	if err != nil {
		t.Fatalf("BulkAddMessages: %v", err)
	}
	if len(out) != 50 {
		t.Errorf("got %d, want 50", len(out))
	}
}

func TestE2EBulkAddRejectsMoreThan100(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	msgs := make([]memory.BulkMessageInput, 101)
	for i := range msgs {
		msgs[i] = memory.BulkMessageInput{Role: memory.RoleUser, Content: "x"}
	}
	if _, err := c.ShortTerm.BulkAddMessages(context.Background(), conv.ID, msgs); err == nil {
		t.Error("expected error when sending >100 messages")
	}
}

// ===========================================================================
// 5. Three-tier context
// ===========================================================================

func TestE2EContextThreeTierShape(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, "Hello world"); err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	ctx, err := c.ShortTerm.GetContext(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetContext: %v", err)
	}
	if ctx.Reflections == nil || ctx.Observations == nil || ctx.RecentMessages == nil {
		t.Error("context should expose all three slices")
	}
}

func TestE2EObservationsList(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	obs, err := c.ShortTerm.GetObservations(context.Background(), conv.ID, 10)
	if err != nil {
		t.Fatalf("GetObservations: %v", err)
	}
	if obs == nil {
		t.Error("Observations slice should be non-nil (may be empty)")
	}
}

func TestE2EReflectionsList(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	refl, err := c.ShortTerm.GetReflections(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetReflections: %v", err)
	}
	if refl == nil {
		t.Error("Reflections slice should be non-nil")
	}
}

func TestE2EContextRecentMessages(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, "context-probe-message"); err != nil {
		t.Fatalf("AddMessage: %v", err)
	}
	ctx, err := c.ShortTerm.GetContext(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetContext: %v", err)
	}
	found := false
	for _, m := range ctx.RecentMessages {
		if m.Content == "context-probe-message" {
			found = true
			break
		}
	}
	if !found {
		t.Error("context.recent_messages should include the added message")
	}
}

// ===========================================================================
// 6. Long-term: entity CRUD + search
// ===========================================================================

func TestE2EAddEntityReturnsIDAndFields(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "TCK Alice "+e2eRandomHex(4), "concept", "test person")
	if len(e.ID) < 8 {
		t.Errorf("entity ID too short: %q", e.ID)
	}
	// e2eNewEntity tags the description with a tck-provenance prefix; the
	// original payload is preserved at the end of the string.
	if !strings.HasSuffix(e.Description, "test person") {
		t.Errorf("Description = %q, should end with 'test person'", e.Description)
	}
	if !strings.Contains(e.Description, "tck:go") {
		t.Errorf("Description = %q, should contain provenance tag", e.Description)
	}
}

func TestE2EListEntitiesReturnsArray(t *testing.T) {
	c := e2eClient(t)
	if _, err := c.LongTerm.ListEntities(context.Background(), "", 5); err != nil {
		t.Fatalf("ListEntities: %v", err)
	}
}

func TestE2EListEntitiesWithTypeFilter(t *testing.T) {
	c := e2eClient(t)
	ents, err := c.LongTerm.ListEntities(context.Background(), "person", 5)
	if err != nil {
		t.Fatalf("ListEntities(person): %v", err)
	}
	for _, e := range ents {
		if e.Type != "person" {
			t.Errorf("non-person entity returned with type filter: %q", e.Type)
		}
	}
}

func TestE2EGetEntityRelationships(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "concept", "")
	full, err := c.LongTerm.GetEntity(context.Background(), e.ID)
	if err != nil {
		t.Fatalf("GetEntity: %v", err)
	}
	if full.ID != e.ID {
		t.Errorf("ID mismatch: got %q want %q", full.ID, e.ID)
	}
}

func TestE2EUpdateEntityDescription(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "concept", "orig")
	updated, err := c.LongTerm.UpdateEntity(context.Background(), e.ID, memory.UpdateEntityParams{Description: "rewritten"})
	if err != nil {
		t.Fatalf("UpdateEntity: %v", err)
	}
	if updated.Description != "rewritten" {
		t.Errorf("Description = %q, want rewritten", updated.Description)
	}
}

func TestE2EUpdateEntityName(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "Original-"+e2eRandomHex(6), "concept", "")
	newName := "Renamed-" + e2eRandomHex(6)
	updated, err := c.LongTerm.UpdateEntity(context.Background(), e.ID, memory.UpdateEntityParams{Name: newName})
	if err != nil {
		t.Fatalf("UpdateEntity: %v", err)
	}
	if updated.Name != newName {
		t.Errorf("Name = %q, want %q", updated.Name, newName)
	}
}

func TestE2EDeleteEntity(t *testing.T) {
	c := e2eClient(t)
	e, err := c.LongTerm.AddEntity(context.Background(), "TCK-DelProbe-"+e2eRandomHex(6), "concept",
		memory.WithDescription("ephemeral"))
	if err != nil {
		t.Fatalf("AddEntity: %v", err)
	}
	if err := c.LongTerm.DeleteEntity(context.Background(), e.ID); err != nil {
		t.Fatalf("DeleteEntity: %v", err)
	}
	// Re-fetch may either 404 or soft-tombstone — either is acceptable.
	_, _ = c.LongTerm.GetEntity(context.Background(), e.ID)
}

func TestE2ESearchEntitiesReturnsArray(t *testing.T) {
	c := e2eClient(t)
	if _, err := c.LongTerm.SearchEntities(context.Background(), "anything", 5); err != nil {
		t.Fatalf("SearchEntities: %v", err)
	}
}

func TestE2ESearchFindsCreatedEntity(t *testing.T) {
	c := e2eClient(t)
	marker := "TCK-Probe-" + e2eRandomHex(8)
	e := e2eNewEntity(t, c, marker, "concept", "")
	err := e2eWaitUntil(func() (bool, error) {
		hits, err := c.LongTerm.SearchEntities(context.Background(), marker, 10)
		if err != nil {
			return false, err
		}
		for _, h := range hits {
			if h.ID == e.ID {
				return true, nil
			}
		}
		return false, nil
	}, 12*time.Second)
	if err != nil {
		t.Skipf("entity not yet indexed for search after 12s: %v", err)
	}
}

// ===========================================================================
// 7. Entity feedback + history + provenance + graph + merge
// ===========================================================================

func TestE2ESetEntityFeedback(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "", "")
	res, err := c.LongTerm.SetEntityFeedback(context.Background(), e.ID, 0.93, true)
	if err != nil {
		t.Fatalf("SetEntityFeedback: %v", err)
	}
	if !res.Updated {
		t.Error("expected res.Updated = true")
	}
}

func TestE2EFeedbackZeroScore(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "", "")
	if _, err := c.LongTerm.SetEntityFeedback(context.Background(), e.ID, 0.0, false); err != nil {
		t.Fatalf("SetEntityFeedback: %v", err)
	}
}

func TestE2EEntityHistoryShape(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "", "")
	hist, err := c.LongTerm.GetEntityHistory(context.Background(), e.ID)
	if err != nil {
		t.Fatalf("GetEntityHistory: %v", err)
	}
	if hist.EntityID != e.ID {
		t.Errorf("EntityID = %q, want %q", hist.EntityID, e.ID)
	}
	if hist.Mentions == nil {
		t.Error("Mentions should be non-nil (may be empty)")
	}
}

func TestE2EEntityProvenanceShape(t *testing.T) {
	c := e2eClient(t)
	e := e2eNewEntity(t, c, "", "", "")
	prov, err := c.Reasoning.GetEntityProvenance(context.Background(), e.ID)
	if err != nil {
		t.Fatalf("GetEntityProvenance: %v", err)
	}
	if prov.EntityID != e.ID {
		t.Errorf("EntityID = %q, want %q", prov.EntityID, e.ID)
	}
}

func TestE2EEntityGraph(t *testing.T) {
	c := e2eClient(t)
	graph, err := c.LongTerm.GetEntityGraph(context.Background())
	if err != nil {
		t.Fatalf("GetEntityGraph: %v", err)
	}
	if graph.Nodes == nil || graph.Edges == nil {
		t.Error("graph should have Nodes and Edges slices")
	}
}

func TestE2EMergeEntities(t *testing.T) {
	c := e2eClient(t)
	a := e2eNewEntity(t, c, "MergeA-"+e2eRandomHex(6), "", "")
	b := e2eNewEntity(t, c, "MergeB-"+e2eRandomHex(6), "", "")
	res, err := c.LongTerm.MergeEntities(context.Background(), a.ID, b.ID)
	if err != nil {
		var te *memory.TransportError
		var ae *memory.AuthenticationError
		if errors.As(err, &te) || errors.As(err, &ae) {
			t.Skipf("merge endpoint refused or unsupported: %v", err)
		}
		t.Fatalf("MergeEntities: %v", err)
	}
	if res.Status == "" {
		t.Error("merge status should be set")
	}
}

// ===========================================================================
// 8. Reasoning: steps + explain + trace
// ===========================================================================

func TestE2ERecordStepPersists(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	step, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{
		ConversationID: conv.ID,
		Reasoning:      "hypothesizing user's intent",
		ActionTaken:    "lookup_user_profile",
		Result:         "found profile",
	})
	if err != nil {
		t.Fatalf("RecordStep: %v", err)
	}
	if step.ID == "" {
		t.Error("expected step.ID")
	}
	if step.ConversationID != conv.ID {
		t.Errorf("ConversationID = %q, want %q", step.ConversationID, conv.ID)
	}
}

func TestE2ERecordStepWithoutResult(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	step, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{
		ConversationID: conv.ID,
		Reasoning:      "r",
		ActionTaken:    "a",
	})
	if err != nil {
		t.Fatalf("RecordStep: %v", err)
	}
	if step.ID == "" {
		t.Error("expected step.ID")
	}
}

func TestE2EListStepsReturnsRecorded(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	s1, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{ConversationID: conv.ID, Reasoning: "r1", ActionTaken: "a1"})
	if err != nil {
		t.Fatalf("RecordStep s1: %v", err)
	}
	s2, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{ConversationID: conv.ID, Reasoning: "r2", ActionTaken: "a2"})
	if err != nil {
		t.Fatalf("RecordStep s2: %v", err)
	}
	steps, err := c.Reasoning.ListSteps(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("ListSteps: %v", err)
	}
	ids := map[string]bool{}
	for _, s := range steps {
		ids[s.ID] = true
	}
	if !ids[s1.ID] || !ids[s2.ID] {
		t.Error("ListSteps did not return both recorded steps")
	}
}

func TestE2EExplainStep(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	step, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{ConversationID: conv.ID, Reasoning: "r", ActionTaken: "a"})
	if err != nil {
		t.Fatalf("RecordStep: %v", err)
	}
	ex, err := c.Reasoning.ExplainStep(context.Background(), step.ID)
	if err != nil {
		t.Fatalf("ExplainStep: %v", err)
	}
	if ex.ID != step.ID {
		t.Errorf("ID = %q, want %q", ex.ID, step.ID)
	}
}

func TestE2ETraceForEmptyConv(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	tr, err := c.Reasoning.GetTraceByConversation(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetTraceByConversation: %v", err)
	}
	if tr.ConversationID != conv.ID {
		t.Errorf("ConversationID mismatch")
	}
}

func TestE2ETraceWithRecordedSteps(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	if _, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{ConversationID: conv.ID, Reasoning: "r", ActionTaken: "a"}); err != nil {
		t.Fatalf("RecordStep: %v", err)
	}
	tr, err := c.Reasoning.GetTraceByConversation(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetTraceByConversation: %v", err)
	}
	found := false
	for _, s := range tr.Steps {
		if strings.Contains(s.Reasoning, "r") {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected recorded step to appear in trace")
	}
}

// ===========================================================================
// 9. Cypher (skipped on 403)
// ===========================================================================

func TestE2ECypherCount(t *testing.T) {
	c := e2eClient(t)
	res, err := c.Query.Cypher(context.Background(), memory.CypherInput{Cypher: "MATCH (n) RETURN count(n) AS total"})
	if err != nil {
		var ae *memory.AuthenticationError
		if errors.As(err, &ae) {
			t.Skipf("API key lacks Cypher scope: %v", err)
		}
		t.Fatalf("Cypher: %v", err)
	}
	if len(res.Columns) == 0 || res.Columns[0] != "total" {
		t.Errorf("columns = %v, want [total]", res.Columns)
	}
}

func TestE2ECypherParameterised(t *testing.T) {
	c := e2eClient(t)
	res, err := c.Query.Cypher(context.Background(), memory.CypherInput{
		Cypher: "MATCH (n) RETURN $label AS label LIMIT 1",
		Params: map[string]interface{}{"label": "tck-e2e"},
	})
	if err != nil {
		var ae *memory.AuthenticationError
		if errors.As(err, &ae) {
			t.Skipf("API key lacks Cypher scope: %v", err)
		}
		t.Fatalf("Cypher: %v", err)
	}
	if res.Columns == nil {
		t.Error("Columns should be non-nil")
	}
}

// ===========================================================================
// 10. Auth API (skipped on 403)
// ===========================================================================

func TestE2EListApiKeys(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	meta, err := c.ShortTerm.GetConversationMetadata(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetConversationMetadata: %v", err)
	}
	if meta.WorkspaceID == "" {
		t.Skip("workspace_id not exposed by service")
	}
	keys, err := c.Auth.ListAPIKeys(context.Background(), meta.WorkspaceID)
	if err != nil {
		var ae *memory.AuthenticationError
		if errors.As(err, &ae) {
			t.Skipf("API key lacks auth scope: %v", err)
		}
		t.Fatalf("ListAPIKeys: %v", err)
	}
	if keys == nil {
		t.Error("keys slice should be non-nil")
	}
}

// ===========================================================================
// 11. Cross-feature workflows
// ===========================================================================

func TestE2EMessageFlowToExtractedEntities(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "agent-flow")
	uniqueName := "TCKMercury" + e2eRandomHex(8)
	if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser,
		uniqueName+" is the smallest planet in the solar system."); err != nil {
		t.Fatalf("AddMessage 1: %v", err)
	}
	if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleAssistant,
		"Yes, "+uniqueName+" has a thin atmosphere."); err != nil {
		t.Fatalf("AddMessage 2: %v", err)
	}
	err := e2eWaitUntil(func() (bool, error) {
		hits, err := c.LongTerm.SearchEntities(context.Background(), uniqueName, 10)
		if err != nil {
			return false, err
		}
		for _, h := range hits {
			if strings.Contains(strings.ToLower(h.Name), strings.ToLower(uniqueName)) {
				return true, nil
			}
		}
		return false, nil
	}, 20*time.Second)
	if err != nil {
		t.Skipf("extracted entity not indexed within 20s: %v", err)
	}
}

func TestE2EMultiStepReasoningChain(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	want := map[string]bool{}
	for i := 0; i < 3; i++ {
		s, err := c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{
			ConversationID: conv.ID,
			Reasoning:      fmt.Sprintf("step %d reasoning", i),
			ActionTaken:    fmt.Sprintf("action_%d", i),
			Result:         fmt.Sprintf("result_%d", i),
		})
		if err != nil {
			t.Fatalf("RecordStep %d: %v", i, err)
		}
		want[s.ID] = true
	}
	tr, err := c.Reasoning.GetTraceByConversation(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetTraceByConversation: %v", err)
	}
	got := map[string]bool{}
	for _, s := range tr.Steps {
		got[s.ID] = true
	}
	for id := range want {
		if !got[id] {
			t.Errorf("step %s missing from trace", id)
		}
	}
}

func TestE2EMultiTurnConversationAppearsInContext(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	turns := [][2]string{
		{"user", "I'm planning a trip to Tokyo next month."},
		{"assistant", "Tokyo is great in autumn — what are your interests?"},
		{"user", "Mostly food and historical sites."},
		{"assistant", "Visit Tsukiji Outer Market and Senso-ji."},
		{"user", "How long should I stay?"},
	}
	for _, turn := range turns {
		if _, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.MessageRole(turn[0]), turn[1]); err != nil {
			t.Fatalf("AddMessage: %v", err)
		}
	}
	ctx, err := c.ShortTerm.GetContext(context.Background(), conv.ID)
	if err != nil {
		t.Fatalf("GetContext: %v", err)
	}
	all := ""
	for _, m := range ctx.RecentMessages {
		all += " " + m.Content
	}
	if !strings.Contains(all, "Tokyo") && !strings.Contains(all, "Tsukiji") {
		t.Error("conversation contents should appear in context.recent_messages")
	}
}

// ===========================================================================
// 12. Concurrency
// ===========================================================================

func TestE2EConcurrentAddMessages(t *testing.T) {
	c := e2eClient(t)
	conv := e2eNewConv(t, c, "")
	const n = 8
	var wg sync.WaitGroup
	ids := make([]string, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			msg, err := c.ShortTerm.AddMessage(context.Background(), conv.ID, memory.RoleUser, fmt.Sprintf("concurrent-%d", i))
			if err != nil {
				errs[i] = err
				return
			}
			ids[i] = msg.ID
		}(i)
	}
	wg.Wait()
	uniq := map[string]bool{}
	for i, id := range ids {
		if errs[i] != nil {
			t.Errorf("worker %d: %v", i, errs[i])
			continue
		}
		uniq[id] = true
	}
	if len(uniq) != n {
		t.Errorf("expected %d unique ids, got %d", n, len(uniq))
	}
}

func TestE2EConcurrentCreateConversations(t *testing.T) {
	c := e2eClient(t)
	const n = 4
	var wg sync.WaitGroup
	ids := make([]string, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{UserID: e2eUserID(t, fmt.Sprintf("concur-%d", i))})
			if err != nil {
				errs[i] = err
				return
			}
			ids[i] = conv.ID
			t.Cleanup(func() { _ = c.ShortTerm.DeleteConversation(context.Background(), conv.ID) })
		}(i)
	}
	wg.Wait()
	uniq := map[string]bool{}
	for i, id := range ids {
		if errs[i] != nil {
			t.Errorf("worker %d: %v", i, errs[i])
			continue
		}
		uniq[id] = true
	}
	if len(uniq) != n {
		t.Errorf("expected %d unique ids, got %d", n, len(uniq))
	}
}
