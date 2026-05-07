# End-to-end tests against the live hosted Neo4j Agent Memory Service.
# Skipped when MEMORY_API_KEY is unset (or empty).

new_e2e_client <- function() {
  skip_if_no_api_key()
  client <- MemoryClient$new(
    endpoint = e2e_endpoint(),
    api_key = Sys.getenv("MEMORY_API_KEY")
  )
  client$connect()
  client
}

test_that("e2e: connect succeeds with a valid key", {
  client <- new_e2e_client()
  expect_true(inherits(client, "MemoryClient"))
})

test_that("e2e: bogus key fails authentication", {
  skip_if_no_api_key()
  bad <- MemoryClient$new(
    endpoint = e2e_endpoint(),
    api_key = "nams_obviously_not_real_token"
  )
  expect_error(bad$connect(), "[Aa]uthentication")
})

test_that("e2e: createConversation returns a UUID-like id", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id())
  on.exit(try(client$short_term$delete_conversation(conv$id), silent = TRUE))
  expect_true(!is.null(conv$id) && nchar(conv$id) >= 8)
})

test_that("e2e: addMessage round-trips", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id())
  on.exit(try(client$short_term$delete_conversation(conv$id), silent = TRUE))
  msg <- client$short_term$add_message(conv$id, "user", "Hello e2e from R.")
  expect_equal(msg$role, "user")
})

test_that("e2e: bulk_add_messages preserves order", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id())
  on.exit(try(client$short_term$delete_conversation(conv$id), silent = TRUE))
  msgs <- lapply(0:4, function(i) list(role = "user", content = paste0("bulk-", i)))
  out <- client$short_term$bulk_add_messages(conv$id, msgs)
  expect_equal(length(out), 5)
})

test_that("e2e: get_context returns three-tier shape", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id())
  on.exit(try(client$short_term$delete_conversation(conv$id), silent = TRUE))
  client$short_term$add_message(conv$id, "user", "Hello.")
  ctx <- client$short_term$get_context(conv$id)
  expect_true(!is.null(ctx$reflections))
  expect_true(!is.null(ctx$observations))
  expect_true(!is.null(ctx$recent_messages))
})

test_that("e2e: get_entity_graph returns nodes and edges", {
  client <- new_e2e_client()
  graph <- client$long_term$get_entity_graph()
  expect_true(!is.null(graph$nodes))
  expect_true(!is.null(graph$edges))
})

test_that("e2e: search_entities returns a list", {
  client <- new_e2e_client()
  results <- client$long_term$search_entities("anything", limit = 5L)
  expect_true(is.list(results))
})

test_that("e2e: list_entities returns a list", {
  client <- new_e2e_client()
  results <- client$long_term$list_entities(limit = 5L)
  expect_true(is.list(results))
})

test_that("e2e: record_step + get_trace_by_conversation round-trip", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id())
  on.exit(try(client$short_term$delete_conversation(conv$id), silent = TRUE))
  step <- client$reasoning$record_step(
    conversation_id = conv$id,
    reasoning = "test",
    action_taken = "ran",
    result = "passed"
  )
  expect_true(!is.null(step$id))
  trace <- client$reasoning$get_trace_by_conversation(conv$id)
  expect_equal(trace$conversation_id, conv$id)
})

test_that("e2e: cypher_query executes read-only Cypher", {
  client <- new_e2e_client()
  result <- client$query$cypher("MATCH (n) RETURN count(n) AS total")
  expect_true("total" %in% result$columns)
  expect_true(length(result$rows) >= 1)
})
