# --- MemoryClient construction ---

test_that("MemoryClient creates sub-clients from endpoint", {
  client <- MemoryClient$new(endpoint = "http://localhost:3001")
  expect_true(inherits(client$short_term, "ShortTermMemory"))
  expect_true(inherits(client$long_term, "LongTermMemory"))
  expect_true(inherits(client$reasoning, "ReasoningMemory"))
})

test_that("MemoryClient accepts pre-built transport", {
  transport <- HttpTransport$new("http://localhost:3001")
  client <- MemoryClient$new(transport = transport)
  expect_true(inherits(client$short_term, "ShortTermMemory"))
})

test_that("MemoryClient requires endpoint or transport", {
  expect_error(MemoryClient$new(), "Either 'endpoint' or 'transport' must be provided")
})

test_that("MemoryClient prefers transport over endpoint", {
  transport <- HttpTransport$new("http://custom:9999")
  client <- MemoryClient$new(endpoint = "http://ignored:3001", transport = transport)
  private_env <- client$.__enclos_env__$private
  expect_equal(private_env$transport$.__enclos_env__$private$endpoint, "http://custom:9999")
})
