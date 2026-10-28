# --- Integration tests (require a live memory service) ---
# These tests run only when MEMORY_ENDPOINT is set.
# They verify the full client -> HTTP -> service round-trip.

skip_if_no_endpoint <- function() {
  endpoint <- Sys.getenv("MEMORY_ENDPOINT", "")
  if (nchar(endpoint) == 0) {
    skip("MEMORY_ENDPOINT not set — skipping integration tests")
  }
}

test_that("integration: add and retrieve message round-trip", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  msg <- client$short_term$add_message("r-integration-test", "user", "Hello from R")
  expect_true(nchar(msg$id) > 0)
  expect_equal(msg$role, "user")
  expect_equal(msg$content, "Hello from R")

  conv <- client$short_term$get_conversation("r-integration-test")
  expect_equal(conv$session_id, "r-integration-test")
  expect_true(length(conv$messages) >= 1)
  found <- any(vapply(conv$messages, function(m) m$content == "Hello from R", logical(1)))
  expect_true(found)
})

test_that("integration: list sessions includes created session", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  client$short_term$add_message("r-integration-sessions", "user", "test")
  sessions <- client$short_term$list_sessions()
  sids <- vapply(sessions, function(s) s$session_id, character(1))
  expect_true("r-integration-sessions" %in% sids)
})

test_that("integration: delete message", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  msg <- client$short_term$add_message("r-integration-delete", "user", "to delete")
  deleted <- client$short_term$delete_message(msg$id)
  expect_true(deleted)
})

test_that("integration: clear session", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  client$short_term$add_message("r-integration-clear", "user", "will clear")
  client$short_term$clear_session("r-integration-clear")
  conv <- client$short_term$get_conversation("r-integration-clear")
  expect_equal(length(conv$messages), 0)
})

test_that("integration: add and search entity", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  entity <- client$long_term$add_entity("R-Test-Entity", "OBJECT", description = "Created by R test")
  expect_true(nchar(entity$id) > 0)
  expect_equal(entity$name, "R-Test-Entity")
  expect_equal(entity$type, "OBJECT")

  results <- client$long_term$search_entities("R-Test-Entity")
  expect_true(length(results) >= 1)
})

test_that("integration: get entity by name", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  client$long_term$add_entity("R-Lookup-Entity", "PERSON")
  found <- client$long_term$get_entity_by_name("R-Lookup-Entity")
  expect_false(is.null(found))
  expect_equal(found$name, "R-Lookup-Entity")

  missing <- client$long_term$get_entity_by_name("nonexistent-entity-xyz-999")
  expect_null(missing)
})

test_that("integration: add preference and fact", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  pref <- client$long_term$add_preference("language", "R", context = "data science")
  expect_true(nchar(pref$id) > 0)
  expect_equal(pref$category, "language")

  fact <- client$long_term$add_fact("R", "IS_A", "programming language")
  expect_true(nchar(fact$id) > 0)
  expect_equal(fact$subject, "R")
  expect_equal(fact$predicate, "IS_A")
})

test_that("integration: reasoning trace lifecycle", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  trace <- client$reasoning$start_trace("r-integration-reasoning", "Test analysis")
  expect_true(nchar(trace$id) > 0)
  expect_equal(trace$task, "Test analysis")

  step <- client$reasoning$add_step(trace$id,
    thought = "Analyzing data", action = "run_summary")
  expect_true(nchar(step$id) > 0)
  expect_equal(step$step_number, 1L)

  tc <- client$reasoning$record_tool_call(
    step$id, "summary", list(n = 10),
    result = list(mean = 5.0), status = "success", duration_ms = 50L)
  expect_true(nchar(tc$id) > 0)
  expect_equal(tc$tool_name, "summary")

  completed <- client$reasoning$complete_trace(trace$id,
    outcome = "Analysis complete", success = TRUE)
  expect_equal(completed$outcome, "Analysis complete")
  expect_true(completed$success)

  fetched <- client$reasoning$get_trace_with_steps(trace$id)
  expect_false(is.null(fetched))
  expect_equal(fetched$task, "Test analysis")
  expect_true(length(fetched$steps) >= 1)
})

test_that("integration: list traces by session", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  client$reasoning$start_trace("r-integration-list-traces", "Trace 1")
  traces <- client$reasoning$list_traces(session_id = "r-integration-list-traces")
  expect_true(length(traces) >= 1)
})

test_that("integration: get_trace_with_steps returns NULL for unknown ID", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  result <- client$reasoning$get_trace_with_steps("00000000-0000-0000-0000-000000000000")
  expect_null(result)
})

test_that("integration: clear_all_data resets state", {
  skip_if_no_endpoint()
  client <- MemoryClient$new(endpoint = Sys.getenv("MEMORY_ENDPOINT"))

  client$short_term$add_message("r-integration-clearall", "user", "before clear")
  client$clear_all_data()

  conv <- client$short_term$get_conversation("r-integration-clearall")
  expect_equal(length(conv$messages), 0)
})
