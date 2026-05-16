test_that("MemoryClient picks RestTransport for /v1 endpoints", {
  client <- MemoryClient$new(
    endpoint = "https://memory.neo4jlabs.com/v1",
    api_key = "k"
  )
  expect_true(inherits(client$.__enclos_env__$private$transport, "RestTransport"))
})

test_that("MemoryClient picks BridgeTransport for localhost", {
  client <- MemoryClient$new(endpoint = "http://localhost:3001")
  expect_true(inherits(client$.__enclos_env__$private$transport, "BridgeTransport"))
})

test_that("explicit transport_mode='bridge' overrides auto", {
  client <- MemoryClient$new(
    endpoint = "https://memory.neo4jlabs.com/v1",
    transport_mode = "bridge"
  )
  expect_true(inherits(client$.__enclos_env__$private$transport, "BridgeTransport"))
})

test_that("explicit transport_mode='rest' overrides auto", {
  client <- MemoryClient$new(
    endpoint = "http://localhost:3001",
    api_key = "k",
    transport_mode = "rest"
  )
  expect_true(inherits(client$.__enclos_env__$private$transport, "RestTransport"))
})

test_that("RestTransport raises on unknown method", {
  rt <- RestTransport$new("https://memory.test/v1", api_key = "k")
  expect_error(rt$request("not_a_real_method"), "not supported")
})

test_that("RestTransport raises on legacy unsupported method", {
  rt <- RestTransport$new("https://memory.test/v1", api_key = "k")
  expect_error(
    rt$request("add_preference", list(category = "x", preference = "y")),
    "no equivalent"
  )
})

test_that("RestTransport noop methods return NULL silently", {
  rt <- RestTransport$new("https://memory.test/v1", api_key = "k")
  expect_null(rt$request("setup"))
  expect_null(rt$request("teardown"))
  expect_null(rt$request("clear_all_data"))
})
