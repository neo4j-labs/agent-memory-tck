package memory_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

// Integration tests — run RestTransport against a local httptest.Server stub.
// No external network calls; verify request/response wiring + camel↔snake
// translation + path / query / body assembly.

type recordingHandler struct {
	t        *testing.T
	method   string
	path     string
	headers  http.Header
	rawQuery string
	body     []byte
	status   int
	respJSON string
}

func (h *recordingHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.method = r.Method
	h.path = r.URL.Path
	h.headers = r.Header.Clone()
	h.rawQuery = r.URL.RawQuery
	defer r.Body.Close()
	buf := make([]byte, 8192)
	n, _ := r.Body.Read(buf)
	h.body = append([]byte(nil), buf[:n]...)
	if h.respJSON == "" {
		w.WriteHeader(h.status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(h.status)
	_, _ = w.Write([]byte(h.respJSON))
}

func newRestClient(t *testing.T, h http.Handler) (*memory.Client, *httptest.Server) {
	t.Helper()
	srv := httptest.NewServer(h)
	c, err := memory.New(
		memory.WithEndpoint(srv.URL+"/v1"),
		memory.WithAPIKey("nams_test_key"),
	)
	if err != nil {
		srv.Close()
		t.Fatalf("memory.New: %v", err)
	}
	return c, srv
}

func TestRestCreateConversation(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"id":"conv-1","userId":"alice","workspaceId":"ws","createdAt":"2026-05-07T00:00:00Z"}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	conv, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{
		UserID:   "alice",
		Metadata: map[string]interface{}{"source": "test"},
	})
	if err != nil {
		t.Fatalf("CreateConversation: %v", err)
	}

	if h.method != "POST" {
		t.Errorf("method = %s, want POST", h.method)
	}
	if !strings.HasSuffix(h.path, "/conversations") {
		t.Errorf("path = %s, want .../conversations", h.path)
	}
	if got := h.headers.Get("Authorization"); got != "Bearer nams_test_key" {
		t.Errorf("Authorization header = %q, want Bearer nams_test_key", got)
	}
	if !strings.Contains(string(h.body), `"userId":"alice"`) {
		t.Errorf("body did not contain camelCase userId; got %s", string(h.body))
	}
	if conv.ID != "conv-1" {
		t.Errorf("conv.ID = %q, want conv-1", conv.ID)
	}
}

func TestRestGetContextSubstitutesPath(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"reflections":[{"id":"r","conversationId":"conv-42","content":"x","createdAt":"2026-05-07T00:00:00Z"}],"observations":[],"recentMessages":[]}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	ctx, err := c.ShortTerm.GetContext(context.Background(), "conv-42")
	if err != nil {
		t.Fatalf("GetContext: %v", err)
	}
	if !strings.HasSuffix(h.path, "/conversations/conv-42/context") {
		t.Errorf("path = %s, want .../conversations/conv-42/context", h.path)
	}
	if h.method != "GET" {
		t.Errorf("method = %s, want GET", h.method)
	}
	if len(ctx.Reflections) != 1 {
		t.Errorf("Reflections len = %d, want 1", len(ctx.Reflections))
	}
}

func TestRestListConversationsUnwrapsEnvelope(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"conversations":[{"id":"c1","userId":"a","createdAt":"2026-05-07T00:00:00Z"},{"id":"c2","userId":"b","createdAt":"2026-05-07T00:00:00Z"}]}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	convs, err := c.ShortTerm.ListConversations(context.Background(), 10)
	if err != nil {
		t.Fatalf("ListConversations: %v", err)
	}
	if len(convs) != 2 {
		t.Errorf("got %d conversations, want 2", len(convs))
	}
	if convs[0].ID != "c1" {
		t.Errorf("convs[0].ID = %q, want c1", convs[0].ID)
	}
	// Query string should include the limit
	if !strings.Contains(h.rawQuery, "limit=10") {
		t.Errorf("query did not include limit=10; got %q", h.rawQuery)
	}
}

