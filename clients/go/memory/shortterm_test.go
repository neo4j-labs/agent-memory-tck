//go:build bridge

package memory_test

import (
	"context"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

const (
	sessionA = "tck-session-alpha"
	sessionB = "tck-session-beta"
	sessionC = "tck-session-gamma"
)

func newTestClient(t *testing.T) *memory.Client {
	t.Helper()
	endpoint := os.Getenv("MEMORY_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:3001"
	}
	c, err := memory.New(memory.WithEndpoint(endpoint))
	if err != nil {
		t.Fatalf("creating client: %v", err)
	}
	return c
}

func cleanSessions(t *testing.T, c *memory.Client) {
	t.Helper()
	ctx := context.Background()
	_ = c.ShortTerm.ClearSession(ctx, sessionA)
	_ = c.ShortTerm.ClearSession(ctx, sessionB)
	_ = c.ShortTerm.ClearSession(ctx, sessionC)
}

// ---------------------------------------------------------------------------
// AddMessage
// ---------------------------------------------------------------------------

func TestAddMessage(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.1.1 returns valid message", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Hello, world!")
		mustNoErr(t, err)
		assertNonEmpty(t, "id", msg.ID)
		assertEqual(t, "role", string(msg.Role), "user")
		assertEqual(t, "content", msg.Content, "Hello, world!")
	})

	t.Run("SPEC-2.1.2 user role", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "User message")
		mustNoErr(t, err)
		assertEqual(t, "role", string(msg.Role), "user")
	})

	t.Run("SPEC-2.1.3 assistant role", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleAssistant, "Assistant message")
		mustNoErr(t, err)
		assertEqual(t, "role", string(msg.Role), "assistant")
	})

	t.Run("SPEC-2.1.4 system role", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleSystem, "System prompt")
		mustNoErr(t, err)
		assertEqual(t, "role", string(msg.Role), "system")
	})

	t.Run("SPEC-2.1.5 preserves metadata", func(t *testing.T) {
		cleanSessions(t, c)
		meta := map[string]interface{}{"source": "test", "priority": "high"}
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "With meta", memory.WithMetadata(meta))
		mustNoErr(t, err)
		assertEqual(t, "source", fmt.Sprint(msg.Metadata["source"]), "test")
		assertEqual(t, "priority", fmt.Sprint(msg.Metadata["priority"]), "high")
	})

	t.Run("SPEC-2.1.6 creates conversation on first call", func(t *testing.T) {
		cleanSessions(t, c)
		_, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "First message")
		mustNoErr(t, err)
		conv, err := c.ShortTerm.GetConversation(ctx, sessionA)
		mustNoErr(t, err)
		assertEqual(t, "session_id", conv.SessionID, sessionA)
		assertLen(t, "messages", len(conv.Messages), 1)
	})

	t.Run("SPEC-2.1.7 empty content", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "")
		mustNoErr(t, err)
		assertEqual(t, "content", msg.Content, "")
	})

	t.Run("SPEC-2.1.8 long content 10K chars", func(t *testing.T) {
		cleanSessions(t, c)
		long := strings.Repeat("x", 10000)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, long)
		mustNoErr(t, err)
		assertLen(t, "content length", len(msg.Content), 10000)
	})

	t.Run("SPEC-2.1.9 unicode content", func(t *testing.T) {
		cleanSessions(t, c)
		unicode := "Hello 世界 🌍 éèê üöä ☃️ ❤️‍🔥"
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, unicode)
		mustNoErr(t, err)
		assertEqual(t, "content", msg.Content, unicode)
	})

	t.Run("SPEC-2.1.10 special characters", func(t *testing.T) {
		cleanSessions(t, c)
		special := "Line1\nLine2\tTabbed \"quoted\" 'single' back\\slash"
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, special)
		mustNoErr(t, err)
		assertEqual(t, "content", msg.Content, special)
	})

	t.Run("SPEC-2.1.16 50 rapid messages ordered", func(t *testing.T) {
		cleanSessions(t, c)
		for i := 0; i < 50; i++ {
			_, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, fmt.Sprintf("Rapid message %d", i))
			mustNoErr(t, err)
		}
		conv, err := c.ShortTerm.GetConversation(ctx, sessionA)
		mustNoErr(t, err)
		assertLen(t, "messages", len(conv.Messages), 50)
		for i := 0; i < 50; i++ {
			assertEqual(t, fmt.Sprintf("msg[%d]", i), conv.Messages[i].Content, fmt.Sprintf("Rapid message %d", i))
		}
	})
}

