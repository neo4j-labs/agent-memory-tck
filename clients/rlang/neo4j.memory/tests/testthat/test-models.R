# --- parse_message ---

test_that("parse_message handles complete data", {
  data <- list(
    id = "abc-123",
    role = "user",
    content = "Hello",
    timestamp = "2026-01-01T00:00:00Z",
    embedding = c(0.1, 0.2, 0.3),
    metadata = list(source = "test")
  )
  msg <- parse_message(data)
  expect_equal(msg$id, "abc-123")
  expect_equal(msg$role, "user")
  expect_equal(msg$content, "Hello")
  expect_equal(msg$timestamp, "2026-01-01T00:00:00Z")
  expect_equal(msg$embedding, c(0.1, 0.2, 0.3))
  expect_equal(msg$metadata$source, "test")
})

test_that("parse_message handles missing optional fields", {
  data <- list(id = "abc", role = "assistant", content = "Hi", timestamp = "2026-01-01T00:00:00Z")
  msg <- parse_message(data)
  expect_null(msg$embedding)
  expect_equal(msg$metadata, list())
})

test_that("parse_message returns NULL for NULL input", {
  expect_null(parse_message(NULL))
})

test_that("parse_message preserves all three roles", {
  for (role in c("user", "assistant", "system")) {
    msg <- parse_message(list(id = "m1", role = role, content = "x", timestamp = "t"))
    expect_equal(msg$role, role)
  }
})

test_that("parse_message preserves nested metadata", {
  data <- list(
    id = "m1", role = "user", content = "x", timestamp = "t",
    metadata = list(nested = list(key = "value"), number = 42)
  )
  msg <- parse_message(data)
  expect_equal(msg$metadata$nested$key, "value")
  expect_equal(msg$metadata$number, 42)
})

# --- parse_conversation ---

test_that("parse_conversation maps messages", {
  data <- list(
    id = "conv-1",
    session_id = "sess-1",
    messages = list(
      list(id = "m1", role = "user", content = "Hi", timestamp = "2026-01-01T00:00:00Z"),
      list(id = "m2", role = "assistant", content = "Hello", timestamp = "2026-01-01T00:00:01Z")
    ),
    created_at = "2026-01-01T00:00:00Z"
  )
  conv <- parse_conversation(data)
  expect_equal(conv$id, "conv-1")
  expect_equal(conv$session_id, "sess-1")
  expect_equal(length(conv$messages), 2)
  expect_equal(conv$messages[[1]]$role, "user")
  expect_equal(conv$messages[[2]]$content, "Hello")
})

test_that("parse_conversation handles empty messages list", {
  data <- list(id = "conv-1", session_id = "s1", messages = list(), created_at = "t")
  conv <- parse_conversation(data)
  expect_equal(length(conv$messages), 0)
})

test_that("parse_conversation handles NULL messages", {
  data <- list(id = "conv-1", session_id = "s1", created_at = "t")
  conv <- parse_conversation(data)
  expect_equal(conv$messages, list())
})

test_that("parse_conversation returns NULL for NULL input", {
  expect_null(parse_conversation(NULL))
})

# --- parse_session_info ---

test_that("parse_session_info with all fields", {
  data <- list(session_id = "s1", message_count = 5, created_at = "t1", updated_at = "t2")
  info <- parse_session_info(data)
  expect_equal(info$session_id, "s1")
  expect_equal(info$message_count, 5L)
  expect_equal(info$updated_at, "t2")
})

test_that("parse_session_info defaults message_count to 0", {
  data <- list(session_id = "s1", created_at = "2026-01-01T00:00:00Z")
  info <- parse_session_info(data)
  expect_equal(info$message_count, 0L)
})

test_that("parse_session_info returns NULL for NULL input", {
  expect_null(parse_session_info(NULL))
})

# --- parse_entity ---

test_that("parse_entity handles all fields", {
  data <- list(
    id = "e1", name = "Alice", type = "PERSON",
    subtype = "researcher", description = "A scientist",
    embedding = c(0.1, 0.2), canonical_name = "alice",
    created_at = "2026-01-01T00:00:00Z"
  )
  entity <- parse_entity(data)
  expect_equal(entity$name, "Alice")
  expect_equal(entity$type, "PERSON")
  expect_equal(entity$subtype, "researcher")
  expect_equal(entity$description, "A scientist")
  expect_equal(entity$canonical_name, "alice")
  expect_equal(entity$embedding, c(0.1, 0.2))
})

