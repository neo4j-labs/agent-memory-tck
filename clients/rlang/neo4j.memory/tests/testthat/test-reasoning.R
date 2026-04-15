# --- ReasoningMemory unit tests (using MockTransport) ---

test_that("start_trace sends session_id and task", {
  mock <- MockTransport$new()
  mock$set_response("start_trace", list(
    id = "t-1", session_id = "sess-1", task = "Research", started_at = "t"
  ))
  rm <- ReasoningMemory$new(mock)

  result <- rm$start_trace("sess-1", "Research")

  expect_equal(result$id, "t-1")
  expect_equal(result$task, "Research")
  call <- mock$last_call()
  expect_equal(call$params$session_id, "sess-1")
  expect_equal(call$params$task, "Research")
})

test_that("add_step sends optional fields", {
  mock <- MockTransport$new()
  mock$set_response("add_step", list(
    id = "s-1", trace_id = "t-1", step_number = 1
  ))
  rm <- ReasoningMemory$new(mock)

  result <- rm$add_step("t-1", thought = "thinking", action = "search")

  expect_equal(result$id, "s-1")
  expect_equal(result$step_number, 1L)
  call <- mock$last_call()
  expect_equal(call$params$thought, "thinking")
  expect_equal(call$params$action, "search")
  expect_false("observation" %in% names(call$params))
})

test_that("add_step omits all optional fields when NULL", {
  mock <- MockTransport$new()
  mock$set_response("add_step", list(id = "s-1", trace_id = "t-1", step_number = 1))
  rm <- ReasoningMemory$new(mock)

  rm$add_step("t-1")

  call <- mock$last_call()
  expect_equal(call$params$trace_id, "t-1")
  expect_false("thought" %in% names(call$params))
  expect_false("action" %in% names(call$params))
  expect_false("observation" %in% names(call$params))
})

test_that("record_tool_call sends all parameters", {
  mock <- MockTransport$new()
  mock$set_response("record_tool_call", list(
    id = "tc-1", tool_name = "web_search", status = "success"
  ))
  rm <- ReasoningMemory$new(mock)

  result <- rm$record_tool_call(
    "s-1", "web_search", list(query = "test"),
    result = "found it", status = "success", duration_ms = 150L, error = NULL
  )

  expect_equal(result$id, "tc-1")
  expect_equal(result$tool_name, "web_search")
  call <- mock$last_call()
  expect_equal(call$params$step_id, "s-1")
  expect_equal(call$params$tool_name, "web_search")
  expect_equal(call$params$arguments$query, "test")
  expect_equal(call$params$result, "found it")
  expect_equal(call$params$status, "success")
  expect_equal(call$params$duration_ms, 150L)
})

test_that("record_tool_call omits optional fields when NULL", {
  mock <- MockTransport$new()
  mock$set_response("record_tool_call", list(id = "tc-1", tool_name = "t", status = "success"))
  rm <- ReasoningMemory$new(mock)

  rm$record_tool_call("s-1", "t", list())

  call <- mock$last_call()
  expect_false("result" %in% names(call$params))
  expect_false("duration_ms" %in% names(call$params))
  expect_false("error" %in% names(call$params))
  expect_equal(call$params$status, "success")
})

test_that("complete_trace sends outcome and success", {
  mock <- MockTransport$new()
  mock$set_response("complete_trace", list(
    id = "t-1", session_id = "s1", task = "x", outcome = "Done", success = TRUE, started_at = "t"
  ))
  rm <- ReasoningMemory$new(mock)

  result <- rm$complete_trace("t-1", outcome = "Done", success = TRUE)

  expect_equal(result$outcome, "Done")
  expect_true(result$success)
  call <- mock$last_call()
  expect_equal(call$params$outcome, "Done")
  expect_true(call$params$success)
})

test_that("complete_trace omits optional fields when NULL", {
  mock <- MockTransport$new()
  mock$set_response("complete_trace", list(
    id = "t-1", session_id = "s1", task = "x", started_at = "t"
  ))
  rm <- ReasoningMemory$new(mock)

  rm$complete_trace("t-1")

  call <- mock$last_call()
  expect_false("outcome" %in% names(call$params))
  expect_false("success" %in% names(call$params))
})

