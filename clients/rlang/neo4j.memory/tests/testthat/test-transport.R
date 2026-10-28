# --- HttpTransport construction ---

test_that("HttpTransport strips trailing slashes from endpoint", {
  transport <- HttpTransport$new("http://localhost:3001/")
  expect_equal(transport$.__enclos_env__$private$endpoint, "http://localhost:3001")
})

test_that("HttpTransport strips multiple trailing slashes", {
  transport <- HttpTransport$new("http://localhost:3001///")
  expect_equal(transport$.__enclos_env__$private$endpoint, "http://localhost:3001")
})

test_that("HttpTransport stores endpoint without trailing slash", {
  transport <- HttpTransport$new("http://localhost:3001")
  expect_equal(transport$.__enclos_env__$private$endpoint, "http://localhost:3001")
})

test_that("HttpTransport stores custom timeout", {
  transport <- HttpTransport$new("http://localhost:3001", timeout = 60)
  expect_equal(transport$.__enclos_env__$private$timeout, 60)
})

test_that("HttpTransport default timeout is 30", {
  transport <- HttpTransport$new("http://localhost:3001")
  expect_equal(transport$.__enclos_env__$private$timeout, 30)
})

test_that("HttpTransport close returns NULL invisibly", {
  transport <- HttpTransport$new("http://localhost:3001")
  result <- transport$close()
  expect_null(result)
})

test_that("HttpTransport request errors on unreachable server", {
  transport <- HttpTransport$new("http://localhost:59999", timeout = 1)
  expect_error(transport$request("setup"))
})

test_that("REST message search sends only supported body fields to the conversation route", {
  observed <- NULL
  transport <- RestTransport$new("https://memory.test/v1")
  client <- ShortTermMemory$new(transport)

  results <- httr2::with_mocked_responses(function(req) {
    observed <<- req
    httr2::response(headers = list(`content-type` = "application/json"),
      body = charToRaw('{"messages":[]}'))
  }, client$search_messages("Nobel", session_id = "conv-42", limit = 5L, threshold = 0.25))

  expect_equal(observed$method, "POST")
  expect_equal(observed$url, "https://memory.test/v1/conversations/conv-42/search")
  expect_equal(observed$body$data, list(query = "Nobel", limit = 5L))
  expect_equal(results, list())
})

test_that("REST Cypher queries omit absent optional parameters", {
  observed <- NULL
  wire <- NULL
  client <- MemoryClient$new(endpoint = "https://memory.test/v1")

  result <- httr2::with_mocked_responses(function(req) {
    observed <<- req
    wire <<- capture.output(httr2::req_dry_run(req))
    httr2::response(headers = list(`content-type` = "application/json"),
      body = charToRaw('{"columns":["total"],"rows":[{"total":1}]}'))
  }, client$query$cypher("RETURN 1 AS total"))

  expect_equal(observed$method, "POST")
  expect_equal(observed$url, "https://memory.test/v1/query")
  expect_identical(tail(wire, 1), '{"cypher":"RETURN 1 AS total"}')
  expect_equal(result$rows, list(list(total = 1)))
})

test_that("REST Cypher queries omit explicit empty parameter lists", {
  observed <- NULL
  wire <- NULL
  client <- MemoryClient$new(endpoint = "https://memory.test/v1")

  result <- httr2::with_mocked_responses(function(req) {
    observed <<- req
    wire <<- capture.output(httr2::req_dry_run(req))
    httr2::response(headers = list(`content-type` = "application/json"),
      body = charToRaw('{"columns":["total"],"rows":[{"total":1}]}'))
  }, client$query$cypher("RETURN 1 AS total", params = list()))

  expect_equal(observed$method, "POST")
  expect_equal(observed$url, "https://memory.test/v1/query")
  expect_identical(tail(wire, 1), '{"cypher":"RETURN 1 AS total"}')
  expect_equal(result$rows, list(list(total = 1)))
})

test_that("REST Cypher queries send named parameters as an object", {
  observed <- NULL
  wire <- NULL
  client <- MemoryClient$new(endpoint = "https://memory.test/v1")

  result <- httr2::with_mocked_responses(function(req) {
    observed <<- req
    wire <<- capture.output(httr2::req_dry_run(req))
    httr2::response(headers = list(`content-type` = "application/json"),
      body = charToRaw('{"columns":["label"],"rows":[{"label":"Alice"}]}'))
  }, client$query$cypher("RETURN $label AS label", params = list(label = "Alice")))

  expect_equal(observed$method, "POST")
  expect_equal(observed$url, "https://memory.test/v1/query")
  expect_identical(tail(wire, 1),
    '{"cypher":"RETURN $label AS label","params":{"label":"Alice"}}')
  expect_equal(result$rows, list(list(label = "Alice")))
})
