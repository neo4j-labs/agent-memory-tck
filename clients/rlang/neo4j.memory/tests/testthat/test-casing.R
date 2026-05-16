test_that("snake_to_camel rewrites top-level keys", {
  expect_equal(snake_to_camel(list(user_id = "x")), list(userId = "x"))
})

test_that("snake_to_camel leaves camelCase unchanged", {
  expect_equal(snake_to_camel(list(userId = "x")), list(userId = "x"))
})

test_that("snake_to_camel handles nested objects", {
  result <- snake_to_camel(list(outer_key = list(inner_key = 1)))
  expect_equal(result, list(outerKey = list(innerKey = 1)))
})

test_that("snake_to_camel handles unnamed lists", {
  expect_equal(
    snake_to_camel(list(list(a_b = 1), list(c_d = 2))),
    list(list(aB = 1), list(cD = 2))
  )
})

test_that("snake_to_camel returns primitives unchanged", {
  expect_equal(snake_to_camel("hello"), "hello")
  expect_equal(snake_to_camel(42), 42)
  expect_null(snake_to_camel(NULL))
  expect_equal(snake_to_camel(TRUE), TRUE)
})

test_that("camel_to_snake rewrites top-level keys", {
  expect_equal(camel_to_snake(list(userId = "x")), list(user_id = "x"))
})

test_that("camel_to_snake leaves snake_case unchanged", {
  expect_equal(camel_to_snake(list(user_id = "x")), list(user_id = "x"))
})

test_that("camel_to_snake handles nested objects", {
  result <- camel_to_snake(list(outerKey = list(innerKey = 1)))
  expect_equal(result, list(outer_key = list(inner_key = 1)))
})

test_that("snake/camel roundtrip is idempotent", {
  original <- list(
    user_id = "alice",
    metadata = list(is_active = TRUE, tags = list("a", "b"))
  )
  expect_equal(camel_to_snake(snake_to_camel(original)), original)
})
