// Forge — Data pipeline agent using Go.
//
// Forge enriches entity properties in the shared knowledge graph.
// It monitors for new entities and adds structured data like
// geographic coordinates, company details, and relationship metadata.
//
// Usage:
//
//	MEMORY_ENDPOINT=http://localhost:3001 go run .
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

var client *memory.Client

func main() {
	endpoint := os.Getenv("MEMORY_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:3001"
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8003"
	}

	apiKey := os.Getenv("MEMORY_API_KEY")
	opts := []memory.Option{memory.WithEndpoint(endpoint)}
	if apiKey != "" {
		opts = append(opts, memory.WithAPIKey(apiKey))
	}

	var err error
	client, err = memory.New(opts...)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}

	if err := client.Connect(context.Background()); err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/enrich", handleEnrich)
	mux.HandleFunc("/pipeline", handlePipeline)

	addr := ":" + port
	fmt.Printf("Forge agent running on http://localhost%s\n", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	json.NewEncoder(w).Encode(map[string]string{
		"status":    "healthy",
		"agent":     "forge",
		"framework": "go-custom-http",
	})
}

type enrichRequest struct {
	EntityName string            `json:"entity_name"`
	Properties map[string]string `json:"properties"`
}

func handleEnrich(w http.ResponseWriter, r *http.Request) {
	var req enrichRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	sessionID := fmt.Sprintf("forge-%d", time.Now().UnixMilli())

	// Look up the entity
	entity, err := client.LongTerm.GetEntityByName(ctx, req.EntityName)
	if err != nil || entity == nil {
		http.Error(w, fmt.Sprintf("Entity not found: %s", req.EntityName), http.StatusNotFound)
		return
	}

	// Start a reasoning trace for the enrichment
	trace, err := client.Reasoning.StartTrace(ctx, sessionID, fmt.Sprintf("Enrich entity: %s", req.EntityName))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Record enrichment step
	step, err := client.Reasoning.AddStep(ctx, trace.ID,
		memory.WithThought(fmt.Sprintf("Enriching %s with %d properties", req.EntityName, len(req.Properties))),
		memory.WithAction("enrich_entity"),
	)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Store each property as a fact
	factsCreated := 0
	for key, value := range req.Properties {
		_, err := client.LongTerm.AddFact(ctx, req.EntityName, key, value)
		if err != nil {
			log.Printf("Failed to add fact %s for %s: %v", key, req.EntityName, err)
			continue
		}
		factsCreated++
	}

	// Record tool call
	args := map[string]interface{}{
		"entity": req.EntityName,
		"facts":  factsCreated,
	}
	client.Reasoning.RecordToolCall(ctx, step.ID, "enrich_entity", args,
		memory.WithDurationMs(100),
	)

	// Complete trace
	client.Reasoning.CompleteTrace(ctx, trace.ID,
		memory.WithOutcome(fmt.Sprintf("Added %d facts to %s", factsCreated, req.EntityName)),
		memory.WithSuccess(true),
	)

	// Log the enrichment
	client.ShortTerm.AddMessage(ctx, sessionID, memory.RoleAssistant,
		fmt.Sprintf("Enriched %s with %d properties", req.EntityName, factsCreated),
	)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"entity":       req.EntityName,
		"facts_added":  factsCreated,
		"session_id":   sessionID,
	})
}

type pipelineRequest struct {
	Query string `json:"query"`
}

func handlePipeline(w http.ResponseWriter, r *http.Request) {
	var req pipelineRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	ctx := r.Context()

	// Search for entities matching the query
	entities, err := client.LongTerm.SearchEntities(ctx, req.Query, 10)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	results := make([]map[string]string, 0, len(entities))
	for _, e := range entities {
		results = append(results, map[string]string{
			"id":          e.ID,
			"name":        e.Name,
			"type":        e.Type,
			"description": e.Description,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"query":    req.Query,
		"entities": results,
		"count":    len(results),
	})
}