// ---------------------------------------------------------------------------
// GetConversation
// ---------------------------------------------------------------------------

func TestGetConversation(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.2.1 insertion order", func(t *testing.T) {
		cleanSessions(t, c)
		contents := []string{"Hello", "How are you?", "Fine thanks", "Goodbye", "See you"}
		for i, content := range contents {
			roles := []memory.MessageRole{memory.RoleUser, memory.RoleAssistant}
			_, err := c.ShortTerm.AddMessage(ctx, sessionA, roles[i%2], content)
			mustNoErr(t, err)
		}
		conv, err := c.ShortTerm.GetConversation(ctx, sessionA)
		mustNoErr(t, err)
		assertLen(t, "messages", len(conv.Messages), 5)
		for i, content := range contents {
			assertEqual(t, fmt.Sprintf("msg[%d]", i), conv.Messages[i].Content, content)
		}
	})

	t.Run("SPEC-2.2.2 respects limit", func(t *testing.T) {
		cleanSessions(t, c)
		for i := 0; i < 5; i++ {
			_, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, fmt.Sprintf("Msg %d", i))
			mustNoErr(t, err)
		}
		conv, err := c.ShortTerm.GetConversation(ctx, sessionA, memory.WithLimit(2))
		mustNoErr(t, err)
		assertLen(t, "messages", len(conv.Messages), 2)
	})

	t.Run("SPEC-2.2.3 empty session", func(t *testing.T) {
		cleanSessions(t, c)
		conv, err := c.ShortTerm.GetConversation(ctx, "tck-nonexistent-session")
		mustNoErr(t, err)
		assertLen(t, "messages", len(conv.Messages), 0)
	})

	t.Run("SPEC-2.2.4 session isolation", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha 1")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha 2")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta 1")

		convA, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		convB, _ := c.ShortTerm.GetConversation(ctx, sessionB)
		assertLen(t, "session A", len(convA.Messages), 2)
		assertLen(t, "session B", len(convB.Messages), 1)
	})

	t.Run("SPEC-2.2.5 limit exceeds count", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Only one")
		conv, err := c.ShortTerm.GetConversation(ctx, sessionA, memory.WithLimit(100))
		mustNoErr(t, err)
		assertLen(t, "messages", len(conv.Messages), 1)
	})

	t.Run("SPEC-2.2.9 preserves roles", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleSystem, "System")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "User")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleAssistant, "Assistant")

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertEqual(t, "role[0]", string(conv.Messages[0].Role), "system")
		assertEqual(t, "role[1]", string(conv.Messages[1].Role), "user")
		assertEqual(t, "role[2]", string(conv.Messages[2].Role), "assistant")
	})

	t.Run("SPEC-2.2.12 three sessions isolated", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta 1")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta 2")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionC, memory.RoleUser, "Gamma 1")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionC, memory.RoleUser, "Gamma 2")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionC, memory.RoleUser, "Gamma 3")

		convA, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		convB, _ := c.ShortTerm.GetConversation(ctx, sessionB)
		convC, _ := c.ShortTerm.GetConversation(ctx, sessionC)
		assertLen(t, "session A", len(convA.Messages), 1)
		assertLen(t, "session B", len(convB.Messages), 2)
		assertLen(t, "session C", len(convC.Messages), 3)
	})
}

// ---------------------------------------------------------------------------
// SearchMessages
// ---------------------------------------------------------------------------

