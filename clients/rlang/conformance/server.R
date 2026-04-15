r_libs_user <- Sys.getenv("R_LIBS_USER", "")
if (nchar(r_libs_user) > 0) .libPaths(c(r_libs_user, .libPaths()))
cat("R_LIBS_USER:", r_libs_user, "\n")
cat("Library paths:", paste(.libPaths(), collapse = "\n  "), "\n")
cat("plumber available:", requireNamespace("plumber", quietly = TRUE), "\n")

library(plumber)
library(jsonlite)

`%||%` <- function(x, y) if (is.null(x)) y else x

pkg_path <- file.path(dirname(getwd()), "neo4j.memory")
if (dir.exists(pkg_path)) {
  devtools_available <- requireNamespace("devtools", quietly = TRUE)
  if (devtools_available) {
    devtools::load_all(pkg_path, quiet = TRUE)
  } else {
    for (f in list.files(file.path(pkg_path, "R"), full.names = TRUE)) source(f)
  }
} else {
  library(neo4j.memory)
}

endpoint <- Sys.getenv("MEMORY_ENDPOINT")
if (nchar(endpoint) == 0) stop("MEMORY_ENDPOINT environment variable is required")

port <- as.integer(Sys.getenv("TCK_BRIDGE_PORT", "3001"))

client <- MemoryClient$new(endpoint = endpoint)

s <- function(body, key, default = "") {
  val <- body[[key]]
  if (is.null(val) || identical(val, "")) default else as.character(val)
}

i <- function(body, key, default = NULL) {
  val <- body[[key]]
  if (is.null(val)) default else as.integer(val)
}

d <- function(body, key, default = NULL) {
  val <- body[[key]]
  if (is.null(val)) default else as.double(val)
}

b <- function(body, key) {
  val <- body[[key]]
  if (is.null(val)) NULL else as.logical(val)
}

m <- function(body, key) {
  val <- body[[key]]
  if (is.null(val)) NULL else as.list(val)
}

opt_str <- function(body, key) {
  val <- body[[key]]
  if (is.null(val) || identical(val, "")) NULL else as.character(val)
}

null_json <- function(res) {
  res$status <- 200L
  res$setHeader("Content-Type", "application/json")
  res$body <- "null"
  res
}

no_content <- function(res) {
  res$status <- 204L
  res$body <- ""
  res
}

pr <- plumber::pr()
pr$setSerializer(plumber::serializer_json(auto_unbox = TRUE, null = "null"))

# --- Lifecycle ---

pr$handle("POST", "/setup", function(req, res) {
  list(ok = TRUE)
})

pr$handle("POST", "/teardown", function(req, res) {
  no_content(res)
})

pr$handle("POST", "/clear_all_data", function(req, res) {
  client$clear_all_data()
  no_content(res)
})

# --- Short-Term Memory ---

pr$handle("POST", "/add_message", function(req, res) {
  body <- req$body
  client$short_term$add_message(
    s(body, "session_id"),
    s(body, "role"),
    s(body, "content"),
    metadata = m(body, "metadata")
  )
})

pr$handle("POST", "/get_conversation", function(req, res) {
  body <- req$body
  client$short_term$get_conversation(
    s(body, "session_id"),
    limit = i(body, "limit")
  )
})

pr$handle("POST", "/search_messages", function(req, res) {
  body <- req$body
  client$short_term$search_messages(
    s(body, "query"),
    session_id = opt_str(body, "session_id"),
    limit = i(body, "limit", 10L),
    threshold = d(body, "threshold", 0.7)
  )
})

pr$handle("POST", "/list_sessions", function(req, res) {
  body <- req$body
  client$short_term$list_sessions(
    limit = i(body, "limit", 100L)
  )
})

pr$handle("POST", "/delete_message", function(req, res) {
  body <- req$body
  deleted <- client$short_term$delete_message(s(body, "message_id"))
  list(deleted = deleted)
})

pr$handle("POST", "/clear_session", function(req, res) {
  body <- req$body
  client$short_term$clear_session(s(body, "session_id"))
  no_content(res)
})

# --- Long-Term Memory ---

pr$handle("POST", "/add_entity", function(req, res) {
  body <- req$body
  client$long_term$add_entity(
    s(body, "name"),
    s(body, "entity_type"),
    description = opt_str(body, "description")
  )
})

