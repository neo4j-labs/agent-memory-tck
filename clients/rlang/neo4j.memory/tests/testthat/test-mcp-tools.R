test_that("mcp_tools returns exactly 12 tools", {
  tools <- mcp_tools()
  expect_equal(length(tools), 12)
})

test_that("mcp_tools use snake_case names", {
  tools <- mcp_tools()
  for (t in tools) {
    expect_match(t$name, "^memory_[a-z_]+$")
  }
})

test_that("mcp_tools includes all 12 standard names", {
  expected <- c(
    "memory_create_conversation", "memory_add_messages", "memory_get_context",
    "memory_search_messages", "memory_search_entities", "memory_get_entity",
    "memory_add_entity", "memory_get_entity_history", "memory_record_step",
    "memory_record_tool_call", "memory_get_trace", "memory_explain_decision"
  )
  got <- vapply(mcp_tools(), function(t) t$name, character(1))
  expect_setequal(got, expected)
})

test_that("each tool has an inputSchema", {
  for (t in mcp_tools()) {
    expect_true(!is.null(t$inputSchema))
    expect_equal(t$inputSchema$type, "object")
  }
})