func TestSearchMessages(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.3.1 finds relevant", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "I love programming in Python")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "The weather is sunny today")

		results, err := c.ShortTerm.SearchMessages(ctx, "Python programming", memory.WithSearchLimit(10), memory.WithThreshold(0.0))
		mustNoErr(t, err)
		if len(results) == 0 {
			t.Fatal("expected at least one result")
		}
	})

	t.Run("SPEC-2.3.3 respects limit", func(t *testing.T) {
		cleanSessions(t, c)
		for i := 0; i < 5; i++ {
			_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, fmt.Sprintf("Test message %d", i))
		}
		results, err := c.ShortTerm.SearchMessages(ctx, "Test message", memory.WithSearchLimit(2), memory.WithThreshold(0.0))
		mustNoErr(t, err)
		if len(results) > 2 {
			t.Fatalf("expected at most 2 results, got %d", len(results))
		}
	})

	t.Run("SPEC-2.3.6 empty database", func(t *testing.T) {
		cleanSessions(t, c)
		results, err := c.ShortTerm.SearchMessages(ctx, "anything", memory.WithSearchLimit(10), memory.WithThreshold(0.0))
		mustNoErr(t, err)
		assertLen(t, "results", len(results), 0)
	})
}

// ---------------------------------------------------------------------------
// ListSessions
// ---------------------------------------------------------------------------

func TestListSessions(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.4.1 returns all sessions", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta")

		sessions, err := c.ShortTerm.ListSessions(ctx)
		mustNoErr(t, err)
		ids := make(map[string]bool)
		for _, s := range sessions {
			ids[s.SessionID] = true
		}
		if !ids[sessionA] {
			t.Fatalf("missing session %s", sessionA)
		}
		if !ids[sessionB] {
			t.Fatalf("missing session %s", sessionB)
		}
	})

	t.Run("SPEC-2.4.2 message counts", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "One")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleAssistant, "Two")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Three")

		sessions, err := c.ShortTerm.ListSessions(ctx)
		mustNoErr(t, err)
		for _, s := range sessions {
			if s.SessionID == sessionA {
				assertLen(t, "message_count", s.MessageCount, 3)
				return
			}
		}
		t.Fatal("session A not found")
	})

	t.Run("SPEC-2.4.3 empty", func(t *testing.T) {
		cleanSessions(t, c)
		sessions, err := c.ShortTerm.ListSessions(ctx)
		mustNoErr(t, err)
		assertLen(t, "sessions", len(sessions), 0)
	})

	t.Run("SPEC-2.4.5 respects limit", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionC, memory.RoleUser, "Gamma")

		sessions, err := c.ShortTerm.ListSessions(ctx, memory.WithListLimit(2))
		mustNoErr(t, err)
		if len(sessions) > 2 {
			t.Fatalf("expected at most 2 sessions, got %d", len(sessions))
		}
	})
}

// ---------------------------------------------------------------------------
// DeleteMessage
// ---------------------------------------------------------------------------

