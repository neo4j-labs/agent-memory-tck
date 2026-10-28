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
		respJSON: `{"columns":["name"],"rows":[{"name":"Alice"}],"stats":{"queryTime":3}}`,
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
	if len(res.Rows) != 1 || res.Rows[0]["name"] != "Alice" {
		t.Errorf("rows = %v, want [{name: Alice}]", res.Rows)
	}
}

func TestRestGetConversationInsertionOrder(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"messages":[{"id":"m2","role":"assistant","content":"second"},{"id":"m1","role":"user","content":"first"}]}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	conv, err := c.ShortTerm.GetConversation(context.Background(), "conv-1")
	if err != nil {
		t.Fatalf("GetConversation: %v", err)
	}
	if len(conv.Messages) != 2 || conv.Messages[0].Content != "first" || conv.Messages[1].Content != "second" {
		t.Errorf("messages = %+v, want first then second", conv.Messages)
	}
}

func TestRestSearchMessagesOmitsUnsetThreshold(t *testing.T) {
	h := &recordingHandler{t: t, status: 200, respJSON: `{"messages":[]}`}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	_, err := c.ShortTerm.SearchMessages(context.Background(), "nobel", memory.WithSessionID("conv-1"))
	if err != nil {
		t.Fatalf("SearchMessages: %v", err)
	}
	if strings.Contains(string(h.body), "threshold") {
		t.Errorf("body = %s, want no threshold", h.body)
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

func TestRestAddRelationship(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 201,
		respJSON: `{"id":"11111111-2222-3333-4444-555555555555","sourceId":"e-src","targetId":"e-tgt","relationshipType":"WORKS_AT","confidence":1,"created":true}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	rel, err := c.LongTerm.AddRelationship(context.Background(), "e-src", "e-tgt", "WORKS_AT")
	if err != nil {
		t.Fatalf("AddRelationship: %v", err)
	}

	if h.method != "POST" || !strings.HasSuffix(h.path, "/relationships") {
		t.Errorf("request = %s %s, want POST .../relationships", h.method, h.path)
	}
	body := string(h.body)
	for _, want := range []string{`"sourceId":"e-src"`, `"targetId":"e-tgt"`, `"relationshipType":"WORKS_AT"`} {
		if !strings.Contains(body, want) {
			t.Errorf("body missing %s; got %s", want, body)
		}
	}
	if rel.ID != "11111111-2222-3333-4444-555555555555" {
		t.Errorf("rel.ID = %q, want the response UUID", rel.ID)
	}
	if rel.SourceID != "e-src" || rel.TargetID != "e-tgt" || rel.RelationshipType != "WORKS_AT" {
		t.Errorf("rel = %+v, want source/target/type round-trip", rel)
	}
}

func TestRestAddRelationshipCollapsedType(t *testing.T) {
	// A type outside the workspace allowlist comes back as RELATED_TO with the
	// original preserved as "predicate" — the client surfaces the written type.
	h := &recordingHandler{
		t: t, status: 201,
		respJSON: `{"id":"11111111-2222-3333-4444-555555555555","sourceId":"a","targetId":"b","relationshipType":"RELATED_TO","predicate":"located_at","confidence":1,"created":true}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	rel, err := c.LongTerm.AddRelationship(context.Background(), "a", "b", "LOCATED_AT")
	if err != nil {
		t.Fatalf("AddRelationship: %v", err)
	}
	if rel.RelationshipType != "RELATED_TO" {
		t.Errorf("rel.RelationshipType = %q, want RELATED_TO (collapsed)", rel.RelationshipType)
	}
}

func TestRestGetEntityByNameFound(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"entities":[{"id":"e1","name":"Alice Johnson","type":"person","matchKind":"name","createdAt":"2026-05-07T00:00:00Z"},{"id":"e2","name":"Alice Johnson","type":"organization","matchKind":"alias"}]}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	entity, err := c.LongTerm.GetEntityByName(context.Background(), "Alice Johnson")
	if err != nil {
		t.Fatalf("GetEntityByName: %v", err)
	}
	if h.method != "GET" || !strings.HasSuffix(h.path, "/entities/by-name") {
		t.Errorf("request = %s %s, want GET .../entities/by-name", h.method, h.path)
	}
	if !strings.Contains(h.rawQuery, "name=Alice+Johnson") {
		t.Errorf("query = %q, want name=Alice+Johnson", h.rawQuery)
	}
	if entity == nil {
		t.Fatal("entity = nil, want the first (best) match")
	}
	if entity.ID != "e1" || entity.Name != "Alice Johnson" {
		t.Errorf("entity = %+v, want first entity e1", entity)
	}
}

func TestRestGetEntityByNameNotFound(t *testing.T) {
	h := &recordingHandler{t: t, status: 200, respJSON: `{"entities":[]}`}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	entity, err := c.LongTerm.GetEntityByName(context.Background(), "Nonexistent Entity")
	if err != nil {
		t.Fatalf("GetEntityByName: %v", err)
	}
	if entity != nil {
		t.Errorf("entity = %+v, want nil for empty match list", entity)
	}
}

const relatedEntityJSON = `{
	"id": "e-alice", "name": "Alice", "type": "person",
	"relationships": [
		{"relType":"KNOWS","targetId":"e-bob","targetName":"Bob","targetType":"person"},
		{"relType":"WORKS_AT","targetId":"e-acme","targetName":"Acme","targetType":"organization"}
	]
}`

func TestRestGetRelatedEntities(t *testing.T) {
	h := &recordingHandler{t: t, status: 200, respJSON: relatedEntityJSON}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	related, err := c.LongTerm.GetRelatedEntities(context.Background(), "e-alice")
	if err != nil {
		t.Fatalf("GetRelatedEntities: %v", err)
	}
	if h.method != "GET" || !strings.HasSuffix(h.path, "/entities/e-alice") {
		t.Errorf("request = %s %s, want GET .../entities/e-alice", h.method, h.path)
	}
	if len(related) != 2 {
		t.Fatalf("len(related) = %d, want 2", len(related))
	}
	names := map[string]string{}
	for _, e := range related {
		names[e.Name] = e.Type
	}
	if names["Bob"] != "person" || names["Acme"] != "organization" {
		t.Errorf("related = %v, want Bob/person and Acme/organization", names)
	}
	if related[0].ID != "e-bob" {
		t.Errorf("related[0].ID = %q, want e-bob", related[0].ID)
	}
}

func TestRestGetRelatedEntitiesTypeFilter(t *testing.T) {
	h := &recordingHandler{t: t, status: 200, respJSON: relatedEntityJSON}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	related, err := c.LongTerm.GetRelatedEntities(context.Background(), "e-alice",
		memory.WithRelType("WORKS_AT"))
	if err != nil {
		t.Fatalf("GetRelatedEntities: %v", err)
	}
	if len(related) != 1 || related[0].Name != "Acme" {
		t.Errorf("related = %+v, want just Acme (WORKS_AT filter)", related)
	}
}

func TestRestGetRelatedEntitiesEmpty(t *testing.T) {
	h := &recordingHandler{
		t: t, status: 200,
		respJSON: `{"id":"e-lonely","name":"Lonely","type":"person","relationships":[]}`,
	}
	c, srv := newRestClient(t, h)
	defer srv.Close()

	related, err := c.LongTerm.GetRelatedEntities(context.Background(), "e-lonely")
	if err != nil {
		t.Fatalf("GetRelatedEntities: %v", err)
	}
	if len(related) != 0 {
		t.Errorf("related = %+v, want empty slice", related)
	}
}

func TestRestGetRelatedEntitiesDepthNotSupportedPublicAPI(t *testing.T) {
	c, err := memory.New(
		memory.WithEndpoint("https://example.com/v1"),
		memory.WithAPIKey("k"),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	_, err = c.LongTerm.GetRelatedEntities(context.Background(), "e1", memory.WithDepth(2))
	if err == nil {
		t.Fatal("expected NotSupportedError for depth > 1")
	}
	var nse *memory.NotSupportedError
	if !errors.As(err, &nse) {
		t.Errorf("expected NotSupportedError, got %T: %v", err, err)
	}
}

// mergeMux serves the three endpoints the merge_duplicate_entities composite
// chains: POST /v1/entities/{src}/merge, PUT /v1/entities/{tgt}, and
// GET /v1/entities/{tgt}. (Plain method/path switch — the module's go 1.21
// directive disables ServeMux method patterns.)
func mergeMux(t *testing.T, calls *[]string, entityName string) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		key := r.Method + " " + r.URL.Path
		switch key {
		case "POST /v1/entities/e-src/merge":
			*calls = append(*calls, "merge")
			_, _ = w.Write([]byte(`{"status":"merged","sourceId":"e-src","targetId":"e-canonical"}`))
		case "PUT /v1/entities/e-canonical":
			*calls = append(*calls, "rename")
			_, _ = w.Write([]byte(`{"status":"updated"}`))
		case "GET /v1/entities/e-canonical":
			*calls = append(*calls, "get")
			_, _ = w.Write([]byte(`{"id":"e-canonical","name":"` + entityName + `","type":"person"}`))
		default:
			t.Errorf("unexpected request: %s", key)
			w.WriteHeader(404)
		}
	})
}