test_that("parse_entity handles missing optional fields", {
  data <- list(id = "e1", name = "Bob", type = "ORGANIZATION", created_at = "t")
  entity <- parse_entity(data)
  expect_null(entity$subtype)
  expect_null(entity$description)
  expect_null(entity$embedding)
  expect_null(entity$canonical_name)
})

test_that("parse_entity returns NULL for NULL input", {
  expect_null(parse_entity(NULL))
})

test_that("parse_entity handles all POLE+O types", {
  for (type in c("PERSON", "ORGANIZATION", "LOCATION", "EVENT", "OBJECT")) {
    entity <- parse_entity(list(id = "e1", name = "x", type = type, created_at = "t"))
    expect_equal(entity$type, type)
  }
})

# --- parse_preference ---

test_that("parse_preference handles all fields", {
  data <- list(id = "p1", category = "theme", preference = "dark", context = "coding", embedding = c(1))
  pref <- parse_preference(data)
  expect_equal(pref$id, "p1")
  expect_equal(pref$category, "theme")
  expect_equal(pref$preference, "dark")
  expect_equal(pref$context, "coding")
})

test_that("parse_preference handles missing optional fields", {
  data <- list(id = "p1", category = "lang", preference = "R")
  pref <- parse_preference(data)
  expect_null(pref$context)
  expect_null(pref$embedding)
})

test_that("parse_preference returns NULL for NULL input", {
  expect_null(parse_preference(NULL))
})

# --- parse_fact ---

test_that("parse_fact handles all fields", {
  data <- list(id = "f1", subject = "Alice", predicate = "WORKS_AT", object = "Acme", embedding = c(0.5))
  fact <- parse_fact(data)
  expect_equal(fact$subject, "Alice")
  expect_equal(fact$predicate, "WORKS_AT")
  expect_equal(fact$object, "Acme")
  expect_equal(fact$embedding, c(0.5))
})

test_that("parse_fact returns NULL for NULL input", {
  expect_null(parse_fact(NULL))
})

# --- parse_relationship ---

test_that("parse_relationship handles all fields", {
  data <- list(
    id = "r1", source_id = "e1", target_id = "e2",
    relationship_type = "WORKS_AT", properties = list(since = "2020")
  )
  rel <- parse_relationship(data)
  expect_equal(rel$source_id, "e1")
  expect_equal(rel$target_id, "e2")
  expect_equal(rel$relationship_type, "WORKS_AT")
  expect_equal(rel$properties$since, "2020")
})

test_that("parse_relationship defaults properties to empty list", {
  data <- list(id = "r1", source_id = "e1", target_id = "e2", relationship_type = "KNOWS")
  rel <- parse_relationship(data)
  expect_equal(rel$properties, list())
})

test_that("parse_relationship returns NULL for NULL input", {
  expect_null(parse_relationship(NULL))
})

# --- parse_trace ---

test_that("parse_trace handles nested steps and tool_calls", {
  data <- list(
    id = "t1", session_id = "s1", task = "analyze",
    steps = list(
      list(
        id = "step1", trace_id = "t1", step_number = 1,
        thought = "thinking", action = "run_regression",
        tool_calls = list(
          list(id = "tc1", tool_name = "lm", arguments = list(x = "a"),
               status = "success", duration_ms = 150)
        )
      )
    ),
    outcome = "done", success = TRUE,
    started_at = "2026-01-01T00:00:00Z",
    completed_at = "2026-01-01T00:00:05Z"
  )
  trace <- parse_trace(data)
  expect_equal(trace$id, "t1")
  expect_equal(trace$task, "analyze")
  expect_equal(trace$outcome, "done")
  expect_true(trace$success)
  expect_equal(length(trace$steps), 1)
  expect_equal(trace$steps[[1]]$thought, "thinking")
  expect_equal(length(trace$steps[[1]]$tool_calls), 1)
  expect_equal(trace$steps[[1]]$tool_calls[[1]]$tool_name, "lm")
  expect_equal(trace$steps[[1]]$tool_calls[[1]]$duration_ms, 150L)
})