pr$handle("POST", "/add_preference", function(req, res) {
  body <- req$body
  client$long_term$add_preference(
    s(body, "category"),
    s(body, "preference"),
    context = opt_str(body, "context")
  )
})

pr$handle("POST", "/add_fact", function(req, res) {
  body <- req$body
  client$long_term$add_fact(
    s(body, "subject"),
    s(body, "predicate"),
    s(body, "obj")
  )
})

pr$handle("POST", "/search_entities", function(req, res) {
  body <- req$body
  client$long_term$search_entities(
    s(body, "query"),
    limit = i(body, "limit", 10L)
  )
})

pr$handle("POST", "/search_preferences", function(req, res) {
  body <- req$body
  client$long_term$search_preferences(
    s(body, "query"),
    category = opt_str(body, "category"),
    limit = i(body, "limit", 10L)
  )
})

pr$handle("POST", "/get_entity_by_name", function(req, res) {
  body <- req$body
  result <- client$long_term$get_entity_by_name(s(body, "name"))
  if (is.null(result)) return(null_json(res))
  result
})

pr$handle("POST", "/get_related_entities", function(req, res) {
  body <- req$body
  client$long_term$get_related_entities(
    s(body, "entity_id"),
    relationship_type = opt_str(body, "relationship_type"),
    depth = i(body, "depth", 1L)
  )
})

pr$handle("POST", "/add_relationship", function(req, res) {
  body <- req$body
  client$long_term$add_relationship(
    s(body, "source_id"),
    s(body, "target_id"),
    s(body, "relationship_type"),
    properties = m(body, "properties")
  )
})

pr$handle("POST", "/merge_duplicate_entities", function(req, res) {
  body <- req$body
  client$long_term$merge_duplicate_entities(
    s(body, "source_id"),
    s(body, "target_id"),
    canonical_name = opt_str(body, "canonical_name")
  )
})

# --- Reasoning Memory ---

pr$handle("POST", "/start_trace", function(req, res) {
  body <- req$body
  client$reasoning$start_trace(
    s(body, "session_id"),
    s(body, "task")
  )
})

pr$handle("POST", "/add_step", function(req, res) {
  body <- req$body
  client$reasoning$add_step(
    s(body, "trace_id"),
    thought = opt_str(body, "thought"),
    action = opt_str(body, "action"),
    observation = opt_str(body, "observation")
  )
})

pr$handle("POST", "/record_tool_call", function(req, res) {
  body <- req$body
  client$reasoning$record_tool_call(
    s(body, "step_id"),
    s(body, "tool_name"),
    if (is.null(body$arguments)) list() else as.list(body$arguments),
    result = body$result,
    status = s(body, "status", "success"),
    duration_ms = i(body, "duration_ms"),
    error = opt_str(body, "error")
  )
})

pr$handle("POST", "/complete_trace", function(req, res) {
  body <- req$body
  client$reasoning$complete_trace(
    s(body, "trace_id"),
    outcome = opt_str(body, "outcome"),
    success = b(body, "success")
  )
})

pr$handle("POST", "/get_trace_with_steps", function(req, res) {
  body <- req$body
  result <- client$reasoning$get_trace_with_steps(s(body, "trace_id"))
  if (is.null(result)) return(null_json(res))
  result
})

pr$handle("POST", "/list_traces", function(req, res) {
  body <- req$body
  client$reasoning$list_traces(
    session_id = opt_str(body, "session_id"),
    limit = i(body, "limit", 100L)
  )
})

pr$handle("POST", "/get_tool_stats", function(req, res) {
  body <- req$body
  client$reasoning$get_tool_stats(
    tool_name = opt_str(body, "tool_name")
  )
})

pr$handle("POST", "/get_similar_traces", function(req, res) {
  body <- req$body
  client$reasoning$get_similar_traces(
    s(body, "task"),
    limit = i(body, "limit", 5L),
    success_only = b(body, "success_only") %||% TRUE
  )
})

cat(sprintf("R conformance server starting on port %d\n", port))
cat(sprintf("Memory endpoint: %s\n", endpoint))
pr$run(host = "0.0.0.0", port = port)