func TestDeleteMessage(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.5.1 returns true", func(t *testing.T) {
		cleanSessions(t, c)
		msg, err := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Delete me")
		mustNoErr(t, err)
		deleted, err := c.ShortTerm.DeleteMessage(ctx, msg.ID)
		mustNoErr(t, err)
		if !deleted {
			t.Fatal("expected true")
		}
	})

	t.Run("SPEC-2.5.2 removes from conversation", func(t *testing.T) {
		cleanSessions(t, c)
		msg1, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Keep")
		msg2, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Delete")
		_, _ = c.ShortTerm.DeleteMessage(ctx, msg2.ID)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 1)
		assertEqual(t, "id", conv.Messages[0].ID, msg1.ID)
	})

	t.Run("SPEC-2.5.4 preserves order", func(t *testing.T) {
		cleanSessions(t, c)
		var msgs []*memory.Message
		for _, content := range []string{"First", "Second", "Third", "Fourth"} {
			msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, content)
			msgs = append(msgs, msg)
		}
		_, _ = c.ShortTerm.DeleteMessage(ctx, msgs[1].ID)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 3)
		assertEqual(t, "msg[0]", conv.Messages[0].Content, "First")
		assertEqual(t, "msg[1]", conv.Messages[1].Content, "Third")
		assertEqual(t, "msg[2]", conv.Messages[2].Content, "Fourth")
	})

	t.Run("SPEC-2.5.5 delete first", func(t *testing.T) {
		cleanSessions(t, c)
		var msgs []*memory.Message
		for _, content := range []string{"First", "Second", "Third"} {
			msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, content)
			msgs = append(msgs, msg)
		}
		_, _ = c.ShortTerm.DeleteMessage(ctx, msgs[0].ID)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 2)
		assertEqual(t, "msg[0]", conv.Messages[0].Content, "Second")
		assertEqual(t, "msg[1]", conv.Messages[1].Content, "Third")
	})

	t.Run("SPEC-2.5.6 delete last", func(t *testing.T) {
		cleanSessions(t, c)
		var msgs []*memory.Message
		for _, content := range []string{"First", "Second", "Third"} {
			msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, content)
			msgs = append(msgs, msg)
		}
		_, _ = c.ShortTerm.DeleteMessage(ctx, msgs[2].ID)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 2)
		assertEqual(t, "msg[0]", conv.Messages[0].Content, "First")
		assertEqual(t, "msg[1]", conv.Messages[1].Content, "Second")
	})

	t.Run("SPEC-2.5.8 delete all one by one", func(t *testing.T) {
		cleanSessions(t, c)
		var msgs []*memory.Message
		for _, content := range []string{"One", "Two", "Three"} {
			msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, content)
			msgs = append(msgs, msg)
		}
		for _, msg := range msgs {
			deleted, err := c.ShortTerm.DeleteMessage(ctx, msg.ID)
			mustNoErr(t, err)
			if !deleted {
				t.Fatalf("expected true for %s", msg.ID)
			}
		}
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 0)
	})

	t.Run("SPEC-2.5.9 second delete returns false", func(t *testing.T) {
		cleanSessions(t, c)
		msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Once")
		first, _ := c.ShortTerm.DeleteMessage(ctx, msg.ID)
		if !first {
			t.Fatal("first delete should return true")
		}
		second, _ := c.ShortTerm.DeleteMessage(ctx, msg.ID)
		if second {
			t.Fatal("second delete should return false")
		}
	})
}

// ---------------------------------------------------------------------------
// ClearSession
// ---------------------------------------------------------------------------

func TestClearSession(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.6.1 removes all messages", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "One")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleAssistant, "Two")
		_ = c.ShortTerm.ClearSession(ctx, sessionA)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 0)
	})

	t.Run("SPEC-2.6.2 preserves other sessions", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta")
		_ = c.ShortTerm.ClearSession(ctx, sessionA)

		convB, _ := c.ShortTerm.GetConversation(ctx, sessionB)
		assertLen(t, "session B messages", len(convB.Messages), 1)
		assertEqual(t, "content", convB.Messages[0].Content, "Beta")
	})

	t.Run("SPEC-2.6.3 idempotent on empty", func(t *testing.T) {
		cleanSessions(t, c)
		err := c.ShortTerm.ClearSession(ctx, sessionA)
		mustNoErr(t, err)
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 0)
	})

	t.Run("SPEC-2.6.4 accepts messages after clear", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Before")
		_ = c.ShortTerm.ClearSession(ctx, sessionA)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "After")

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 1)
		assertEqual(t, "content", conv.Messages[0].Content, "After")
	})

	t.Run("SPEC-2.6.5 selective clear of three sessions", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Alpha")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionB, memory.RoleUser, "Beta")
		_, _ = c.ShortTerm.AddMessage(ctx, sessionC, memory.RoleUser, "Gamma")
		_ = c.ShortTerm.ClearSession(ctx, sessionB)

		convA, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		convB, _ := c.ShortTerm.GetConversation(ctx, sessionB)
		convC, _ := c.ShortTerm.GetConversation(ctx, sessionC)
		assertLen(t, "session A", len(convA.Messages), 1)
		assertLen(t, "session B", len(convB.Messages), 0)
		assertLen(t, "session C", len(convC.Messages), 1)
	})
}