func TestRestMergeDuplicateEntities(t *testing.T) {
	var calls []string
	c, srv := newRestClient(t, mergeMux(t, &calls, "Alice Johnson"))
	defer srv.Close()

	// Note the merge response's targetId ("e-canonical") wins over the
	// caller-supplied one — it is canonical-resolved server-side.
	merged, err := c.LongTerm.MergeDuplicateEntities(context.Background(), "e-src", "e-tgt")
	if err != nil {
		t.Fatalf("MergeDuplicateEntities: %v", err)
	}
	if merged.ID != "e-canonical" || merged.Name != "Alice Johnson" {
		t.Errorf("merged = %+v, want the surviving entity e-canonical", merged)
	}
	want := []string{"merge", "get"}
	if len(calls) != len(want) || calls[0] != want[0] || calls[1] != want[1] {
		t.Errorf("calls = %v, want %v", calls, want)
	}
}

func TestRestMergeDuplicateEntitiesCanonicalName(t *testing.T) {
	var calls []string
	c, srv := newRestClient(t, mergeMux(t, &calls, "Alice J. Johnson"))
	defer srv.Close()

	merged, err := c.LongTerm.MergeDuplicateEntities(context.Background(), "e-src", "e-tgt",
		memory.WithCanonicalName("Alice J. Johnson"))
	if err != nil {
		t.Fatalf("MergeDuplicateEntities: %v", err)
	}
	if merged.Name != "Alice J. Johnson" {
		t.Errorf("merged.Name = %q, want canonical name", merged.Name)
	}
	want := []string{"merge", "rename", "get"}
	if len(calls) != 3 || calls[0] != want[0] || calls[1] != want[1] || calls[2] != want[2] {
		t.Errorf("calls = %v, want %v", calls, want)
	}
}
