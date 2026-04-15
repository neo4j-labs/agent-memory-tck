# --- ShortTermMemory unit tests (using MockTransport) ---

test_that("add_message sends correct parameters", {
  mock <- MockTransport$new()
  mock$set_response("add_message", list(
    id = "msg-1", role = "user", content = "Hello", timestamp = "2026-01-01T00:00:00Z"
  ))
  st <- ShortTermMemory$new(mock)

  result <- st$add_message("sess-1", "user", "Hello")

  expect_equal(result$id, "msg-1")
  expect_equal(result$role, "user")
  expect_equal(result$content, "Hello")
  call <- mock$last_call()
  expect_equal(call$method, "add_message")
  expect_equal(call$params$session_id, "sess-1")
  expect_equal(call$params$role, "user")
  expect_equal(call$params$content, "Hello")
})

test_that("add_message includes metadata when provided", {
  mock <- MockTransport$new()
  mock$set_response("add_message", list(
    id = "msg-2", role = "user", content = "Hi", timestamp = "t"
  ))
  st <- ShortTermMemory$new(mock)

  st$add_message("sess-1", "user", "Hi", metadata = list(key = "value"))

  call <- mock$last_call()
  expect_equal(call$params$metadata$key, "value")
})

test_that("add_message omits metadata when NULL", {
  mock <- MockTransport$new()
  mock$set_response("add_message", list(
    id = "msg-3", role = "user", content = "x", timestamp = "t"
  ))
  st <- ShortTermMemory$new(mock)

  st$add_message("sess-1", "user", "x")

  call <- mock$last_call()
  expect_false("metadata" %in% names(call$params))
})

test_that("get_conversation returns parsed conversation", {
  mock <- MockTransport$new()
  mock$set_response("get_conversation", list(
    id = "conv-1", session_id = "sess-1",
    messages = list(
      list(id = "m1", role = "user", content = "Hi", timestamp = "t")
    ),
    created_at = "t"
  ))
  st <- ShortTermMemory$new(mock)

  conv <- st$get_conversation("sess-1")

  expect_equal(conv$id, "conv-1")
  expect_equal(length(conv$messages), 1)
  expect_equal(conv$messages[[1]]$content, "Hi")
})

test_that("get_conversation passes limit when provided", {
  mock <- MockTransport$new()
  mock$set_response("get_conversation", list(
    id = "c1", session_id = "s1", messages = list(), created_at = "t"
  ))
  st <- ShortTermMemory$new(mock)

  st$get_conversation("s1", limit = 5L)

  call <- mock$last_call()
  expect_equal(call$params$limit, 5L)
})

test_that("get_conversation omits limit when NULL", {
  mock <- MockTransport$new()
  mock$set_response("get_conversation", list(
    id = "c1", session_id = "s1", messages = list(), created_at = "t"
  ))
  st <- ShortTermMemory$new(mock)

  st$get_conversation("s1")

  call <- mock$last_call()
  expect_false("limit" %in% names(call$params))
})

test_that("search_messages returns list of messages", {
  mock <- MockTransport$new()
  mock$set_response("search_messages", list(
    list(id = "m1", role = "user", content = "match", timestamp = "t")
  ))
  st <- ShortTermMemory$new(mock)

  results <- st$search_messages("test query")

  expect_equal(length(results), 1)
  expect_equal(results[[1]]$content, "match")
})

test_that("search_messages returns empty list on NULL response", {
  mock <- MockTransport$new()
  mock$set_response("search_messages", NULL)
  st <- ShortTermMemory$new(mock)

  results <- st$search_messages("nothing")

  expect_equal(results, list())
})

test_that("search_messages passes all parameters", {
  mock <- MockTransport$new()
  mock$set_response("search_messages", list())
  st <- ShortTermMemory$new(mock)

  st$search_messages("q", session_id = "s1", limit = 5L, threshold = 0.9)

  call <- mock$last_call()
  expect_equal(call$params$query, "q")
  expect_equal(call$params$session_id, "s1")
  expect_equal(call$params$limit, 5L)
  expect_equal(call$params$threshold, 0.9)
})

test_that("list_sessions returns list of session infos", {
  mock <- MockTransport$new()
  mock$set_response("list_sessions", list(
    list(session_id = "s1", message_count = 3, created_at = "t"),
    list(session_id = "s2", message_count = 1, created_at = "t")
  ))
  st <- ShortTermMemory$new(mock)

  sessions <- st$list_sessions()

  expect_equal(length(sessions), 2)
  expect_equal(sessions[[1]]$session_id, "s1")
  expect_equal(sessions[[2]]$message_count, 1L)
})

test_that("list_sessions returns empty list on NULL", {
  mock <- MockTransport$new()
  mock$set_response("list_sessions", NULL)
  st <- ShortTermMemory$new(mock)

  expect_equal(st$list_sessions(), list())
})

test_that("delete_message returns TRUE when deleted", {
  mock <- MockTransport$new()
  mock$set_response("delete_message", list(deleted = TRUE))
  st <- ShortTermMemory$new(mock)

  expect_true(st$delete_message("msg-1"))
})

test_that("delete_message returns FALSE when not found", {
  mock <- MockTransport$new()
  mock$set_response("delete_message", list(deleted = FALSE))
  st <- ShortTermMemory$new(mock)

  expect_false(st$delete_message("nonexistent"))
})

test_that("clear_session calls transport", {
  mock <- MockTransport$new()
  mock$set_response("clear_session", NULL)
  st <- ShortTermMemory$new(mock)

  st$clear_session("sess-1")

  expect_equal(mock$call_count("clear_session"), 1)
  expect_equal(mock$last_call()$params$session_id, "sess-1")
})
