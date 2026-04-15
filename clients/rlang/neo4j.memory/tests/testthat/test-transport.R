test_that("HttpTransport strips trailing slashes from endpoint", {
  transport <- HttpTransport$new("http://localhost:3001/")
  expect_equal(transport$.__enclos_env__$private$endpoint, "http://localhost:3001")
})

test_that("HttpTransport requires endpoint", {
  expect_error(HttpTransport$new(NULL))
})
