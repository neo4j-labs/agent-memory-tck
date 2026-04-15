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
  expect_equal(length(conv$messages), 2)
  expect_equal(conv$messages[[1]]$role, "user")
  expect_equal(conv$messages[[2]]$content, "Hello")
})

test_that("parse_entity handles all fields", {
  data <- list(
    id = "e1", name = "Alice", type = "PERSON",
    subtype = "researcher", description = "A scientist",
    created_at = "2026-01-01T00:00:00Z"
  )
  entity <- parse_entity(data)
  expect_equal(entity$name, "Alice")
  expect_equal(entity$type, "PERSON")
  expect_equal(entity$subtype, "researcher")
  expect_null(entity$embedding)
})

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
    started_at = "2026-01-01T00:00:00Z"
  )
  trace <- parse_trace(data)
  expect_equal(length(trace$steps), 1)
  expect_equal(trace$steps[[1]]$thought, "thinking")
  expect_equal(length(trace$steps[[1]]$tool_calls), 1)
  expect_equal(trace$steps[[1]]$tool_calls[[1]]$tool_name, "lm")
  expect_equal(trace$steps[[1]]$tool_calls[[1]]$duration_ms, 150L)
})

test_that("parse_tool_stats computes fields correctly", {
  data <- list(
    name = "search", total_calls = 10, successful_calls = 8,
    failed_calls = 2, success_rate = 0.8, avg_duration_ms = 42.5
  )
  stats <- parse_tool_stats(data)
  expect_equal(stats$name, "search")
  expect_equal(stats$total_calls, 10L)
  expect_equal(stats$success_rate, 0.8)
  expect_equal(stats$avg_duration_ms, 42.5)
})

test_that("parse_fact handles object field", {
  data <- list(id = "f1", subject = "Alice", predicate = "WORKS_AT", object = "Acme")
  fact <- parse_fact(data)
  expect_equal(fact$subject, "Alice")
  expect_equal(fact$object, "Acme")
})

test_that("parse_session_info defaults message_count to 0", {
  data <- list(session_id = "s1", created_at = "2026-01-01T00:00:00Z")
  info <- parse_session_info(data)
  expect_equal(info$message_count, 0L)
})
