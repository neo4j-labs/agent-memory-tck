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