func TestRestSetEntityFeedback(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"id":"e1","updated":true}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	res, err := c.LongTerm.SetEntityFeedback(context.Background(), "e1", 0.95, true)
	if err != nil {
		t.Fatalf("SetEntityFeedback: %v", err)
	}
	if h.method != "PUT" {
		t.Errorf("method = %s, want PUT", h.method)
	}
	if !strings.HasSuffix(h.path, "/entities/e1/feedback") {
		t.Errorf("path = %s, want .../entities/e1/feedback", h.path)
	}
	body := string(h.body)
	if !strings.Contains(body, `"userScore":0.95`) {
		t.Errorf("body should contain userScore; got %s", body)
	}
	if !strings.Contains(body, `"confirmed":true`) {
		t.Errorf("body should contain confirmed=true; got %s", body)
	}
	if !res.Updated {
		t.Error("expected res.Updated = true")
	}
}

func TestRestCypherQuery(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"columns":["name"],"rows":[["Alice"]],"stats":{"queryTime":3}}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	res, err := c.Query.Cypher(context.Background(), memory.CypherInput{
		Cypher: "MATCH (n) RETURN count(n) AS name",
		Params: map[string]interface{}{"n": 1},
	})
	if err != nil {
		t.Fatalf("Cypher: %v", err)
	}
	if !strings.HasSuffix(h.path, "/query") {
		t.Errorf("path = %s, want /query suffix", h.path)
	}
	if len(res.Columns) != 1 || res.Columns[0] != "name" {
		t.Errorf("columns = %v, want [name]", res.Columns)
	}
}

func TestRest401AuthenticationError(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 401, respJSON: `{"error":"bad token"}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	_, err := c.ShortTerm.ListConversations(context.Background(), 0)
	if err == nil {
		t.Fatal("expected error")
	}
	var ae *memory.AuthenticationError
	if !errors.As(err, &ae) {
		t.Errorf("expected AuthenticationError, got %T: %v", err, err)
	}
}

func TestRest500TransportError(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 500, respJSON: `{"error":"boom"}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	_, err := c.ShortTerm.CreateConversation(context.Background(), memory.CreateConversationParams{UserID: "x"})
	if err == nil {
		t.Fatal("expected error")
	}
	var te *memory.TransportError
	if !errors.As(err, &te) {
		t.Errorf("expected TransportError, got %T: %v", err, err)
	}
	if te != nil && te.StatusCode != 500 {
		t.Errorf("status = %d, want 500", te.StatusCode)
	}
}

func TestRestLegacyMethodNotSupported(t *testing.T) {
	c, err := memory.New(
		memory.WithEndpoint("https://example.com/v1"),
		memory.WithAPIKey("k"),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	_, err = c.LongTerm.AddPreference(context.Background(), "style", "concise")
	if err == nil {
		t.Fatal("expected NotSupportedError")
	}
	var nse *memory.NotSupportedError
	if !errors.As(err, &nse) {
		t.Errorf("expected NotSupportedError, got %T", err)
	}
}

func TestRestTokenProvider(t *testing.T) {
	calls := 0
	provider := func(ctx context.Context) (string, error) {
		calls++
		return fmtToken(calls), nil
	}

	var observed []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		observed = append(observed, r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"conversations":[]}`))
	}))
	defer srv.Close()

	c, err := memory.New(
		memory.WithEndpoint(srv.URL+"/v1"),
		memory.WithTokenProvider(provider),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	for i := 0; i < 2; i++ {
		if _, err := c.ShortTerm.ListConversations(context.Background(), 0); err != nil {
			t.Fatalf("ListConversations: %v", err)
		}
	}

	if calls != 2 {
		t.Errorf("expected 2 token-provider calls, got %d", calls)
	}
	if observed[0] != "Bearer token-1" || observed[1] != "Bearer token-2" {
		t.Errorf("unexpected auth headers: %v", observed)
	}
}

func fmtToken(n int) string {
	res, _ := json.Marshal(map[string]int{})
	_ = res
	return "token-" + intToStr(n)
}

func intToStr(n int) string {
	switch n {
	case 1:
		return "1"
	case 2:
		return "2"
	default:
		return "0"
	}
}