test_that("parse_trace handles empty steps", {
  data <- list(id = "t1", session_id = "s1", task = "x", started_at = "t")
  trace <- parse_trace(data)
  expect_equal(trace$steps, list())
  expect_null(trace$outcome)
  expect_null(trace$success)
})

test_that("parse_trace returns NULL for NULL input", {
  expect_null(parse_trace(NULL))
})

test_that("parse_trace handles multiple steps", {
  data <- list(
    id = "t1", session_id = "s1", task = "multi",
    steps = list(
      list(id = "s1", trace_id = "t1", step_number = 1, thought = "first"),
      list(id = "s2", trace_id = "t1", step_number = 2, action = "second"),
      list(id = "s3", trace_id = "t1", step_number = 3, observation = "third")
    ),
    started_at = "t"
  )
  trace <- parse_trace(data)
  expect_equal(length(trace$steps), 3)
  expect_equal(trace$steps[[1]]$step_number, 1L)
  expect_equal(trace$steps[[2]]$step_number, 2L)
  expect_equal(trace$steps[[3]]$step_number, 3L)
})

# --- parse_step ---

test_that("parse_step handles all optional fields", {
  data <- list(
    id = "s1", trace_id = "t1", step_number = 1,
    thought = "hmm", action = "do", observation = "saw"
  )
  step <- parse_step(data)
  expect_equal(step$thought, "hmm")
  expect_equal(step$action, "do")
  expect_equal(step$observation, "saw")
  expect_equal(step$tool_calls, list())
})

test_that("parse_step handles missing optional fields", {
  data <- list(id = "s1", trace_id = "t1", step_number = 2)
  step <- parse_step(data)
  expect_null(step$thought)
  expect_null(step$action)
  expect_null(step$observation)
})

test_that("parse_step returns NULL for NULL input", {
  expect_null(parse_step(NULL))
})

# --- parse_tool_call ---

test_that("parse_tool_call handles all fields", {
  data <- list(
    id = "tc1", tool_name = "search", arguments = list(q = "test"),
    result = "found", status = "success", duration_ms = 100, error = NULL
  )
  tc <- parse_tool_call(data)
  expect_equal(tc$tool_name, "search")
  expect_equal(tc$arguments$q, "test")
  expect_equal(tc$result, "found")
  expect_equal(tc$status, "success")
  expect_equal(tc$duration_ms, 100L)
  expect_null(tc$error)
})

test_that("parse_tool_call handles error status", {
  data <- list(
    id = "tc1", tool_name = "bad", arguments = list(),
    status = "error", error = "something broke"
  )
  tc <- parse_tool_call(data)
  expect_equal(tc$status, "error")
  expect_equal(tc$error, "something broke")
  expect_null(tc$duration_ms)
})

test_that("parse_tool_call handles all status values", {
  for (st in c("pending", "success", "failure", "error", "timeout", "cancelled")) {
    tc <- parse_tool_call(list(id = "x", tool_name = "t", arguments = list(), status = st))
    expect_equal(tc$status, st)
  }
})

test_that("parse_tool_call returns NULL for NULL input", {
  expect_null(parse_tool_call(NULL))
})

# --- parse_tool_stats ---

test_that("parse_tool_stats handles all fields", {
  data <- list(
    name = "search", total_calls = 10, successful_calls = 8,
    failed_calls = 2, success_rate = 0.8, avg_duration_ms = 42.5
  )
  stats <- parse_tool_stats(data)
  expect_equal(stats$name, "search")
  expect_equal(stats$total_calls, 10L)
  expect_equal(stats$successful_calls, 8L)
  expect_equal(stats$failed_calls, 2L)
  expect_equal(stats$success_rate, 0.8)
  expect_equal(stats$avg_duration_ms, 42.5)
})

test_that("parse_tool_stats defaults to zeros", {
  data <- list(name = "unused")
  stats <- parse_tool_stats(data)
  expect_equal(stats$total_calls, 0L)
  expect_equal(stats$successful_calls, 0L)
  expect_equal(stats$failed_calls, 0L)
  expect_equal(stats$success_rate, 0)
  expect_null(stats$avg_duration_ms)
})

test_that("parse_tool_stats returns NULL for NULL input", {
  expect_null(parse_tool_stats(NULL))
})