// ---------------------------------------------------------------------------
// MessageChainStructure
// ---------------------------------------------------------------------------

func TestMessageChainStructure(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.7.1 insertion order", func(t *testing.T) {
		cleanSessions(t, c)
		contents := []string{"First", "Second", "Third", "Fourth", "Fifth"}
		for _, content := range contents {
			_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, content)
		}
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 5)
		for i, content := range contents {
			assertEqual(t, fmt.Sprintf("msg[%d]", i), conv.Messages[i].Content, content)
		}
	})

	t.Run("SPEC-2.7.5 chain integrity after middle delete", func(t *testing.T) {
		cleanSessions(t, c)
		var msgs []*memory.Message
		for _, c2 := range []string{"A", "B", "C", "D", "E"} {
			msg, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, c2)
			msgs = append(msgs, msg)
		}
		_, _ = c.ShortTerm.DeleteMessage(ctx, msgs[2].ID)

		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 4)
		expected := []string{"A", "B", "D", "E"}
		for i, e := range expected {
			assertEqual(t, fmt.Sprintf("msg[%d]", i), conv.Messages[i].Content, e)
		}
	})

	t.Run("SPEC-2.7.7 mixed roles maintain order", func(t *testing.T) {
		cleanSessions(t, c)
		sequence := []struct {
			role    memory.MessageRole
			content string
		}{
			{memory.RoleSystem, "You are helpful"},
			{memory.RoleUser, "Hello"},
			{memory.RoleAssistant, "Hi there"},
			{memory.RoleUser, "How are you?"},
			{memory.RoleAssistant, "I'm doing well"},
		}
		for _, s := range sequence {
			_, _ = c.ShortTerm.AddMessage(ctx, sessionA, s.role, s.content)
		}
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 5)
		for i, s := range sequence {
			assertEqual(t, fmt.Sprintf("role[%d]", i), string(conv.Messages[i].Role), string(s.role))
			assertEqual(t, fmt.Sprintf("content[%d]", i), conv.Messages[i].Content, s.content)
		}
	})
}

// ---------------------------------------------------------------------------
// Idempotency
// ---------------------------------------------------------------------------

func TestIdempotency(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()

	t.Run("SPEC-2.8.1 unique IDs", func(t *testing.T) {
		cleanSessions(t, c)
		msg1, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Same")
		msg2, _ := c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Same")
		if msg1.ID == msg2.ID {
			t.Fatal("expected unique IDs")
		}
	})

	t.Run("SPEC-2.8.2 duplicate content stored separately", func(t *testing.T) {
		cleanSessions(t, c)
		for i := 0; i < 3; i++ {
			_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Dup")
		}
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 3)
	})

	t.Run("SPEC-2.8.3 clear_session idempotent", func(t *testing.T) {
		cleanSessions(t, c)
		_, _ = c.ShortTerm.AddMessage(ctx, sessionA, memory.RoleUser, "Data")
		_ = c.ShortTerm.ClearSession(ctx, sessionA)
		err := c.ShortTerm.ClearSession(ctx, sessionA)
		mustNoErr(t, err)
		conv, _ := c.ShortTerm.GetConversation(ctx, sessionA)
		assertLen(t, "messages", len(conv.Messages), 0)
	})
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func mustNoErr(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func assertEqual(t *testing.T, field, got, want string) {
	t.Helper()
	if got != want {
		t.Fatalf("%s: got %q, want %q", field, got, want)
	}
}

func assertLen(t *testing.T, field string, got, want int) {
	t.Helper()
	if got != want {
		t.Fatalf("%s: got %d, want %d", field, got, want)
	}
}

func assertNonEmpty(t *testing.T, field, val string) {
	t.Helper()
	if val == "" {
		t.Fatalf("%s: expected non-empty", field)
	}
}