test_that("get_trace_with_steps returns trace when found", {
  mock <- MockTransport$new()
  mock$set_response("get_trace_with_steps", list(
    id = "t-1", session_id = "s1", task = "analyze",
    steps = list(
      list(id = "s1", trace_id = "t-1", step_number = 1, thought = "hmm")
    ),
    started_at = "t"
  ))
  rm <- ReasoningMemory$new(mock)

  trace <- rm$get_trace_with_steps("t-1")

  expect_equal(trace$id, "t-1")
  expect_equal(length(trace$steps), 1)
  expect_equal(trace$steps[[1]]$thought, "hmm")
})

test_that("get_trace_with_steps returns NULL when not found", {
  mock <- MockTransport$new()
  mock$set_response("get_trace_with_steps", NULL)
  rm <- ReasoningMemory$new(mock)

  expect_null(rm$get_trace_with_steps("nonexistent"))
})

test_that("list_traces returns list of traces", {
  mock <- MockTransport$new()
  mock$set_response("list_traces", list(
    list(id = "t1", session_id = "s1", task = "a", started_at = "t"),
    list(id = "t2", session_id = "s1", task = "b", started_at = "t")
  ))
  rm <- ReasoningMemory$new(mock)

  traces <- rm$list_traces(session_id = "s1")

  expect_equal(length(traces), 2)
  expect_equal(traces[[1]]$task, "a")
  expect_equal(traces[[2]]$task, "b")
})

test_that("list_traces returns empty list on NULL", {
  mock <- MockTransport$new()
  mock$set_response("list_traces", NULL)
  rm <- ReasoningMemory$new(mock)

  expect_equal(rm$list_traces(), list())
})

test_that("list_traces passes session_id and limit", {
  mock <- MockTransport$new()
  mock$set_response("list_traces", list())
  rm <- ReasoningMemory$new(mock)

  rm$list_traces(session_id = "s1", limit = 50L)

  call <- mock$last_call()
  expect_equal(call$params$session_id, "s1")
  expect_equal(call$params$limit, 50L)
})

test_that("get_tool_stats returns list of stats", {
  mock <- MockTransport$new()
  mock$set_response("get_tool_stats", list(
    list(name = "search", total_calls = 10, successful_calls = 8,
         failed_calls = 2, success_rate = 0.8, avg_duration_ms = 42)
  ))
  rm <- ReasoningMemory$new(mock)

  stats <- rm$get_tool_stats()

  expect_equal(length(stats), 1)
  expect_equal(stats[[1]]$name, "search")
  expect_equal(stats[[1]]$total_calls, 10L)
})

test_that("get_tool_stats returns empty list on NULL", {
  mock <- MockTransport$new()
  mock$set_response("get_tool_stats", NULL)
  rm <- ReasoningMemory$new(mock)

  expect_equal(rm$get_tool_stats(), list())
})

test_that("get_tool_stats passes tool_name filter", {
  mock <- MockTransport$new()
  mock$set_response("get_tool_stats", list())
  rm <- ReasoningMemory$new(mock)

  rm$get_tool_stats(tool_name = "lm")

  call <- mock$last_call()
  expect_equal(call$params$tool_name, "lm")
})

test_that("get_similar_traces sends all parameters", {
  mock <- MockTransport$new()
  mock$set_response("get_similar_traces", list())
  rm <- ReasoningMemory$new(mock)

  rm$get_similar_traces("test task", limit = 3L, success_only = FALSE)

  call <- mock$last_call()
  expect_equal(call$params$task, "test task")
  expect_equal(call$params$limit, 3L)
  expect_false(call$params$success_only)
})

test_that("get_similar_traces returns empty list on NULL", {
  mock <- MockTransport$new()
  mock$set_response("get_similar_traces", NULL)
  rm <- ReasoningMemory$new(mock)

  expect_equal(rm$get_similar_traces("x"), list())
})
