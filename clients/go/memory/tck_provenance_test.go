//go:build e2e

// Provenance tagging for e2e tests.
//
// Every conversation, entity, and reasoning step the e2e suite creates is
// tagged with metadata that traces it back to:
//
//   - the language client            (tck_client)
//   - the specific test              (tck_test)
//   - the GitHub Actions run         (tck_run_id, tck_run_attempt)
//   - the commit SHA + branch        (tck_sha, tck_branch)
//   - the suite start time           (tck_started_at)
//   - the runner / hostname          (tck_host)
//
// Querying provenance after the fact (with workspace-admin Cypher access):
//
//	MATCH (c:Conversation) WHERE c.metadata.tck_run_id = '12345' RETURN c
//	MATCH (e:Entity) WHERE e.description STARTS WITH '[tck:go' RETURN e
//	MATCH (s:AgentStep) WHERE s.reasoning STARTS WITH 'TCK e2e' RETURN s

package memory_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

const tckClientName = "go"

var (
	runInfoOnce sync.Once
	runInfoVal  map[string]interface{}
)

func tckRunInfo() map[string]interface{} {
	runInfoOnce.Do(func() {
		sha := os.Getenv("GITHUB_SHA")
		if sha == "" {
			sha = "local"
		}
		if len(sha) > 7 {
			sha = sha[:7]
		}
		host, _ := os.Hostname()
		if v := os.Getenv("RUNNER_NAME"); v != "" {
			host = v
		}
		runInfoVal = map[string]interface{}{
			"tck_client":      tckClientName,
			"tck_run_id":      defaultEnv("GITHUB_RUN_ID", "local"),
			"tck_run_attempt": defaultEnv("GITHUB_RUN_ATTEMPT", "1"),
			"tck_workflow":    defaultEnv("GITHUB_WORKFLOW", "local"),
			"tck_sha":         sha,
			"tck_branch":      defaultEnv("GITHUB_REF_NAME", "local"),
			"tck_started_at":  time.Now().UTC().Format(time.RFC3339),
			"tck_host":        host,
		}
	})
	out := make(map[string]interface{}, len(runInfoVal))
	for k, v := range runInfoVal {
		out[k] = v
	}
	return out
}

func defaultEnv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// tckMetadataFor returns the per-test metadata bundle.
func tckMetadataFor(testName string, extra map[string]interface{}) map[string]interface{} {
	out := tckRunInfo()
	out["tck_test"] = testName
	for k, v := range extra {
		out[k] = v
	}
	return out
}

// tckTagDescription prefixes an entity description with a searchable
// provenance tag.
func tckTagDescription(testName, description string) string {
	info := tckRunInfo()
	return fmt.Sprintf("[tck:%s:%s:%s] %s",
		info["tck_client"], info["tck_run_id"], testName, description)
}

func tckProvenanceReasoning(testName, phase string) string {
	info := tckRunInfo()
	return fmt.Sprintf("TCK e2e test %s: %s [client=%s, run=%s, sha=%s, branch=%s]",
		phase, testName,
		info["tck_client"], info["tck_run_id"], info["tck_sha"], info["tck_branch"])
}

func tckProvenanceResult(testName string, extra map[string]interface{}) string {
	buf, _ := json.Marshal(tckMetadataFor(testName, extra))
	return string(buf)
}

// tckRecordProvenanceStep is the best-effort hook called after a test
// fixture creates a conversation. It records a reasoning step on the
// conversation tying it back to the test/run/sha. Never fails the test.
func tckRecordProvenanceStep(c *memory.Client, conversationID, testName, phase, action string) {
	_, _ = c.Reasoning.RecordStep(context.Background(), memory.RecordStepInput{
		ConversationID: conversationID,
		Reasoning:      tckProvenanceReasoning(testName, phase),
		ActionTaken:    action,
		Result:         tckProvenanceResult(testName, map[string]interface{}{"conversation_id": conversationID}),
	})
}
