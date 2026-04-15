# R Client — neo4j.memory

R client for neo4j-agent-memory, providing short-term, long-term, and reasoning memory operations via R6 classes and httr2.

## Installation

```r
# Install dependencies
install.packages(c("httr2", "R6", "jsonlite"))

# Install from source (from repo root)
install.packages("clients/rlang/neo4j.memory", repos = NULL, type = "source")

# Or using devtools
devtools::install("clients/rlang/neo4j.memory")
```

## Usage

```r
library(neo4j.memory)

# Connect to a memory service endpoint
mem <- MemoryClient$new(endpoint = "http://localhost:3001")
mem$connect()

# Short-term memory
msg <- mem$short_term$add_message("session-1", "user", "Hello, world!")
conv <- mem$short_term$get_conversation("session-1")

# Long-term memory
entity <- mem$long_term$add_entity("Alice", "PERSON", description = "A researcher")
results <- mem$long_term$search_entities("Alice")

# Reasoning memory
trace <- mem$reasoning$start_trace("session-1", "Analyze data")
step <- mem$reasoning$add_step(trace$id, thought = "Running regression")
mem$reasoning$record_tool_call(step$id, "lm", list(formula = "y ~ x"))
mem$reasoning$complete_trace(trace$id, outcome = "Analysis complete", success = TRUE)
```

## Running the Conformance Server

The conformance server bridges the Python TCK test suite to the R client via HTTP:

```bash
# Prerequisites
install.packages(c("plumber", "httr2", "R6", "jsonlite"))

# Start the server (from clients/rlang/conformance/)
cd clients/rlang/conformance
MEMORY_ENDPOINT=http://localhost:3001 Rscript server.R
```

## Running TCK Tests

```bash
# Start Neo4j and the bridge server first, then:
cd clients/rlang/conformance
MEMORY_ENDPOINT=http://localhost:3001 Rscript server.R

# In another terminal, run TCK tests against the R conformance server:
uv run pytest -m bronze --bridge-url http://localhost:3001 -v
```

## Package Structure

```
neo4j.memory/
├── R/
│   ��── transport.R       # HttpTransport R6 class
│   ├── models.R          # Parse helpers for wire-format JSON
│   ├── memory_client.R   # MemoryClient (composes sub-clients)
│   ├── short_term.R      # ShortTermMemory (6 methods)
│   ├── long_term.R       # LongTermMemory (9 methods)
│   └── reasoning.R       # ReasoningMemory (8 methods)
├── tests/testthat/       # Unit tests
├── DESCRIPTION
└── NAMESPACE
```

## Dependencies

- **httr2** (>= 1.0.0) — HTTP client
- **R6** (>= 2.5.0) — Reference classes
- **jsonlite** (>= 1.8.0) — JSON serialization
